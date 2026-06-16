package com.example.semcache.app;

import com.example.semcache.oracle.AnswerProvider;
import com.example.semcache.oracle.SemanticCacheRequest;
import com.example.semcache.oracle.SemanticCacheResponse;
import com.example.semcache.oracle.SemanticCacheService;
import oracle.jdbc.pool.OracleDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

final class FullBenchmarkRunner {
    private static final String CHAT_MODEL = "gpt-4o-mini";
    private static final String EMBEDDING_MODEL = "text-embedding-3-small";
    private static final double THRESHOLD = 0.10;
    private static final double INPUT_COST_PER_MILLION = 0.15;
    private static final double OUTPUT_COST_PER_MILLION = 0.60;

    private FullBenchmarkRunner() {
    }

    static void run() throws Exception {
        BenchmarkConfig config = BenchmarkConfig.fromEnvironment();
        if (!config.providerMode().equals("deterministic")) {
            throw new IllegalArgumentException("Only BENCHMARK_PROVIDER_MODE=deterministic is implemented in this demo. Live provider mode is intentionally deferred until token usage can be captured from the selected provider API.");
        }

        DataSource primary = oracleDataSource(config.primaryJdbcUrl(), config.user(), config.password());
        DataSource trueCache = oracleDataSource(config.trueCacheJdbcUrl(), config.user(), config.password());
        List<WorkloadItem> workload = WorkloadItem.generate(config.requests());
        BenchmarkProvider provider = new BenchmarkProvider(config.providerLatencyMs());
        List<BenchmarkEvent> allEvents = new ArrayList<>();
        List<ModeSummary> summaries = new ArrayList<>();

        System.out.println();
        System.out.println("== Full semantic cache benchmark ==");
        System.out.println("Requests per measured mode: " + config.requests());
        System.out.println("Concurrency: " + config.concurrency());
        System.out.println("Provider mode: deterministic mock");
        System.out.println("Provider latency simulation: " + config.providerLatencyMs() + " ms per provider call");
        System.out.println("Prewarm visibility pause: " + config.prewarmPauseMs() + " ms");
        System.out.println("Output: reports/generated/benchmark-full-*");

        summaries.add(runNoCacheMode(config, provider, workload, allEvents));
        summaries.add(runCacheMode(config, "exact-cache", primary, trueCache, "true-cache", provider, workload, allEvents, true, -1.0));
        summaries.add(runCacheMode(config, "semantic-cache-primary", primary, primary, "primary", provider, workload, allEvents, true, THRESHOLD));
        summaries.add(runCacheMode(config, "semantic-cache-true-cache", primary, trueCache, "true-cache", provider, workload, allEvents, true, THRESHOLD));
        summaries.add(runCacheMode(config, "semantic-cache-write-through", primary, trueCache, "true-cache", provider, WorkloadItem.generateColdMissOnly(config.requests()), allEvents, false, THRESHOLD));

        DatabaseEvidence evidence = DatabaseEvidence.collect(primary, trueCache);
        writeReports(config, summaries, allEvents, evidence);
        printSummary(summaries);
    }

    private static ModeSummary runNoCacheMode(
            BenchmarkConfig config,
            BenchmarkProvider provider,
            List<WorkloadItem> workload,
            List<BenchmarkEvent> allEvents) throws Exception {
        long started = System.nanoTime();
        List<BenchmarkEvent> events = runConcurrent(config, "no-cache", workload, item -> {
            long requestStarted = System.nanoTime();
            String answer = provider.generate(item.request("no-cache", THRESHOLD));
            TokenUsage tokenUsage = TokenUsage.from(item.prompt(), answer, true);
            return new BenchmarkEvent(
                "no-cache",
                item.index(),
                item.type(),
                "provider-call",
                "none",
                1,
                elapsedMillis(requestStarted),
                null,
                THRESHOLD,
                tokenUsage.promptTokens(),
                tokenUsage.completionTokens(),
                tokenUsage.totalTokens(),
                tokenUsage.estimatedCostUsd());
        });
        long wallClockMs = elapsedMillis(started);
        allEvents.addAll(events);
        return ModeSummary.from("no-cache", events, wallClockMs, config.requests());
    }

