package org.lareferencia.embedding.repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.stream.Collectors;

import com.pgvector.PGvector;

import org.lareferencia.embedding.config.PgVectorStorageProperties;
import org.lareferencia.embedding.service.StorageTableNamingService;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Repository
@RequiredArgsConstructor
@Slf4j
public class EmbeddingRepository {

  private static final String IDENTIFIER_PATTERN = "^[A-Za-z_][A-Za-z0-9_]*$";
  private static final int MAX_HNSW_DIMENSIONS = 2000;

  private final JdbcTemplate jdbcTemplate;
  private final StorageTableNamingService tableNamingService;
  private final PgVectorStorageProperties pgVectorStorageProperties;
  private final ConcurrentMap<StorageVectorKey, Boolean> initializedTables = new ConcurrentHashMap<>();
  private final ConcurrentSkipListSet<String> initializedSchemas = new ConcurrentSkipListSet<>();

  public void ensureStorageTableExists(UUID storageId, int embeddingDimensions) {
    if (embeddingDimensions <= 0) {
      throw new IllegalArgumentException("Embedding dimensions must be a positive number");
    }

    String tableName = tableNamingService.tableName(storageId);
    StorageVectorKey key = new StorageVectorKey(tableName, embeddingDimensions);
    initializedTables.computeIfAbsent(key, ignored -> {
      initializeStorageTable(storageId, tableName, embeddingDimensions);
      return Boolean.TRUE;
    });
  }

  public Map<String, float[]> findByHashes(UUID storageId, Integer embeddingDimensions, Collection<String> hashes) {
    if (hashes == null || hashes.isEmpty()) {
      return Map.of();
    }

    if (embeddingDimensions == null || embeddingDimensions <= 0) {
      return Map.of();
    }

    ensureStorageTableExists(storageId, embeddingDimensions);
    String qualifiedTableName = qualifiedTableName(storageId);

    List<String> orderedHashes = new ArrayList<>(hashes);
    String placeholders = orderedHashes.stream().map(ignored -> "?").collect(Collectors.joining(", "));
    String sql = "SELECT id AS hash, embedding::text AS embedding FROM %s WHERE id IN (%s)".formatted(
        qualifiedTableName,
        placeholders);

    Map<String, float[]> results = new LinkedHashMap<>();
    jdbcTemplate.query(
        sql,
        (RowCallbackHandler) rs -> results.put(rs.getString("hash"), parseVector(rs.getString("embedding"))),
        orderedHashes.toArray());
    log.debug("Database cache lookup storageId={} requestedHashes={} found={}", storageId, orderedHashes.size(),
        results.size());
    return results;
  }

