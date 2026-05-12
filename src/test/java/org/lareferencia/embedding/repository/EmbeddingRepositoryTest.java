package org.lareferencia.embedding.repository;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.lareferencia.embedding.config.PgVectorStorageProperties;
import org.lareferencia.embedding.service.StorageTableNamingService;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;

class EmbeddingRepositoryTest {

        private static final UUID STORE_ID_1 = UUID.fromString("11111111-1111-1111-1111-111111111111");

        @Test
        void initializesStorageTablesThroughPureJdbcPgvector() {
                JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
                doNothing().when(jdbcTemplate).execute(any(String.class));
                PgVectorStorageProperties properties = new PgVectorStorageProperties();
                properties.setSchemaValidation(false);
                EmbeddingRepository repository = new EmbeddingRepository(jdbcTemplate,
                                new StorageTableNamingService(), properties);

                repository.ensureStorageTableExists(STORE_ID_1, 768);

                InOrder calls = inOrder(jdbcTemplate);
                calls.verify(jdbcTemplate).execute("CREATE EXTENSION IF NOT EXISTS vector");

                ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
                verify(jdbcTemplate, times(4)).execute(sqlCaptor.capture());

                List<String> executedSql = sqlCaptor.getAllValues();
                assertTrue(executedSql.stream().anyMatch(sql -> sql.contains("CREATE TABLE IF NOT EXISTS")
                                && sql.contains("embeddings_11111111_1111_1111_1111_111111111111")
                                && sql.contains("vector(768)")));
                assertTrue(executedSql.stream().anyMatch(sql -> sql.contains("USING HNSW")
                                && sql.contains("vector_cosine_ops")));
        }

        @Test
        void disablesHnswIndexForEmbeddingsLargerThanTwoThousandDimensions() {
                JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
                doNothing().when(jdbcTemplate).execute(any(String.class));
                PgVectorStorageProperties properties = new PgVectorStorageProperties();
                properties.setSchemaValidation(false);
                EmbeddingRepository repository = new EmbeddingRepository(jdbcTemplate,
                                new StorageTableNamingService(), properties);

                repository.ensureStorageTableExists(STORE_ID_1, 2560);

                ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
                verify(jdbcTemplate, times(3)).execute(sqlCaptor.capture());

                List<String> executedSql = sqlCaptor.getAllValues();
                assertTrue(executedSql.stream().anyMatch(sql -> sql.contains("CREATE TABLE IF NOT EXISTS")
                                && sql.contains("embeddings_11111111_1111_1111_1111_111111111111")
                                && sql.contains("vector(2560)")));
                assertTrue(executedSql.stream().noneMatch(sql -> sql.contains("USING HNSW")
                                || sql.contains("USING IVFFLAT")
                                || sql.contains("vector_cosine_ops")
                                || sql.contains("vector_l2_ops")
                                || sql.contains("vector_ip_ops")));
        }
}