    private static ModeSummary runCacheMode(
            BenchmarkConfig config,
            String mode,
            DataSource primary,
            DataSource read,
            String routeName,
            BenchmarkProvider provider,
            List<WorkloadItem> workload,
            List<BenchmarkEvent> allEvents,
            boolean prewarm,
            double threshold) throws Exception {
        if (config.concurrency() == 1) {
            try (Connection primaryConnection = primary.getConnection()) {
                SemanticCacheService setupCache = new SemanticCacheService(primaryConnection, primaryConnection, "primary", provider);
                setupCache.reset();
                if (prewarm) {
                    prewarm(setupCache, threshold);
                    if (config.prewarmPauseMs() > 0) {
                        Thread.sleep(config.prewarmPauseMs());
                    }
                }
                try (Connection readConnection = read.getConnection()) {
                    SemanticCacheService cache = new SemanticCacheService(primaryConnection, readConnection, routeName, provider);
                    return runPreparedCacheMode(config, mode, cache, workload, allEvents, threshold);
                }
            }
        }

        SemanticCacheService cache = new SemanticCacheService(primary, read, routeName, provider);
        return runCacheModeWithService(config, mode, cache, workload, allEvents, prewarm, threshold);
    }

    private static ModeSummary runCacheModeWithService(
            BenchmarkConfig config,
            String mode,
            SemanticCacheService cache,
            List<WorkloadItem> workload,
            List<BenchmarkEvent> allEvents,
            boolean prewarm,
            double threshold) throws Exception {
        cache.reset();
        if (prewarm) {
            prewarm(cache, threshold);
            if (config.prewarmPauseMs() > 0) {
                Thread.sleep(config.prewarmPauseMs());
            }
        }

        long started = System.nanoTime();
        List<BenchmarkEvent> events = runConcurrent(config, mode, workload, item -> {
            SemanticCacheResponse response = cache.answer(item.request(mode, threshold));
            TokenUsage tokenUsage = TokenUsage.from(item.prompt(), response.answer(), response.providerCalls() > 0);
            return BenchmarkEvent.from(mode, item, response, tokenUsage);
        });
        long wallClockMs = elapsedMillis(started);
        allEvents.addAll(events);
        return ModeSummary.from(mode, events, wallClockMs, config.requests());
    }

    private static ModeSummary runPreparedCacheMode(
            BenchmarkConfig config,
            String mode,
            SemanticCacheService cache,
            List<WorkloadItem> workload,
            List<BenchmarkEvent> allEvents,
            double threshold) throws Exception {
        long started = System.nanoTime();
        List<BenchmarkEvent> events = runConcurrent(config, mode, workload, item -> {
            SemanticCacheResponse response = cache.answer(item.request(mode, threshold));
            TokenUsage tokenUsage = TokenUsage.from(item.prompt(), response.answer(), response.providerCalls() > 0);
            return BenchmarkEvent.from(mode, item, response, tokenUsage);
        });
        long wallClockMs = elapsedMillis(started);
        allEvents.addAll(events);
        return ModeSummary.from(mode, events, wallClockMs, config.requests());
    }

