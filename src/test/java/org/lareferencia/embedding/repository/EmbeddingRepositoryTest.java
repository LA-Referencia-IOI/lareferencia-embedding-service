package org.lareferencia.embedding.repository;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.lareferencia.embedding.config.PgVectorStoreProperties;
import org.lareferencia.embedding.service.StoreTableNamingService;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;

class EmbeddingRepositoryTest {

  @Test
  void initializesStoreTablesThroughSpringAiPgVectorStore() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
    doNothing().when(jdbcTemplate).execute(any(String.class));
    PgVectorStoreProperties properties = new PgVectorStoreProperties();
    properties.setSchemaValidation(false);
    EmbeddingRepository repository = new EmbeddingRepository(jdbcTemplate, embeddingModel,
        new StoreTableNamingService(), properties);

    repository.ensureStoreTableExists(1L, 768);

    InOrder calls = inOrder(jdbcTemplate);
    calls.verify(jdbcTemplate).execute("CREATE EXTENSION IF NOT EXISTS vector");

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate, times(5)).execute(sqlCaptor.capture());

    List<String> executedSql = sqlCaptor.getAllValues();
    assertTrue(executedSql.stream().anyMatch(sql -> sql.contains("CREATE TABLE IF NOT EXISTS")
        && sql.contains("embeddings_1")
        && sql.contains("vector(768)")));
  }

  @Test
  void disablesHnswIndexForEmbeddingsLargerThanTwoThousandDimensions() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
    doNothing().when(jdbcTemplate).execute(any(String.class));
    PgVectorStoreProperties properties = new PgVectorStoreProperties();
    properties.setSchemaValidation(false);
    EmbeddingRepository repository = new EmbeddingRepository(jdbcTemplate, embeddingModel,
        new StoreTableNamingService(), properties);

    repository.ensureStoreTableExists(1L, 2560);

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate, times(4)).execute(sqlCaptor.capture());

    List<String> executedSql = sqlCaptor.getAllValues();
    assertTrue(executedSql.stream().anyMatch(sql -> sql.contains("CREATE TABLE IF NOT EXISTS")
        && sql.contains("embeddings_1")
        && sql.contains("vector(2560)")));
    assertTrue(executedSql.stream().noneMatch(sql -> sql.contains("USING HNSW")
        || sql.contains("vector_cosine_ops")
        || sql.contains("CREATE INDEX")));
  }
}