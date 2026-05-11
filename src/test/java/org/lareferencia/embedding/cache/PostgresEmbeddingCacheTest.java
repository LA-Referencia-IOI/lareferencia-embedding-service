package org.lareferencia.embedding.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class PostgresEmbeddingCacheTest {

  private JdbcTemplate jdbcTemplate;
  private PostgresEmbeddingCache cache;

  @BeforeEach
  void setUp() {
    jdbcTemplate = mock(JdbcTemplate.class);
    cache = new PostgresEmbeddingCache(jdbcTemplate);
  }

  @Test
  @SuppressWarnings("unchecked")
  void getReturnsEmbeddingFromPgvectorText() {
    when(jdbcTemplate.query(eq("SELECT embedding::text FROM embeddings WHERE hash_key = ?"), any(RowMapper.class),
        eq("model:key")))
        .thenReturn(List.of(List.of(0.1d, 0.2d, 0.3d)));

    Optional<List<Double>> result = cache.get("model:key");

    assertTrue(result.isPresent());
    assertEquals(List.of(0.1d, 0.2d, 0.3d), result.get());
    verify(jdbcTemplate, times(1)).execute("CREATE EXTENSION IF NOT EXISTS vector");
    verify(jdbcTemplate, times(1)).execute("""
        CREATE TABLE IF NOT EXISTS embeddings (
            hash_key VARCHAR(128) PRIMARY KEY,
            embedding VECTOR(2560) NOT NULL,
            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
        )
        """);
  }

  @Test
  void putInitializesSchemaOnlyOnce() {
    cache.put("model:key", List.of(0.5d, 1.5d));
    cache.put("model:key", List.of(2.5d, 3.5d));

    verify(jdbcTemplate, times(1)).execute("CREATE EXTENSION IF NOT EXISTS vector");
    verify(jdbcTemplate, times(1)).execute("""
        CREATE TABLE IF NOT EXISTS embeddings (
            hash_key VARCHAR(128) PRIMARY KEY,
            embedding VECTOR(2560) NOT NULL,
            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
        )
        """);
    verify(jdbcTemplate, times(2)).update(eq("""
        INSERT INTO embeddings (hash_key, embedding, created_at)
        VALUES (?, CAST(? AS vector), ?)
        ON CONFLICT (hash_key) DO UPDATE SET
            embedding = EXCLUDED.embedding,
            created_at = EXCLUDED.created_at
        """), eq("model:key"), any(), any());
  }
}