    private static List<BenchmarkEvent> runConcurrent(
            BenchmarkConfig config,
            String mode,
            List<WorkloadItem> workload,
            BenchmarkCallableFactory factory) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(config.concurrency());
        try {
            List<Future<BenchmarkEvent>> futures = new ArrayList<>();
            for (WorkloadItem item : workload) {
                futures.add(executor.submit(() -> factory.create(item)));
            }
            List<BenchmarkEvent> events = new ArrayList<>();
            for (Future<BenchmarkEvent> future : futures) {
                events.add(future.get());
            }
            events.sort(Comparator.comparingInt(BenchmarkEvent::requestIndex));
            System.out.println(mode + ": completed " + events.size() + " requests");
            return events;
        } finally {
            executor.shutdownNow();
        }
    }

    private static void prewarm(SemanticCacheService cache, double threshold) throws SQLException {
        cache.answer(WorkloadItem.seedExact().request("prewarm-exact", threshold));
        cache.answer(WorkloadItem.seedSemantic().request("prewarm-semantic", threshold));
        cache.seedExpiredEntry(
            new SemanticCacheRequest(
                "prewarm-expired",
                "store-a",
                "Can I return unopened clearance shoes?",
                CHAT_MODEL,
                EMBEDDING_MODEL,
                4,
                "returns-template-v1",
                "returns-policy-expired",
                "policy-v1",
                FullBenchmarkRunner.vector(0.10, 0.20, 0.30, 0.40),
                threshold),
            "Expired cache entry for benchmark.");
    }

    private static void writeReports(
            BenchmarkConfig config,
            List<ModeSummary> summaries,
            List<BenchmarkEvent> events,
            DatabaseEvidence evidence) throws IOException {
        Path output = Path.of("reports/generated");
        Files.createDirectories(output);
        writeConfig(output, config);
        writeEvents(output, events);
        writeLatency(output, summaries);
        writeCost(output, summaries);
        writeSummaryJson(output, summaries, evidence);
        writeSummaryMarkdown(output, config, summaries, evidence);
    }

    private static void writeConfig(Path output, BenchmarkConfig config) throws IOException {
        String json = """
            {
              "generated_at": "%s",
              "requests_per_mode": %d,
              "concurrency": %d,
              "provider_mode": "%s",
              "provider_latency_ms": %d,
              "prewarm_pause_ms": %d,
              "benchmark_seed": %d,
              "primary_jdbc_url": "%s",
              "true_cache_jdbc_url": "%s",
              "java_version": "%s",
              "os": "%s %s"
            }
            """.formatted(
            Instant.now(),
            config.requests(),
            config.concurrency(),
            config.providerMode(),
            config.providerLatencyMs(),
            config.prewarmPauseMs(),
            config.seed(),
            escape(config.primaryJdbcUrl()),
            escape(config.trueCacheJdbcUrl()),
            escape(System.getProperty("java.version")),
            escape(System.getProperty("os.name")),
            escape(System.getProperty("os.version")));
        Files.writeString(output.resolve("benchmark-full-config.json"), json);
    }

    private static void writeEvents(Path output, List<BenchmarkEvent> events) throws IOException {
        StringBuilder csv = new StringBuilder("mode,request_index,workload_type,decision,route,provider_calls,latency_ms,distance,threshold,prompt_tokens,completion_tokens,total_tokens,estimated_cost_usd\n");
        for (BenchmarkEvent event : events) {
            csv.append(event.mode()).append(',')
                .append(event.requestIndex()).append(',')
                .append(event.workloadType()).append(',')
                .append(event.decision()).append(',')
                .append(event.route()).append(',')
                .append(event.providerCalls()).append(',')
                .append(event.latencyMs()).append(',')
                .append(event.distance() == null ? "" : event.distance()).append(',')
                .append(event.threshold()).append(',')
                .append(event.promptTokens()).append(',')
                .append(event.completionTokens()).append(',')
                .append(event.totalTokens()).append(',')
                .append(formatMoney(event.estimatedCostUsd())).append('\n');
        }
        Files.writeString(output.resolve("benchmark-full-events.csv"), csv.toString());
    }

    private static void writeLatency(Path output, List<ModeSummary> summaries) throws IOException {
        StringBuilder csv = new StringBuilder("mode,requests,wall_clock_ms,requests_per_second,p50_ms,p90_ms,p95_ms,p99_ms\n");
        for (ModeSummary summary : summaries) {
            csv.append(summary.mode()).append(',')
                .append(summary.requests()).append(',')
                .append(summary.wallClockMs()).append(',')
                .append(formatDouble(summary.requestsPerSecond())).append(',')
                .append(summary.p50()).append(',')
                .append(summary.p90()).append(',')
                .append(summary.p95()).append(',')
                .append(summary.p99()).append('\n');
        }
        Files.writeString(output.resolve("benchmark-full-latency.csv"), csv.toString());
    }

    private static void writeCost(Path output, List<ModeSummary> summaries) throws IOException {
        StringBuilder csv = new StringBuilder("mode,provider_calls_made,provider_calls_avoided,total_tokens,estimated_cost_usd\n");
        for (ModeSummary summary : summaries) {
            csv.append(summary.mode()).append(',')
                .append(summary.providerCallsMade()).append(',')
                .append(summary.providerCallsAvoided()).append(',')
                .append(summary.totalTokens()).append(',')
                .append(formatMoney(summary.estimatedCostUsd())).append('\n');
        }
        Files.writeString(output.resolve("benchmark-full-cost.csv"), csv.toString());
    }

    private static void writeSummaryJson(Path output, List<ModeSummary> summaries, DatabaseEvidence evidence) throws IOException {
        StringBuilder json = new StringBuilder("{\n  \"status\": \"passed\",\n  \"database_evidence\": ");
        json.append(evidence.toJson()).append(",\n  \"modes\": [\n");
        for (int index = 0; index < summaries.size(); index++) {
            json.append(summaries.get(index).toJson());
            json.append(index + 1 == summaries.size() ? "\n" : ",\n");
        }
        json.append("  ]\n}\n");
        Files.writeString(output.resolve("benchmark-full-summary.json"), json.toString());
    }

    private static void writeSummaryMarkdown(
            Path output,
            BenchmarkConfig config,
            List<ModeSummary> summaries,
            DatabaseEvidence evidence) throws IOException {
        StringBuilder md = new StringBuilder();
        md.append("# Benchmark Full Summary\n\n");
        md.append("This report measures a deterministic semantic-cache workload across cache modes. It is suitable for comparing this local workload, not for universal production claims.\n\n");
        md.append("## Configuration\n\n");
        md.append("- Requests per mode: `").append(config.requests()).append("`\n");
        md.append("- Concurrency: `").append(config.concurrency()).append("`\n");
        md.append("- Provider mode: `").append(config.providerMode()).append("`\n");
        md.append("- Provider latency simulation: `").append(config.providerLatencyMs()).append(" ms`\n");
        md.append("- Prewarm visibility pause: `").append(config.prewarmPauseMs()).append(" ms`\n");
        md.append("- Benchmark seed: `").append(config.seed()).append("`\n\n");

        md.append("## Database Evidence\n\n");
        md.append("- Primary route objects visible: `").append(evidence.primaryObjects()).append("`\n");
        md.append("- True Cache route objects visible: `").append(evidence.trueCacheObjects()).append("`\n");
        md.append("- True Cache vector distance check: `").append(evidence.trueCacheVectorDistance()).append("`\n\n");

        md.append("## Mode Summary\n\n");
        md.append("| Mode | Requests | Wall clock ms | Requests/sec | Provider calls | Avoided calls | Total tokens | Estimated cost USD | p50 ms | p95 ms | p99 ms |\n");
        md.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        for (ModeSummary summary : summaries) {
            md.append("| `").append(summary.mode()).append("` | ")
                .append(summary.requests()).append(" | ")
                .append(summary.wallClockMs()).append(" | ")
                .append(formatDouble(summary.requestsPerSecond())).append(" | ")
                .append(summary.providerCallsMade()).append(" | ")
                .append(summary.providerCallsAvoided()).append(" | ")
                .append(summary.totalTokens()).append(" | ")
                .append(formatMoney(summary.estimatedCostUsd())).append(" | ")
                .append(summary.p50()).append(" | ")
                .append(summary.p95()).append(" | ")
                .append(summary.p99()).append(" |\n");
        }

        md.append("\n## Decision Counts by Mode\n\n");
        for (ModeSummary summary : summaries) {
            md.append("- `").append(summary.mode()).append("`: ").append(summary.decisionCounts()).append('\n');
        }

        md.append("\nGenerated files:\n\n");
        md.append("- `reports/generated/benchmark-full-config.json`\n");
        md.append("- `reports/generated/benchmark-full-events.csv`\n");
        md.append("- `reports/generated/benchmark-full-latency.csv`\n");
        md.append("- `reports/generated/benchmark-full-cost.csv`\n");
        md.append("- `reports/generated/benchmark-full-summary.json`\n");
        md.append("- `reports/generated/benchmark-full-summary.md`\n");
        md.append("- `reports/generated/benchmark-full-wall-clock.png`\n");
        md.append("- `reports/generated/benchmark-full-provider-calls.png`\n");
        md.append("- `reports/generated/benchmark-full-cost.png`\n");
        Files.writeString(output.resolve("benchmark-full-summary.md"), md.toString());
    }

    private static void printSummary(List<ModeSummary> summaries) {
        System.out.println();
        System.out.println("== Benchmark full summary ==");
        for (ModeSummary summary : summaries) {
            System.out.println(summary.mode()
                + ": requests=" + summary.requests()
                + " wall_clock_ms=" + summary.wallClockMs()
                + " rps=" + formatDouble(summary.requestsPerSecond())
                + " provider_calls=" + summary.providerCallsMade()
                + " avoided_calls=" + summary.providerCallsAvoided()
                + " estimated_cost_usd=" + formatMoney(summary.estimatedCostUsd())
                + " p50_ms=" + summary.p50()
                + " p95_ms=" + summary.p95());
        }
        System.out.println("Generated reports:");
        System.out.println("- reports/generated/benchmark-full-summary.md");
        System.out.println("- reports/generated/benchmark-full-events.csv");
        System.out.println("- reports/generated/benchmark-full-latency.csv");
        System.out.println("- reports/generated/benchmark-full-cost.csv");
    }

    private static DataSource oracleDataSource(String url, String user, String password) throws SQLException {
        OracleDataSource dataSource = new OracleDataSource();
        dataSource.setURL(url);
        dataSource.setUser(user);
        dataSource.setPassword(password);
        return dataSource;
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private static double[] vector(double a, double b, double c, double d) {
        return new double[] {a, b, c, d};
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String formatDouble(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private static String formatMoney(double value) {
        return String.format(Locale.US, "%.6f", value);
    }

    private interface BenchmarkCallableFactory {
        BenchmarkEvent create(WorkloadItem item) throws Exception;
    }

    private record BenchmarkConfig(
            String user,
            String password,
            String primaryJdbcUrl,
            String trueCacheJdbcUrl,
            int requests,
            int concurrency,
            int providerLatencyMs,
            int prewarmPauseMs,
            long seed,
            String providerMode) {
        static BenchmarkConfig fromEnvironment() {
            return new BenchmarkConfig(
                env("APP_USER", "SEMCACHE_APP"),
                env("APP_PASSWORD", "SemCache_26ai_Demo"),
                env("PRIMARY_JDBC_URL", "jdbc:oracle:thin:@//localhost:1521/FREEPDB1"),
                env("TRUE_CACHE_JDBC_URL", env("PRIMARY_JDBC_URL", "jdbc:oracle:thin:@//localhost:1521/FREEPDB1")),
                positiveInt("BENCHMARK_REQUESTS", 1000),
                positiveInt("BENCHMARK_CONCURRENCY", 1),
                positiveInt("BENCHMARK_PROVIDER_LATENCY_MS", 10),
                nonNegativeInt("BENCHMARK_PREWARM_PAUSE_MS", 1500),
                Long.parseLong(env("BENCHMARK_SEED", "260612")),
                env("BENCHMARK_PROVIDER_MODE", "deterministic"));
        }

        private static int positiveInt(String name, int fallback) {
            int value = Integer.parseInt(env(name, Integer.toString(fallback)));
            if (value <= 0) {
                throw new IllegalArgumentException(name + " must be greater than zero");
            }
            return value;
        }

        private static int nonNegativeInt(String name, int fallback) {
            int value = Integer.parseInt(env(name, Integer.toString(fallback)));
            if (value < 0) {
                throw new IllegalArgumentException(name + " must not be negative");
            }
            return value;
        }

        private static String env(String name, String fallback) {
            String value = System.getenv(name);
            return value == null || value.isBlank() ? fallback : value;
        }
    }

    private record WorkloadItem(int index, String type, String tenant, String prompt, String chatModel, String embeddingModel, String sourceFingerprint, double[] vector) {
        static List<WorkloadItem> generate(int count) {
            List<WorkloadItem> items = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                int bucket = index % 10;
                if (bucket < 2) {
                    items.add(new WorkloadItem(index, "exact-repeat", "store-a", "How long do I have to return unopened shoes?", CHAT_MODEL, EMBEDDING_MODEL, "returns-policy-2026-01", FullBenchmarkRunner.vector(0.10, 0.20, 0.30, 0.40)));
                } else if (bucket < 5) {
                    items.add(new WorkloadItem(index, "safe-paraphrase", "store-a", "Return window for unworn shoes request " + index, CHAT_MODEL, EMBEDDING_MODEL, "returns-policy-2026-01", FullBenchmarkRunner.vector(0.101, 0.199, 0.302, 0.398)));
                } else if (bucket < 7) {
                    items.add(new WorkloadItem(index, "near-miss", "store-a", "Can worn shoes be returned after ninety days request " + index, CHAT_MODEL, EMBEDDING_MODEL, "returns-policy-near-miss-" + index, FullBenchmarkRunner.vector(0.90, 0.10, 0.10, 0.05)));
                } else if (bucket == 7) {
                    items.add(new WorkloadItem(index, "tenant-or-model-mismatch", "store-b-" + index, "How long do I have to return unopened shoes?", CHAT_MODEL, EMBEDDING_MODEL, "returns-policy-2026-01", FullBenchmarkRunner.vector(0.10, 0.20, 0.30, 0.40)));
                } else if (bucket == 8) {
                    items.add(new WorkloadItem(index, "source-or-policy-change", "store-a", "How long do I have to return unopened shoes source " + index, CHAT_MODEL, EMBEDDING_MODEL, "returns-policy-2026-" + index, FullBenchmarkRunner.vector(0.10, 0.20, 0.30, 0.40)));
                } else {
                    items.add(new WorkloadItem(index, "cold-miss", "store-a", "New return policy question " + index, CHAT_MODEL, EMBEDDING_MODEL, "returns-policy-cold-" + index, FullBenchmarkRunner.vector(0.20, 0.30, 0.10, 0.70)));
                }
            }
            return items;
        }

        static List<WorkloadItem> generateColdMissOnly(int count) {
            List<WorkloadItem> items = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                items.add(new WorkloadItem(index, "cold-write-through", "store-a", "Cold write-through benchmark question " + index, CHAT_MODEL, EMBEDDING_MODEL, "returns-policy-write-through-" + index, FullBenchmarkRunner.vector(0.30, 0.40, 0.20, 0.10)));
            }
            return items;
        }

        static WorkloadItem seedExact() {
            return new WorkloadItem(-1, "prewarm-exact", "store-a", "How long do I have to return unopened shoes?", CHAT_MODEL, EMBEDDING_MODEL, "returns-policy-2026-01", FullBenchmarkRunner.vector(0.10, 0.20, 0.30, 0.40));
        }

        static WorkloadItem seedSemantic() {
            return new WorkloadItem(-2, "prewarm-semantic", "store-a", "What is the return window for shoes that are still unworn?", CHAT_MODEL, EMBEDDING_MODEL, "returns-policy-2026-01", FullBenchmarkRunner.vector(0.10, 0.20, 0.30, 0.40));
        }

        SemanticCacheRequest request(String scenarioPrefix, double threshold) {
            return new SemanticCacheRequest(
                scenarioPrefix + "-" + type + "-" + index,
                tenant,
                prompt,
                chatModel,
                embeddingModel,
                4,
                "returns-template-v1",
                sourceFingerprint,
                "policy-v1",
                vector,
                threshold);
        }
    }

    private static final class BenchmarkProvider implements AnswerProvider {
        private final int latencyMs;

        private BenchmarkProvider(int latencyMs) {
            this.latencyMs = latencyMs;
        }

        @Override
        public String generate(SemanticCacheRequest request) {
            if (latencyMs > 0) {
                try {
                    Thread.sleep(latencyMs);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Provider simulation interrupted", interruptedException);
                }
            }
            if (request.prompt().toLowerCase(Locale.ROOT).contains("worn")) {
                return "Worn shoes follow the used-item policy and are not accepted after 90 days.";
            }
            return "Unopened shoes can be returned within 30 days when the tenant return policy is returns-v1.";
        }
    }

    private record TokenUsage(int promptTokens, int completionTokens, int totalTokens, double estimatedCostUsd) {
        static TokenUsage from(String prompt, String answer, boolean providerCalled) {
            if (!providerCalled) {
                return new TokenUsage(0, 0, 0, 0.0);
            }
            int promptTokens = estimateTokens(prompt);
            int completionTokens = estimateTokens(answer);
            int total = promptTokens + completionTokens;
            double cost = (promptTokens / 1_000_000.0 * INPUT_COST_PER_MILLION)
                + (completionTokens / 1_000_000.0 * OUTPUT_COST_PER_MILLION);
            return new TokenUsage(promptTokens, completionTokens, total, cost);
        }

        private static int estimateTokens(String text) {
            return Math.max(1, (int) Math.ceil(text.length() / 4.0));
        }
    }

    private record BenchmarkEvent(
            String mode,
            int requestIndex,
            String workloadType,
            String decision,
            String route,
            int providerCalls,
            long latencyMs,
            Double distance,
            double threshold,
            int promptTokens,
            int completionTokens,
            int totalTokens,
            double estimatedCostUsd) {
        static BenchmarkEvent from(String mode, WorkloadItem item, SemanticCacheResponse response, TokenUsage tokenUsage) {
            return new BenchmarkEvent(
                mode,
                item.index(),
                item.type(),
                response.decision(),
                response.routeName(),
                response.providerCalls(),
                response.latencyMs(),
                response.distance(),
                response.threshold(),
                tokenUsage.promptTokens(),
                tokenUsage.completionTokens(),
                tokenUsage.totalTokens(),
                tokenUsage.estimatedCostUsd());
        }
    }

    private record ModeSummary(
            String mode,
            int requests,
            long wallClockMs,
            double requestsPerSecond,
            int providerCallsMade,
            int providerCallsAvoided,
            int totalTokens,
            double estimatedCostUsd,
            long p50,
            long p90,
            long p95,
            long p99,
            Map<String, Integer> decisionCounts) {
        static ModeSummary from(String mode, List<BenchmarkEvent> events, long wallClockMs, int requests) {
            List<Long> latencies = events.stream().map(BenchmarkEvent::latencyMs).sorted().toList();
            int providerCalls = events.stream().mapToInt(BenchmarkEvent::providerCalls).sum();
            int avoided = (int) events.stream()
                .filter(event -> (event.decision().equals("exact-hit") || event.decision().equals("semantic-hit")) && event.providerCalls() == 0)
                .count();
            int tokens = events.stream().mapToInt(BenchmarkEvent::totalTokens).sum();
            double cost = events.stream().mapToDouble(BenchmarkEvent::estimatedCostUsd).sum();
            Map<String, Integer> decisions = new LinkedHashMap<>();
            for (BenchmarkEvent event : events) {
                decisions.put(event.decision(), decisions.getOrDefault(event.decision(), 0) + 1);
            }
            double rps = wallClockMs == 0 ? requests : requests / (wallClockMs / 1000.0);
            return new ModeSummary(
                mode,
                requests,
                wallClockMs,
                rps,
                providerCalls,
                requests - providerCalls,
                tokens,
                cost,
                percentile(latencies, 50),
                percentile(latencies, 90),
                percentile(latencies, 95),
                percentile(latencies, 99),
                decisions);
        }

        String toJson() {
            return """
                {
                  "mode": "%s",
                  "requests": %d,
                  "wall_clock_ms": %d,
                  "requests_per_second": %s,
                  "provider_calls_made": %d,
                  "provider_calls_avoided": %d,
                  "total_tokens": %d,
                  "estimated_cost_usd": %s,
                  "p50_ms": %d,
                  "p90_ms": %d,
                  "p95_ms": %d,
                  "p99_ms": %d
                }""".formatted(
                mode,
                requests,
                wallClockMs,
                formatDouble(requestsPerSecond),
                providerCallsMade,
                providerCallsAvoided,
                totalTokens,
                formatMoney(estimatedCostUsd),
                p50,
                p90,
                p95,
                p99);
        }

        private static long percentile(List<Long> values, int percentile) {
            if (values.isEmpty()) {
                return 0;
            }
            int index = Math.min(values.size() - 1, Math.round((percentile / 100.0f) * (values.size() - 1)));
            return values.get(index);
        }
    }

    private record DatabaseEvidence(int primaryObjects, int trueCacheObjects, double trueCacheVectorDistance) {
        static DatabaseEvidence collect(DataSource primary, DataSource trueCache) throws SQLException {
            return new DatabaseEvidence(
                countObjects(primary),
                countObjects(trueCache),
                vectorDistance(trueCache));
        }

        String toJson() {
            return """
                {
                  "primary_objects": %d,
                  "true_cache_objects": %d,
                  "true_cache_vector_distance": %s
                }""".formatted(primaryObjects, trueCacheObjects, Double.toString(trueCacheVectorDistance));
        }

        private static int countObjects(DataSource dataSource) throws SQLException {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement("select count(*) from user_tables where table_name in ('SEM_CACHE_ENTRY', 'SEM_CACHE_EVENT')");
                 ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }

        private static double vectorDistance(DataSource dataSource) throws SQLException {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement("select vector_distance(to_vector('[0.1,0.2,0.3,0.4]'), to_vector('[0.1,0.2,0.3,0.4]'), cosine) from dual");
                 ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getDouble(1);
            }
        }
    }
}
