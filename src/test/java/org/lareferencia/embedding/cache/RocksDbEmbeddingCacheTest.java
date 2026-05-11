package org.lareferencia.embedding.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.RocksDBException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RocksDbEmbeddingCacheTest {

    @TempDir
    Path tempDir;

    @Test
    void storesAndReadsEmbeddings() throws RocksDBException, IOException {
        try (RocksDbEmbeddingCache cache = new RocksDbEmbeddingCache(tempDir.resolve("cache-db").toString())) {
            List<Double> embedding = List.of(0.25d, 0.5d, 0.75d);

            cache.put("model:key", embedding);
            Optional<List<Double>> result = cache.get("model:key");

            assertTrue(result.isPresent());
            assertEquals(embedding, result.get());
        }
    }
}