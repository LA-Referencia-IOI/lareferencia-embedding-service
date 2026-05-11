package org.lareferencia.embedding.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteEmbeddingCacheTest {

  @TempDir
  Path tempDir;

  @Test
  void storesAndReadsEmbeddingsAcrossInstances() {
    Path databasePath = tempDir.resolve("embedding-cache.db");
    List<Double> embedding = List.of(0.25d, 0.5d, 0.75d);

    SqliteEmbeddingCache writerCache = new SqliteEmbeddingCache(databasePath.toString());
    writerCache.put("model:key", embedding);

    SqliteEmbeddingCache readerCache = new SqliteEmbeddingCache(databasePath.toString());
    Optional<List<Double>> result = readerCache.get("model:key");

    assertTrue(result.isPresent());
    assertEquals(embedding, result.get());
  }
}