package com.example.semcache.oracle;

public record SemanticCacheResponse(
    String scenarioName,
    String decision,
    String reason,
    String answer,
    String routeName,
    Double distance,
    double threshold,
    int providerCalls,
    long latencyMs) {
}
