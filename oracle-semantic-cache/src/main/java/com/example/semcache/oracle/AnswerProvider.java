package com.example.semcache.oracle;

@FunctionalInterface
public interface AnswerProvider {
    String generate(SemanticCacheRequest request);
}