  public void saveEmbeddings(UUID storageId, int embeddingDimensions, Map<String, float[]> embeddingsByHash) {
    if (embeddingsByHash == null || embeddingsByHash.isEmpty()) {
      return;
    }

    embeddingsByHash.forEach(
        (hash, embedding) -> validateEmbedding(hash, embedding, embeddingDimensions));
    ensureStorageTableExists(storageId, embeddingDimensions);
    String qualifiedTableName = qualifiedTableName(storageId);
    List<Map.Entry<String, float[]>> entries = new ArrayList<>(embeddingsByHash.entrySet());
    String sql = """
          INSERT INTO %s (id, embedding)
          VALUES (?, ?)
          ON CONFLICT (id) DO NOTHING
        """.formatted(qualifiedTableName);

    jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
      @Override
      public void setValues(PreparedStatement ps, int index) throws SQLException {
        Map.Entry<String, float[]> entry = entries.get(index);
        ps.setString(1, entry.getKey());
        ps.setObject(2, new PGvector(entry.getValue()));
      }

      @Override
      public int getBatchSize() {
        return entries.size();
      }
    });
    log.debug("Persisted embeddings into database storageId={} rows={} table={}", storageId, entries.size(),
        qualifiedTableName);
  }

  private void initializeStorageTable(UUID storageId, String tableName, int embeddingDimensions) {
    validateIdentifier(pgVectorStorageProperties.getSchemaName(), "schema name");
    validateIdentifier(tableName, "table name");

    if (pgVectorStorageProperties.isSchemaValidation()) {
      validateSchemaExists(pgVectorStorageProperties.getSchemaName());
    }

    if (!pgVectorStorageProperties.isInitializeSchema()) {
      return;
    }

    String qualifiedTableName = qualifiedTableName(tableName);
    String createTableSql = """
        CREATE TABLE IF NOT EXISTS %s (
          id CHAR(16) PRIMARY KEY,
          embedding vector(%d) NOT NULL,
          created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
        )
        """.formatted(qualifiedTableName, embeddingDimensions);
    jdbcTemplate.execute(createTableSql);

    String createdAtIndexName = tableNamingService.createdAtIndexName(storageId);
    validateIdentifier(createdAtIndexName, "index name");
    jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS %s ON %s (created_at)"
        .formatted(createdAtIndexName, qualifiedTableName));

    PgVectorStorageProperties.IndexType indexType = resolvedIndexType(embeddingDimensions);
    if (indexType != PgVectorStorageProperties.IndexType.NONE) {
      String vectorIndexName = vectorIndexName(tableName);
      validateIdentifier(vectorIndexName, "index name");
      String vectorIndexSql = "CREATE INDEX IF NOT EXISTS %s ON %s USING %s (embedding %s)"
          .formatted(
              vectorIndexName,
              qualifiedTableName,
              indexMethod(indexType),
              vectorOperatorClass(pgVectorStorageProperties.getDistanceType()));
      jdbcTemplate.execute(vectorIndexSql);
    }
  }

  private String qualifiedTableName(UUID storageId) {
    String tableName = tableNamingService.tableName(storageId);
    return qualifiedTableName(tableName);
  }

  private String qualifiedTableName(String tableName) {
    validateIdentifier(pgVectorStorageProperties.getSchemaName(), "schema name");
    validateIdentifier(tableName, "table name");
    return pgVectorStorageProperties.getSchemaName() + "." + tableName;
  }

  private PgVectorStorageProperties.IndexType resolvedIndexType(int embeddingDimensions) {
    PgVectorStorageProperties.IndexType configuredIndexType = pgVectorStorageProperties.getIndexType();
    if (configuredIndexType == PgVectorStorageProperties.IndexType.HNSW
        && embeddingDimensions > MAX_HNSW_DIMENSIONS) {
      log.warn(
          "Disabling HNSW index for embedding dimensions={} because pgvector supports at most {} dimensions for HNSW",
          embeddingDimensions,
          MAX_HNSW_DIMENSIONS);
      return PgVectorStorageProperties.IndexType.NONE;
    }
    return configuredIndexType;
  }

  private String indexMethod(PgVectorStorageProperties.IndexType indexType) {
    return switch (indexType) {
      case HNSW -> "HNSW";
      case IVFFLAT -> "IVFFLAT";
      case NONE -> throw new IllegalArgumentException("NONE index type does not have a SQL index method");
    };
  }

  private String vectorOperatorClass(PgVectorStorageProperties.DistanceType distanceType) {
    return switch (distanceType) {
      case COSINE_DISTANCE -> "vector_cosine_ops";
      case EUCLIDEAN_DISTANCE -> "vector_l2_ops";
      case NEGATIVE_INNER_PRODUCT -> "vector_ip_ops";
    };
  }

  private String vectorIndexName(String tableName) {
    return "idx_" + tableName + "_embedding";
  }

  private void validateSchemaExists(String schemaName) {
    if (initializedSchemas.contains(schemaName)) {
      return;
    }

    Integer count = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = ?",
        Integer.class,
        schemaName);

    if (count == null || count == 0) {
      throw new IllegalArgumentException("Configured schema does not exist: " + schemaName);
    }
    initializedSchemas.add(schemaName);
  }

  private void validateEmbedding(String hash, float[] embedding, int expectedDimensions) {
    if (embedding == null || embedding.length == 0) {
      throw new IllegalArgumentException("Embedding for hash " + hash + " must not be empty");
    }
    if (embedding.length != expectedDimensions) {
      throw new IllegalArgumentException("Embedding for hash " + hash + " has dimension " + embedding.length
          + " but expected " + expectedDimensions);
    }
  }

  private void validateIdentifier(String identifier, String label) {
    if (identifier == null || !identifier.matches(IDENTIFIER_PATTERN)) {
      throw new IllegalArgumentException("Invalid pgvector " + label + ": " + identifier);
    }
  }

  private float[] parseVector(String vectorText) {
    try {
      return new PGvector(vectorText).toArray();
    } catch (SQLException exception) {
      throw new IllegalStateException("Failed to parse pgvector payload", exception);
    }
  }

  private record StorageVectorKey(String tableName, int embeddingDimensions) {
  }
}