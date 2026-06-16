package com.example.semcache.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.example.semcache.oracle.AnswerProvider;
import com.example.semcache.oracle.SemanticCacheRequest;
import com.example.semcache.oracle.SemanticCacheResponse;
import com.example.semcache.oracle.SemanticCacheService;
import oracle.jdbc.pool.OracleDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
    private static final ObjectMapper JSON = new ObjectMapper();

    private FullBenchmarkRunner() {
    }

    static void run() throws Exception {
        BenchmarkConfig config = BenchmarkConfig.fromEnvironment();

        DataSource primary = oracleDataSource(config.primaryJdbcUrl(), config.user(), config.password());
        DataSource trueCache = oracleDataSource(config.trueCacheJdbcUrl(), config.user(), config.password());
        List<WorkloadItem> workload = WorkloadItem.generate(config.requests(), config.workloadFamilies());
        BenchmarkAnswerProvider provider = BenchmarkAnswerProvider.create(config);
        List<BenchmarkEvent> allEvents = new ArrayList<>();
        List<ModeSummary> summaries = new ArrayList<>();

        System.out.println();
        System.out.println("== Full semantic cache benchmark ==");
        System.out.println("Requests per measured mode: " + config.requests());
        System.out.println("Workload families: " + config.workloadFamilies());
        System.out.println("Concurrency: " + config.concurrency());
        System.out.println("Provider mode: " + provider.displayName());
        if (config.providerMode().equals("deterministic")) {
            System.out.println("Provider latency simulation: " + config.providerLatencyMs() + " ms per provider call");
            System.out.println("Use BENCHMARK_PROVIDER_MODE=openai for live provider measurements.");
        }
        System.out.println("Prewarm visibility pause: " + config.prewarmPauseMs() + " ms");
        System.out.println("Prewarm cache entries per measured cache mode: " + (config.workloadFamilies() + 1));
        System.out.println("Output: reports/generated/benchmark-full-*");

        summaries.add(runNoCacheMode(config, provider, workload, allEvents));
        summaries.add(runCacheMode(config, "exact-cache", primary, trueCache, "true-cache", provider, workload, allEvents, true, -1.0));
        summaries.add(runCacheMode(config, "semantic-cache-primary", primary, primary, "primary", provider, workload, allEvents, true, THRESHOLD));
        summaries.add(runCacheMode(config, "semantic-cache-true-cache", primary, trueCache, "true-cache", provider, workload, allEvents, true, THRESHOLD));
        summaries.add(runCacheMode(config, "semantic-cache-write-through", primary, trueCache, "true-cache", provider, WorkloadItem.generateColdMissOnly(config.requests(), config.workloadFamilies()), allEvents, false, THRESHOLD));

        DatabaseEvidence evidence = DatabaseEvidence.collect(primary, trueCache);
        writeReports(config, summaries, allEvents, evidence);
        printSummary(summaries);
    }

    private static ModeSummary runNoCacheMode(
            BenchmarkConfig config,
            BenchmarkAnswerProvider provider,
            List<WorkloadItem> workload,
            List<BenchmarkEvent> allEvents) throws Exception {
        long started = System.nanoTime();
        List<BenchmarkEvent> events = runConcurrent(config, "no-cache", workload, item -> {
            long requestStarted = System.nanoTime();
            SemanticCacheRequest request = item.request("no-cache", THRESHOLD);
            String answer = provider.generate(request);
            TokenUsage tokenUsage = TokenUsage.from(item.prompt(), answer, true, provider.metrics(request.scenarioName()));
            return new BenchmarkEvent(
                "no-cache",
                item.index(),
                item.type(),
                "provider-call",
                "none",
                1,
                elapsedMillis(requestStarted),
                tokenUsage.providerLatencyMs(),
                null,
                THRESHOLD,
                tokenUsage.promptTokens(),
                tokenUsage.completionTokens(),
                tokenUsage.totalTokens(),
                tokenUsage.estimatedCostUsd(),
                tokenUsage.source());
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
            BenchmarkAnswerProvider provider,
            List<WorkloadItem> workload,
            List<BenchmarkEvent> allEvents,
            boolean prewarm,
            double threshold) throws Exception {
        if (config.concurrency() == 1) {
            try (Connection primaryConnection = primary.getConnection()) {
                SemanticCacheService setupCache = new SemanticCacheService(primaryConnection, primaryConnection, "primary", provider);
                setupCache.reset();
                if (prewarm) {
                    prewarm(setupCache, threshold, config.workloadFamilies());
                    if (config.prewarmPauseMs() > 0) {
                        Thread.sleep(config.prewarmPauseMs());
                    }
                }
                try (Connection readConnection = read.getConnection()) {
                    SemanticCacheService cache = new SemanticCacheService(primaryConnection, readConnection, routeName, provider);
                    return runPreparedCacheMode(config, mode, cache, provider, workload, allEvents, threshold);
                }
            }
        }

        SemanticCacheService cache = new SemanticCacheService(primary, read, routeName, provider);
        return runCacheModeWithService(config, mode, cache, provider, workload, allEvents, prewarm, threshold);
    }

    private static ModeSummary runCacheModeWithService(
            BenchmarkConfig config,
            String mode,
            SemanticCacheService cache,
            BenchmarkAnswerProvider provider,
            List<WorkloadItem> workload,
            List<BenchmarkEvent> allEvents,
            boolean prewarm,
            double threshold) throws Exception {
        cache.reset();
        if (prewarm) {
            prewarm(cache, threshold, config.workloadFamilies());
            if (config.prewarmPauseMs() > 0) {
                Thread.sleep(config.prewarmPauseMs());
            }
        }

        long started = System.nanoTime();
        List<BenchmarkEvent> events = runConcurrent(config, mode, workload, item -> {
            SemanticCacheResponse response = cache.answer(item.request(mode, threshold));
            TokenUsage tokenUsage = TokenUsage.from(item.prompt(), response.answer(), response.providerCalls() > 0, provider.metrics(response.scenarioName()));
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
            BenchmarkAnswerProvider provider,
            List<WorkloadItem> workload,
            List<BenchmarkEvent> allEvents,
            double threshold) throws Exception {
        long started = System.nanoTime();
        List<BenchmarkEvent> events = runConcurrent(config, mode, workload, item -> {
            SemanticCacheResponse response = cache.answer(item.request(mode, threshold));
            TokenUsage tokenUsage = TokenUsage.from(item.prompt(), response.answer(), response.providerCalls() > 0, provider.metrics(response.scenarioName()));
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

    private static void prewarm(SemanticCacheService cache, double threshold, int workloadFamilies) throws SQLException {
        for (WorkloadFamily family : WorkloadFamily.generate(workloadFamilies)) {
            cache.answer(family.seedItem().request("prewarm-family", threshold));
        }
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
              "workload_families": %d,
              "prewarm_entries_per_cache_mode": %d,
              "concurrency": %d,
              "provider_mode": "%s",
              "openai_model": "%s",
              "provider_latency_ms": %d,
              "prewarm_pause_ms": %d,
              "benchmark_seed": %d,
              "input_cost_per_million": %s,
              "output_cost_per_million": %s,
              "primary_jdbc_url": "%s",
              "true_cache_jdbc_url": "%s",
              "java_version": "%s",
              "os": "%s %s"
            }
            """.formatted(
            Instant.now(),
            config.requests(),
            config.workloadFamilies(),
            config.workloadFamilies() + 1,
            config.concurrency(),
            config.providerMode(),
            escape(config.openAiModel()),
            config.providerLatencyMs(),
            config.prewarmPauseMs(),
            config.seed(),
            formatDouble(config.inputCostPerMillion()),
            formatDouble(config.outputCostPerMillion()),
            escape(config.primaryJdbcUrl()),
            escape(config.trueCacheJdbcUrl()),
            escape(System.getProperty("java.version")),
            escape(System.getProperty("os.name")),
            escape(System.getProperty("os.version")));
        Files.writeString(output.resolve("benchmark-full-config.json"), json);
    }

    private static void writeEvents(Path output, List<BenchmarkEvent> events) throws IOException {
        StringBuilder csv = new StringBuilder("mode,request_index,workload_type,decision,route,provider_calls,latency_ms,provider_latency_ms,non_provider_latency_ms,distance,threshold,prompt_tokens,completion_tokens,total_tokens,estimated_cost_usd,token_source\n");
        for (BenchmarkEvent event : events) {
            long nonProviderLatencyMs = Math.max(0, event.latencyMs() - event.providerLatencyMs());
            csv.append(event.mode()).append(',')
                .append(event.requestIndex()).append(',')
                .append(event.workloadType()).append(',')
                .append(event.decision()).append(',')
                .append(event.route()).append(',')
                .append(event.providerCalls()).append(',')
                .append(event.latencyMs()).append(',')
                .append(event.providerLatencyMs()).append(',')
                .append(nonProviderLatencyMs).append(',')
                .append(event.distance() == null ? "" : event.distance()).append(',')
                .append(event.threshold()).append(',')
                .append(event.promptTokens()).append(',')
                .append(event.completionTokens()).append(',')
                .append(event.totalTokens()).append(',')
                .append(formatMoney(event.estimatedCostUsd())).append(',')
                .append(event.tokenSource()).append('\n');
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
        md.append("This report measures a semantic-cache workload across cache modes. It is suitable for comparing this local workload, not for universal production claims.\n\n");
        md.append("## Configuration\n\n");
        md.append("- Requests per mode: `").append(config.requests()).append("`\n");
        md.append("- Workload families: `").append(config.workloadFamilies()).append("`\n");
        md.append("- Prewarm entries per measured cache mode: `").append(config.workloadFamilies() + 1).append("`\n");
        md.append("- Concurrency: `").append(config.concurrency()).append("`\n");
        md.append("- Provider mode: `").append(config.providerMode()).append("`\n");
        if (config.providerMode().equals("deterministic")) {
            md.append("- Provider latency simulation: `").append(config.providerLatencyMs()).append(" ms`\n");
        } else {
            md.append("- OpenAI model: `").append(config.openAiModel()).append("`\n");
            md.append("- Token source: provider-reported usage from OpenAI Responses API\n");
        }
        md.append("- Input cost per million tokens: `").append(formatDouble(config.inputCostPerMillion())).append("`\n");
        md.append("- Output cost per million tokens: `").append(formatDouble(config.outputCostPerMillion())).append("`\n");
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
        md.append("- `reports/generated/benchmark-full-latency-components.png`\n");
        md.append("- `reports/generated/benchmark-full-cache-hit-lookup-latency.png`\n");
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
            int workloadFamilies,
            int concurrency,
            int providerLatencyMs,
            int prewarmPauseMs,
            long seed,
            String providerMode,
            String openAiApiKey,
            String openAiModel,
            String openAiBaseUrl,
            int openAiMaxOutputTokens,
            double inputCostPerMillion,
            double outputCostPerMillion) {
        static BenchmarkConfig fromEnvironment() {
            String providerMode = env("BENCHMARK_PROVIDER_MODE", "deterministic");
            return new BenchmarkConfig(
                env("APP_USER", "SEMCACHE_APP"),
                env("APP_PASSWORD", "SemCache_26ai_Demo"),
                env("PRIMARY_JDBC_URL", "jdbc:oracle:thin:@//localhost:1521/FREEPDB1"),
                env("TRUE_CACHE_JDBC_URL", env("PRIMARY_JDBC_URL", "jdbc:oracle:thin:@//localhost:1521/FREEPDB1")),
                positiveInt("BENCHMARK_REQUESTS", 1000),
                positiveInt("BENCHMARK_WORKLOAD_FAMILIES", 50),
                positiveInt("BENCHMARK_CONCURRENCY", 1),
                positiveInt("BENCHMARK_PROVIDER_LATENCY_MS", 10),
                nonNegativeInt("BENCHMARK_PREWARM_PAUSE_MS", 1500),
                Long.parseLong(env("BENCHMARK_SEED", "260612")),
                providerMode,
                env("OPENAI_API_KEY", ""),
                env("OPENAI_CHAT_MODEL", CHAT_MODEL),
                env("OPENAI_BASE_URL", "https://api.openai.com/v1/responses"),
                positiveInt("OPENAI_MAX_OUTPUT_TOKENS", 80),
                doubleValue("OPENAI_INPUT_COST_PER_MILLION", INPUT_COST_PER_MILLION),
                doubleValue("OPENAI_OUTPUT_COST_PER_MILLION", OUTPUT_COST_PER_MILLION));
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

        private static double doubleValue(String name, double fallback) {
            return Double.parseDouble(env(name, Double.toString(fallback)));
        }
    }

    private record WorkloadItem(int index, String type, String tenant, String prompt, String chatModel, String embeddingModel, String sourceFingerprint, double[] vector) {
        static List<WorkloadItem> generate(int count, int familyCount) {
            List<WorkloadFamily> families = WorkloadFamily.generate(familyCount);
            List<WorkloadItem> items = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                WorkloadFamily family = families.get(index % families.size());
                int bucket = index % 10;
                if (bucket < 2) {
                    items.add(family.item(index, "exact-repeat", family.exactPrompt(), family.baseVector(), family.sourceFingerprint()));
                } else if (bucket < 5) {
                    items.add(family.item(index, "safe-paraphrase", family.paraphrasePrompt(index), family.nearVector(), family.sourceFingerprint()));
                } else if (bucket < 7) {
                    items.add(family.item(index, "near-miss", family.nearMissPrompt(index), family.farVector(), family.sourceFingerprint()));
                } else if (bucket == 7) {
                    items.add(new WorkloadItem(index, "tenant-or-model-mismatch", "store-b-" + family.id(), family.exactPrompt(), CHAT_MODEL, EMBEDDING_MODEL, family.sourceFingerprint(), family.baseVector()));
                } else if (bucket == 8) {
                    items.add(family.item(index, "source-or-policy-change", family.sourceChangedPrompt(index), family.baseVector(), family.sourceFingerprint() + "-revision-" + index));
                } else {
                    items.add(family.item(index, "cold-miss", family.coldPrompt(index), family.coldVector(), family.sourceFingerprint() + "-cold-" + index));
                }
            }
            return items;
        }

        static List<WorkloadItem> generateColdMissOnly(int count, int familyCount) {
            List<WorkloadFamily> families = WorkloadFamily.generate(familyCount);
            List<WorkloadItem> items = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                WorkloadFamily family = families.get(index % families.size());
                items.add(family.item(index, "cold-write-through", "Cold write-through benchmark question " + index + " for " + family.product(), family.coldVector(), family.sourceFingerprint() + "-write-through-" + index));
            }
            return items;
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

    private record WorkloadFamily(int id, String tenant, String product, String sourceFingerprint, double[] baseVector) {
        static List<WorkloadFamily> generate(int count) {
            List<WorkloadFamily> families = new ArrayList<>();
            String[] products = {
                "running shoes", "hiking boots", "dress shoes", "sandals", "winter coats",
                "rain jackets", "backpacks", "laptop sleeves", "headphones", "fitness watches"
            };
            for (int id = 0; id < count; id++) {
                double offset = (id % 25) / 1000.0;
                families.add(new WorkloadFamily(
                    id,
                    "store-a",
                    products[id % products.length] + " family " + id,
                    "returns-policy-2026-family-" + id,
                    FullBenchmarkRunner.vector(0.10 + offset, 0.20 + offset, 0.30 - offset, 0.40 - offset)));
            }
            return families;
        }

        WorkloadItem seedItem() {
            return item(-(id + 1), "prewarm-family", exactPrompt(), baseVector(), sourceFingerprint);
        }

        WorkloadItem item(int index, String type, String prompt, double[] vector, String source) {
            return new WorkloadItem(index, type, tenant, prompt, CHAT_MODEL, EMBEDDING_MODEL, source, vector);
        }

        String exactPrompt() {
            return "How long do I have to return unopened " + product + "?";
        }

        String paraphrasePrompt(int index) {
            return "Return window for unused " + product + " request " + index;
        }

        String nearMissPrompt(int index) {
            return "Can visibly worn " + product + " be returned after ninety days request " + index;
        }

        String sourceChangedPrompt(int index) {
            return "How long do I have to return unopened " + product + " after source update " + index + "?";
        }

        String coldPrompt(int index) {
            return "New return policy question " + index + " for " + product;
        }

        double[] nearVector() {
            return FullBenchmarkRunner.vector(baseVector[0] + 0.001, baseVector[1] - 0.001, baseVector[2] + 0.002, baseVector[3] - 0.002);
        }

        double[] farVector() {
            return FullBenchmarkRunner.vector(0.90, 0.10 + (id % 10) / 100.0, 0.10, 0.05);
        }

        double[] coldVector() {
            return FullBenchmarkRunner.vector(0.20, 0.30, 0.10 + (id % 10) / 100.0, 0.70);
        }
    }

    private interface BenchmarkAnswerProvider extends AnswerProvider {
        static BenchmarkAnswerProvider create(BenchmarkConfig config) {
            return switch (config.providerMode()) {
                case "deterministic" -> new DeterministicBenchmarkProvider(config);
                case "openai" -> new OpenAiResponsesBenchmarkProvider(config);
                default -> throw new IllegalArgumentException("Unsupported BENCHMARK_PROVIDER_MODE=" + config.providerMode() + ". Use deterministic or openai.");
            };
        }

        ProviderCallMetrics metrics(String scenarioName);

        String displayName();
    }

    private static final class DeterministicBenchmarkProvider implements BenchmarkAnswerProvider {
        private final int latencyMs;
        private final BenchmarkConfig config;
        private final Map<String, ProviderCallMetrics> metrics = new ConcurrentHashMap<>();

        private DeterministicBenchmarkProvider(BenchmarkConfig config) {
            this.latencyMs = config.providerLatencyMs();
            this.config = config;
        }

        @Override
        public String generate(SemanticCacheRequest request) {
            long started = System.nanoTime();
            if (latencyMs > 0) {
                try {
                    Thread.sleep(latencyMs);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Provider simulation interrupted", interruptedException);
                }
            }
            String answer = answerFor(request);
            TokenUsage tokenUsage = TokenUsage.estimate(request.prompt(), answer, config);
            metrics.put(request.scenarioName(), new ProviderCallMetrics(
                tokenUsage.promptTokens(),
                tokenUsage.completionTokens(),
                tokenUsage.totalTokens(),
                tokenUsage.estimatedCostUsd(),
                elapsedMillis(started),
                "deterministic-estimate"));
            return answer;
        }

        @Override
        public ProviderCallMetrics metrics(String scenarioName) {
            return metrics.get(scenarioName);
        }

        @Override
        public String displayName() {
            return "deterministic mock";
        }

        private String answerFor(SemanticCacheRequest request) {
            if (request.prompt().toLowerCase(Locale.ROOT).contains("worn")) {
                return "Worn items follow the used-item policy and are not accepted after 90 days.";
            }
            return "Unopened items can be returned within 30 days when the tenant return policy is returns-v1.";
        }
    }

    private static final class OpenAiResponsesBenchmarkProvider implements BenchmarkAnswerProvider {
        private final BenchmarkConfig config;
        private final HttpClient httpClient;
        private final Map<String, ProviderCallMetrics> metrics = new ConcurrentHashMap<>();

        private OpenAiResponsesBenchmarkProvider(BenchmarkConfig config) {
            if (config.openAiApiKey().isBlank()) {
                throw new IllegalArgumentException("OPENAI_API_KEY is required when BENCHMARK_PROVIDER_MODE=openai.");
            }
            this.config = config;
            this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        }

        @Override
        public String generate(SemanticCacheRequest request) {
            try {
                long started = System.nanoTime();
                ObjectNode body = JSON.createObjectNode();
                body.put("model", config.openAiModel());
                body.put("instructions", "You are a concise retail returns policy assistant. Answer in one sentence using this policy: unopened items can be returned within 30 days; visibly worn items are handled under the used-item policy and are not accepted after 90 days.");
                body.put("input", request.prompt());
                body.put("max_output_tokens", config.openAiMaxOutputTokens());

                HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(config.openAiBaseUrl()))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + config.openAiApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                    .build();

                HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException("OpenAI Responses API returned HTTP " + response.statusCode() + ": " + response.body());
                }

                JsonNode root = JSON.readTree(response.body());
                String answer = outputText(root);
                JsonNode usage = root.path("usage");
                int inputTokens = usage.path("input_tokens").asInt(0);
                int outputTokens = usage.path("output_tokens").asInt(0);
                int totalTokens = usage.path("total_tokens").asInt(inputTokens + outputTokens);
                double cost = (inputTokens / 1_000_000.0 * config.inputCostPerMillion())
                    + (outputTokens / 1_000_000.0 * config.outputCostPerMillion());
                metrics.put(request.scenarioName(), new ProviderCallMetrics(
                    inputTokens,
                    outputTokens,
                    totalTokens,
                    cost,
                    elapsedMillis(started),
                    "openai-reported"));
                return answer.isBlank() ? "(OpenAI response contained no output_text.)" : answer;
            } catch (IOException ioException) {
                throw new IllegalStateException("OpenAI benchmark request failed", ioException);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("OpenAI benchmark request interrupted", interruptedException);
            }
        }

        @Override
        public ProviderCallMetrics metrics(String scenarioName) {
            return metrics.get(scenarioName);
        }

        @Override
        public String displayName() {
            return "openai:" + config.openAiModel();
        }

        private static String outputText(JsonNode root) {
            String direct = root.path("output_text").asText("");
            if (!direct.isBlank()) {
                return direct;
            }
            JsonNode output = root.path("output");
            if (output.isArray()) {
                for (JsonNode item : output) {
                    JsonNode content = item.path("content");
                    if (content.isArray()) {
                        for (JsonNode contentItem : content) {
                            if (contentItem.path("type").asText("").equals("output_text")) {
                                String text = contentItem.path("text").asText("");
                                if (!text.isBlank()) {
                                    return text;
                                }
                            }
                        }
                    }
                }
            }
            return "";
        }
    }

    private record ProviderCallMetrics(int promptTokens, int completionTokens, int totalTokens, double estimatedCostUsd, long providerLatencyMs, String source) {
    }

    private record TokenUsage(int promptTokens, int completionTokens, int totalTokens, double estimatedCostUsd, long providerLatencyMs, String source) {
        static TokenUsage from(String prompt, String answer, boolean providerCalled, ProviderCallMetrics metrics) {
            if (!providerCalled) {
                return new TokenUsage(0, 0, 0, 0.0, 0, "cache-hit");
            }
            if (metrics != null) {
                return new TokenUsage(metrics.promptTokens(), metrics.completionTokens(), metrics.totalTokens(), metrics.estimatedCostUsd(), metrics.providerLatencyMs(), metrics.source());
            }
            return estimate(prompt, answer, null);
        }

        static TokenUsage estimate(String prompt, String answer, BenchmarkConfig config) {
            int promptTokens = estimateTokens(prompt);
            int completionTokens = estimateTokens(answer);
            int total = promptTokens + completionTokens;
            double inputCost = config == null ? INPUT_COST_PER_MILLION : config.inputCostPerMillion();
            double outputCost = config == null ? OUTPUT_COST_PER_MILLION : config.outputCostPerMillion();
            double cost = (promptTokens / 1_000_000.0 * inputCost)
                + (completionTokens / 1_000_000.0 * outputCost);
            return new TokenUsage(promptTokens, completionTokens, total, cost, 0, "deterministic-estimate");
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
            long providerLatencyMs,
            Double distance,
            double threshold,
            int promptTokens,
            int completionTokens,
            int totalTokens,
            double estimatedCostUsd,
            String tokenSource) {
        static BenchmarkEvent from(String mode, WorkloadItem item, SemanticCacheResponse response, TokenUsage tokenUsage) {
            return new BenchmarkEvent(
                mode,
                item.index(),
                item.type(),
                response.decision(),
                response.routeName(),
                response.providerCalls(),
                response.latencyMs(),
                tokenUsage.providerLatencyMs(),
                response.distance(),
                response.threshold(),
                tokenUsage.promptTokens(),
                tokenUsage.completionTokens(),
                tokenUsage.totalTokens(),
                tokenUsage.estimatedCostUsd(),
                tokenUsage.source());
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
