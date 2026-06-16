package com.example.semcache.oracle;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import javax.sql.DataSource;

public class SemanticCacheService {
    private final DataSource primaryDataSource;
    private final DataSource readDataSource;
    private final Connection primaryConnection;
    private final Connection readConnection;
    private final String readRouteName;
    private final AnswerProvider provider;

    public SemanticCacheService(
            DataSource primaryDataSource,
            DataSource readDataSource,
            String readRouteName,
            AnswerProvider provider) {
        this.primaryDataSource = primaryDataSource;
        this.readDataSource = readDataSource;
        this.primaryConnection = null;
        this.readConnection = null;
        this.readRouteName = readRouteName;
        this.provider = provider;
    }

    public SemanticCacheService(
            Connection primaryConnection,
            Connection readConnection,
            String readRouteName,
            AnswerProvider provider) {
        this.primaryDataSource = null;
        this.readDataSource = null;
        this.primaryConnection = primaryConnection;
        this.readConnection = readConnection;
        this.readRouteName = readRouteName;
        this.provider = provider;
    }

    public SemanticCacheResponse answer(SemanticCacheRequest request) throws SQLException {
        long started = System.nanoTime();
        String promptHash = promptHash(request.prompt());

        Connection activeReadConnection = readConnection();
        try {
            Candidate exact = findExact(activeReadConnection, request, promptHash);
            if (exact != null) {
                SemanticCacheResponse response = new SemanticCacheResponse(
                    request.scenarioName(),
                    "exact-hit",
                    "prompt hash and policy scope matched",
                    exact.answer(),
                    readRouteName,
                    null,
                    request.threshold(),
                    0,
                    elapsedMillis(started));
                recordEvent(response);
                return response;
            }

            Candidate semantic = findSemantic(activeReadConnection, request);
            if (semantic != null && semantic.distance() <= request.threshold()) {
                SemanticCacheResponse response = new SemanticCacheResponse(
                    request.scenarioName(),
                    "semantic-hit",
                    "vector candidate passed scope and threshold policy",
                    semantic.answer(),
                    readRouteName,
                    semantic.distance(),
                    request.threshold(),
                    0,
                    elapsedMillis(started));
                recordEvent(response);
                return response;
            }

            if (semantic != null) {
                String answer = provider.generate(request);
                insertEntry(request, promptHash, answer);
                SemanticCacheResponse response = new SemanticCacheResponse(
                    request.scenarioName(),
                    "near-miss",
                    "closest vector candidate failed threshold policy",
                    answer,
                    readRouteName,
                    semantic.distance(),
                    request.threshold(),
                    1,
                    elapsedMillis(started));
                recordEvent(response);
                return response;
            }
        } finally {
            closeIfOwned(activeReadConnection, readConnection);
        }

        String answer = provider.generate(request);
        insertEntry(request, promptHash, answer);
        SemanticCacheResponse response = new SemanticCacheResponse(
            request.scenarioName(),
            "miss",
            "no scoped exact or semantic candidate was reusable",
            answer,
            "primary",
            null,
            request.threshold(),
            1,
            elapsedMillis(started));
        recordEvent(response);
        return response;
    }

