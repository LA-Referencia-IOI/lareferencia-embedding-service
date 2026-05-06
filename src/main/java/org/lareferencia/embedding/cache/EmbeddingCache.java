package org.lareferencia.embedding.cache;

import java.util.Optional;

public interface EmbeddingCache {
    Optional<byte[]> get(String key);
    void put(String key, byte[] embedding);
}
