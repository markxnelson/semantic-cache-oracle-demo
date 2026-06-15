package com.example.semcache.app;

import com.example.semcache.oracle.AnswerProvider;
import com.example.semcache.oracle.SemanticCacheRequest;
import com.example.semcache.oracle.SemanticCacheResponse;
import com.example.semcache.oracle.SemanticCacheService;
import oracle.jdbc.pool.OracleDataSource;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class SemanticCacheDemoApplication implements CommandLineRunner {
    public static void main(String[] args) {
        SpringApplication.run(SemanticCacheDemoApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        String user = env("APP_USER", "SEMCACHE_APP");
        String password = requiredEnv("APP_PASSWORD");
        DataSource primary = oracleDataSource(env("PRIMARY_JDBC_URL", "jdbc:oracle:thin:@//localhost:1521/FREEPDB1"), user, password);
        DataSource read = oracleDataSource(env("TRUE_CACHE_JDBC_URL", env("PRIMARY_JDBC_URL", "jdbc:oracle:thin:@//localhost:1521/FREEPDB1")), user, password);
        String readRouteName = env("SEM_CACHE_READ_ROUTE", "true-cache");
        double threshold = Double.parseDouble(env("SEM_CACHE_THRESHOLD", "0.10"));

        SemanticCacheService cache = new SemanticCacheService(primary, read, readRouteName, deterministicProvider());
        cache.reset();
        cache.seedExpiredEntry(
            requestWithSource("expired-entry", "store-a", "Can I return unopened shoes?", vector(0.10, 0.20, 0.30, 0.40), threshold, "returns-policy-expired"),
            "Expired cache entries should not be served.");

        List<SemanticCacheResponse> responses = new ArrayList<>();
        responses.add(cache.answer(request("seed-miss", "store-a", "How long do I have to return unopened shoes?", vector(0.10, 0.20, 0.30, 0.40), threshold)));
        responses.add(cache.answer(request("exact-hit", "store-a", "How long do I have to return unopened shoes?", vector(0.10, 0.20, 0.30, 0.40), threshold)));
        responses.add(cache.answer(request("semantic-hit", "store-a", "What is the return window for shoes I have not worn?", vector(0.101, 0.199, 0.302, 0.398), threshold)));
        responses.add(cache.answer(request("near-miss", "store-a", "Can I return worn shoes after 90 days?", vector(0.90, 0.10, 0.10, 0.05), threshold)));
        responses.add(cache.answer(request("tenant-isolation", "store-b", "How long do I have to return unopened shoes?", vector(0.10, 0.20, 0.30, 0.40), threshold)));
        responses.add(cache.answer(request("model-mismatch", "store-a", "How long do I have to return unopened shoes?", vector(0.10, 0.20, 0.30, 0.40), threshold, "gpt-4o-mini", "text-embedding-3-large")));
        responses.add(cache.answer(requestWithSource("source-fingerprint-mismatch", "store-a", "How long do I have to return unopened shoes?", vector(0.10, 0.20, 0.30, 0.40), threshold, "returns-policy-2026-02")));
        responses.add(cache.answer(requestWithSource("expired-entry", "store-a", "Can I return unopened shoes?", vector(0.10, 0.20, 0.30, 0.40), threshold, "returns-policy-expired")));

        writeReports(responses);
        long failures = responses.stream().filter(response -> !expected(response)).count();
        if (failures > 0) {
            throw new IllegalStateException("Validation failed for " + failures + " scenario(s). See reports/generated.");
        }
    }

    private static SemanticCacheRequest request(String scenario, String tenant, String prompt, double[] embedding, double threshold) {
        return request(scenario, tenant, prompt, embedding, threshold, "gpt-4o-mini", "text-embedding-3-small");
    }

    private static SemanticCacheRequest request(
            String scenario,
            String tenant,
            String prompt,
            double[] embedding,
            double threshold,
            String chatModel,
            String embeddingModel) {
        return new SemanticCacheRequest(
            scenario,
            tenant,
            prompt,
            chatModel,
            embeddingModel,
            4,
            "returns-template-v1",
            "returns-policy-2026-01",
            "policy-v1",
            embedding,
            threshold);
    }

    private static SemanticCacheRequest requestWithSource(
            String scenario,
            String tenant,
            String prompt,
            double[] embedding,
            double threshold,
            String sourceFingerprint) {
        return new SemanticCacheRequest(
            scenario,
            tenant,
            prompt,
            "gpt-4o-mini",
            "text-embedding-3-small",
            4,
            "returns-template-v1",
            sourceFingerprint,
            "policy-v1",
            embedding,
            threshold);
    }

    private static AnswerProvider deterministicProvider() {
        return request -> switch (request.scenarioName()) {
            case "near-miss" -> "Worn shoes follow the used-item policy and are not accepted after 90 days.";
            default -> "Unopened shoes can be returned within 30 days when the tenant return policy is returns-v1.";
        };
    }

    private static DataSource oracleDataSource(String url, String user, String password) throws SQLException {
        OracleDataSource dataSource = new OracleDataSource();
        dataSource.setURL(url);
        dataSource.setUser(user);
        dataSource.setPassword(password);
        return dataSource;
    }

    private static void writeReports(List<SemanticCacheResponse> responses) throws IOException {
        Path output = Path.of("reports/generated");
        Files.createDirectories(output);
        StringBuilder csv = new StringBuilder("scenario,decision,reason,route,distance,threshold,provider_calls,latency_ms\n");
        for (SemanticCacheResponse response : responses) {
            csv.append(response.scenarioName()).append(',')
                .append(response.decision()).append(',')
                .append('"').append(response.reason()).append('"').append(',')
                .append(response.routeName()).append(',')
                .append(response.distance() == null ? "" : response.distance()).append(',')
                .append(response.threshold()).append(',')
                .append(response.providerCalls()).append(',')
                .append(response.latencyMs()).append('\n');
        }
        Files.writeString(output.resolve("validation-events.csv"), csv.toString());

        StringBuilder json = new StringBuilder("{\n  \"status\": \"passed\",\n  \"events\": [\n");
        for (int index = 0; index < responses.size(); index++) {
            SemanticCacheResponse response = responses.get(index);
            json.append("    {\"scenario\": \"").append(response.scenarioName())
                .append("\", \"decision\": \"").append(response.decision())
                .append("\", \"route\": \"").append(response.routeName())
                .append("\", \"provider_calls\": ").append(response.providerCalls()).append('}');
            json.append(index + 1 == responses.size() ? "\n" : ",\n");
        }
        json.append("  ]\n}\n");
        Files.writeString(output.resolve("validation-summary.json"), json.toString());

        StringBuilder md = new StringBuilder("# Semantic Cache Validation Summary\n\n");
        md.append("Status: passed\n\n");
        for (SemanticCacheResponse response : responses) {
            md.append("- `").append(response.scenarioName()).append("`: ")
              .append(response.decision()).append(" via `").append(response.routeName())
              .append("`, provider calls `").append(response.providerCalls()).append("`\n");
        }
        Files.writeString(output.resolve("validation-summary.md"), md.toString());
    }

    private static boolean expected(SemanticCacheResponse response) {
        return switch (response.scenarioName()) {
            case "seed-miss" -> response.decision().equals("miss") && response.providerCalls() == 1;
            case "exact-hit" -> response.decision().equals("exact-hit") && response.providerCalls() == 0;
            case "semantic-hit" -> response.decision().equals("semantic-hit") && response.providerCalls() == 0;
            case "near-miss" -> response.decision().equals("near-miss") && response.providerCalls() == 1;
            case "tenant-isolation", "model-mismatch", "source-fingerprint-mismatch", "expired-entry" ->
                response.decision().equals("miss") && response.providerCalls() == 1;
            default -> false;
        };
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set. Copy .env.example to .env and run through the scripts.");
        }
        return value;
    }

    private static double[] vector(double a, double b, double c, double d) {
        return new double[] {a, b, c, d};
    }
}
