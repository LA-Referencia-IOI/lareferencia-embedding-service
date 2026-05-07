package org.lareferencia.embedding.cache;

import java.util.List;
import java.util.Optional;

public interface EmbeddingCache {
    Optional<List<Double>> get(String key);
    void put(String key, List<Double> embedding);
}
