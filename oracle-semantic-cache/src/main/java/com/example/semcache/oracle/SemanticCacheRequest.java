package com.example.semcache.oracle;

public record SemanticCacheRequest(
    String scenarioName,
    String tenantId,
    String prompt,
    String chatModel,
    String embeddingModel,
    int embeddingDimension,
    String promptTemplateVersion,
    String sourceFingerprint,
    String policyVersion,
    double[] embedding,
    double threshold) {
}