    public void reset() throws SQLException {
        Connection connection = primaryConnection();
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement deleteEvents = connection.prepareStatement("DELETE FROM sem_cache_event WHERE 1 = 1");
                 PreparedStatement deleteEntries = connection.prepareStatement("DELETE FROM sem_cache_entry WHERE 1 = 1")) {
                deleteEvents.executeUpdate();
                deleteEntries.executeUpdate();
            }
            connection.commit();
        } finally {
            closeIfOwned(connection, primaryConnection);
        }
    }

    public void seedExpiredEntry(SemanticCacheRequest request, String answer) throws SQLException {
        insertEntry(request, promptHash(request.prompt()), answer, Instant.now().minusSeconds(60));
    }

    private Candidate findExact(Connection connection, SemanticCacheRequest request, String promptHash) throws SQLException {
        String sql = """
            SELECT answer_text
            FROM sem_cache_entry
            WHERE tenant_id = ?
              AND prompt_hash = ?
              AND chat_model = ?
              AND embedding_model = ?
              AND embedding_dimension = ?
              AND prompt_template_version = ?
              AND source_fingerprint = ?
              AND policy_version = ?
              AND status = 'ACTIVE'
              AND expires_at > SYSTIMESTAMP
            FETCH FIRST 1 ROW ONLY
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindScope(statement, request, promptHash);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return new Candidate(resultSet.getString(1), null);
                }
                return null;
            }
        }
    }

    private Candidate findSemantic(Connection connection, SemanticCacheRequest request) throws SQLException {
        String sql = """
            SELECT answer_text,
                   VECTOR_DISTANCE(prompt_embedding, TO_VECTOR(?), COSINE) AS distance
            FROM sem_cache_entry
            WHERE tenant_id = ?
              AND chat_model = ?
              AND embedding_model = ?
              AND embedding_dimension = ?
              AND prompt_template_version = ?
              AND source_fingerprint = ?
              AND policy_version = ?
              AND status = 'ACTIVE'
              AND expires_at > SYSTIMESTAMP
            ORDER BY distance
            FETCH FIRST 1 ROW ONLY
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, vectorLiteral(request.embedding()));
            statement.setString(2, request.tenantId());
            statement.setString(3, request.chatModel());
            statement.setString(4, request.embeddingModel());
            statement.setInt(5, request.embeddingDimension());
            statement.setString(6, request.promptTemplateVersion());
            statement.setString(7, request.sourceFingerprint());
            statement.setString(8, request.policyVersion());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return new Candidate(resultSet.getString(1), resultSet.getDouble(2));
                }
                return null;
            }
        }
    }

    private void insertEntry(SemanticCacheRequest request, String promptHash, String answer) throws SQLException {
        insertEntry(request, promptHash, answer, Instant.now().plusSeconds(3600));
    }

    private void insertEntry(SemanticCacheRequest request, String promptHash, String answer, Instant expiresAt) throws SQLException {
        String sql = """
            INSERT INTO sem_cache_entry (
              tenant_id, prompt_hash, prompt_text, prompt_embedding, answer_text,
              chat_model, embedding_model, embedding_dimension, prompt_template_version,
              source_fingerprint, policy_version, status, expires_at
            ) VALUES (
              ?, ?, ?, TO_VECTOR(?), ?,
              ?, ?, ?, ?,
              ?, ?, 'ACTIVE', ?
            )
            """;
        Connection connection = primaryConnection();
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, request.tenantId());
                statement.setString(2, promptHash);
                statement.setString(3, request.prompt());
                statement.setString(4, vectorLiteral(request.embedding()));
                statement.setString(5, answer);
                statement.setString(6, request.chatModel());
                statement.setString(7, request.embeddingModel());
                statement.setInt(8, request.embeddingDimension());
                statement.setString(9, request.promptTemplateVersion());
                statement.setString(10, request.sourceFingerprint());
                statement.setString(11, request.policyVersion());
                statement.setTimestamp(12, Timestamp.from(expiresAt));
                statement.executeUpdate();
            }
            connection.commit();
        } finally {
            closeIfOwned(connection, primaryConnection);
        }
    }

    private void recordEvent(SemanticCacheResponse response) throws SQLException {
        String sql = """
            INSERT INTO sem_cache_event (
              scenario_name, route_name, decision, reason, distance, threshold, provider_calls, latency_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;
        Connection connection = primaryConnection();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, response.scenarioName());
            statement.setString(2, response.routeName());
            statement.setString(3, response.decision());
            statement.setString(4, response.reason());
            if (response.distance() == null) {
                statement.setNull(5, java.sql.Types.DOUBLE);
            } else {
                statement.setDouble(5, response.distance());
            }
            statement.setDouble(6, response.threshold());
            statement.setInt(7, response.providerCalls());
            statement.setLong(8, response.latencyMs());
            statement.executeUpdate();
        } finally {
            closeIfOwned(connection, primaryConnection);
        }
    }

    private Connection primaryConnection() throws SQLException {
        return primaryConnection == null ? primaryDataSource.getConnection() : primaryConnection;
    }

    private Connection readConnection() throws SQLException {
        return readConnection == null ? readDataSource.getConnection() : readConnection;
    }

    private static void closeIfOwned(Connection connection, Connection sharedConnection) throws SQLException {
        if (sharedConnection == null) {
            connection.close();
        }
    }

    private static void bindScope(PreparedStatement statement, SemanticCacheRequest request, String promptHash)
            throws SQLException {
        statement.setString(1, request.tenantId());
        statement.setString(2, promptHash);
        statement.setString(3, request.chatModel());
        statement.setString(4, request.embeddingModel());
        statement.setInt(5, request.embeddingDimension());
        statement.setString(6, request.promptTemplateVersion());
        statement.setString(7, request.sourceFingerprint());
        statement.setString(8, request.policyVersion());
    }

    private static String promptHash(String prompt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalize(prompt).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash prompt", exception);
        }
    }

    private static String normalize(String prompt) {
        return prompt.trim().replaceAll("\\s+", " ").toLowerCase();
    }

    private static String vectorLiteral(double[] values) {
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append(values[index]);
        }
        return builder.append(']').toString();
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private record Candidate(String answer, Double distance) {
    }
}
