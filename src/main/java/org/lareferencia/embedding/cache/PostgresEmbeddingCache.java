package org.lareferencia.embedding.cache;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import com.pgvector.PGvector;

import org.springframework.jdbc.core.JdbcTemplate;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PostgresEmbeddingCache implements EmbeddingCache {

    private static final String CREATE_EXTENSION_SQL = "CREATE EXTENSION IF NOT EXISTS vector";
    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS embeddings (
                hash_key VARCHAR(128) PRIMARY KEY,
                embedding VECTOR(2560) NOT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """;
    private static final String SELECT_SQL = "SELECT embedding::text FROM embeddings WHERE hash_key = ?";
    private static final String UPSERT_SQL = """
            INSERT INTO embeddings (hash_key, embedding, created_at)
            VALUES (?, CAST(? AS vector), ?)
            ON CONFLICT (hash_key) DO UPDATE SET
                embedding = EXCLUDED.embedding,
                created_at = EXCLUDED.created_at
            """;

    private final JdbcTemplate jdbcTemplate;
    private final AtomicBoolean schemaInitialized = new AtomicBoolean(false);

    public PostgresEmbeddingCache(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<List<Double>> get(String key) {
        ensureSchemaInitialized();
        log.debug("Checking Postgres cache for key: {}", key);
        List<List<Double>> results = jdbcTemplate.query(
                SELECT_SQL,
                (rs, rowNum) -> {
                    float[] vector = parseVector(rs.getString(1));
                    List<Double> list = new ArrayList<>(vector.length);
                    for (float value : vector) {
                        list.add((double) value);
                    }
                    return list;
                },
                key);

        return results.stream().findFirst();
    }

    @Override
    public void put(String key, List<Double> embedding) {
        ensureSchemaInitialized();
        log.debug("Storing embedding in Postgres cache for key: {}", key);
        float[] vector = toFloatArray(embedding);

        String vectorString = new PGvector(vector).getValue();
        jdbcTemplate.update(UPSERT_SQL, key, vectorString, Timestamp.valueOf(LocalDateTime.now()));
    }

    private void ensureSchemaInitialized() {
        if (schemaInitialized.compareAndSet(false, true)) {
            try {
                jdbcTemplate.execute(CREATE_EXTENSION_SQL);
                jdbcTemplate.execute(CREATE_TABLE_SQL);
            } catch (RuntimeException exception) {
                schemaInitialized.set(false);
                throw exception;
            }
        }
    }

    private static float[] toFloatArray(List<Double> embedding) {
        float[] vector = new float[embedding.size()];
        for (int index = 0; index < embedding.size(); index++) {
            vector[index] = embedding.get(index).floatValue();
        }
        return vector;
    }

    private static float[] parseVector(String vectorText) {
        try {
            return new PGvector(vectorText).toArray();
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to parse pgvector payload", exception);
        }
    }
}
