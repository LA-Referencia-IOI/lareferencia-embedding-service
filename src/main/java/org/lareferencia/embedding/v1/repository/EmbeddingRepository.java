package org.lareferencia.embedding.v1.repository;

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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.stream.Collectors;

import com.pgvector.PGvector;

import org.lareferencia.embedding.v1.config.PgVectorStoreProperties;
import org.lareferencia.embedding.v1.service.StoreTableNamingService;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.lang.NonNull;
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
  private final StoreTableNamingService tableNamingService;
  private final PgVectorStoreProperties pgVectorStoreProperties;
  private final ConcurrentMap<StoreVectorKey, Boolean> initializedTables = new ConcurrentHashMap<>();

  public void ensureStoreTableExists(UUID storeId, int embeddingDimensions, boolean createIndex) {
    if (embeddingDimensions <= 0) {
      throw new IllegalArgumentException("Embedding dimensions must be a positive number");
    }

    String tableName = tableNamingService.tableName(storeId);
    StoreVectorKey key = new StoreVectorKey(tableName, embeddingDimensions, createIndex);
    if (initializedTables.containsKey(key)) {
      return;
    }
    initializeStoreTable(storeId, tableName, embeddingDimensions, createIndex);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          initializedTables.put(key, Boolean.TRUE);
        }
      });
    } else {
      initializedTables.put(key, Boolean.TRUE);
    }
  }

  public Map<String, float[]> findByHashes(UUID storeId, Integer embeddingDimensions, Collection<String> hashes) {
    if (hashes == null || hashes.isEmpty()) {
      return Map.of();
    }

    if (embeddingDimensions == null || embeddingDimensions <= 0) {
      return Map.of();
    }

    ensureStoreTableExists(storeId, embeddingDimensions, false);
    String qualifiedTableName = qualifiedTableName(storeId);

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
    log.debug("Database cache lookup storeId={} requestedHashes={} found={}", storeId, orderedHashes.size(),
        results.size());
    return results;
  }

  public void saveEmbeddings(UUID storeId, int embeddingDimensions, Map<String, float[]> embeddingsByHash) {
    if (embeddingsByHash == null || embeddingsByHash.isEmpty()) {
      return;
    }

    embeddingsByHash.forEach(
        (hash, embedding) -> validateEmbedding(hash, embedding, embeddingDimensions));
    ensureStoreTableExists(storeId, embeddingDimensions, false);
    String qualifiedTableName = qualifiedTableName(storeId);
    List<Map.Entry<String, float[]>> entries = new ArrayList<>(embeddingsByHash.entrySet());
    String sql = """
          INSERT INTO %s (id, embedding)
          VALUES (?, ?)
          ON CONFLICT (id) DO NOTHING
        """.formatted(qualifiedTableName);

    jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
      @Override
      public void setValues(@NonNull PreparedStatement ps, int index) throws SQLException {
        Map.Entry<String, float[]> entry = entries.get(index);
        ps.setString(1, entry.getKey());
        ps.setObject(2, new PGvector(entry.getValue()));
      }

      @Override
      public int getBatchSize() {
        return entries.size();
      }
    });
    log.debug("Persisted embeddings into database storeId={} rows={} table={}", storeId, entries.size(),
        qualifiedTableName);
  }

  private void initializeStoreTable(UUID storeId, String tableName, int embeddingDimensions, boolean createIndex) {
    validateIdentifier(pgVectorStoreProperties.getSchemaName(), "schema name");
    validateIdentifier(tableName, "table name");

    if (pgVectorStoreProperties.isSchemaValidation()) {
      validateSchemaExists(pgVectorStoreProperties.getSchemaName());
    }

    if (!pgVectorStoreProperties.isInitializeSchema()) {
      return;
    }

    String qualifiedTableName = qualifiedTableName(tableName);
    String createTableSql = """
        CREATE TABLE IF NOT EXISTS %s (
          id TEXT PRIMARY KEY,
          embedding vector(%d) NOT NULL,
          created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
        )
        """.formatted(qualifiedTableName, embeddingDimensions);
    jdbcTemplate.execute(createTableSql);

    PgVectorStoreProperties.IndexType indexType = resolvedIndexType(embeddingDimensions);
    if (createIndex && indexType != PgVectorStoreProperties.IndexType.NONE) {
      String vectorIndexName = vectorIndexName(tableName);
      validateIdentifier(vectorIndexName, "index name");
      String vectorIndexSql = "CREATE INDEX IF NOT EXISTS %s ON %s USING %s (embedding %s)"
          .formatted(
              vectorIndexName,
              qualifiedTableName,
              indexMethod(indexType),
              vectorOperatorClass(pgVectorStoreProperties.getDistanceType()));
      jdbcTemplate.execute(vectorIndexSql);
    }
  }

  private String qualifiedTableName(UUID storeId) {
    String tableName = tableNamingService.tableName(storeId);
    return qualifiedTableName(tableName);
  }

  private String qualifiedTableName(String tableName) {
    validateIdentifier(pgVectorStoreProperties.getSchemaName(), "schema name");
    validateIdentifier(tableName, "table name");
    return pgVectorStoreProperties.getSchemaName() + "." + tableName;
  }

  private PgVectorStoreProperties.IndexType resolvedIndexType(int embeddingDimensions) {
    PgVectorStoreProperties.IndexType configuredIndexType = pgVectorStoreProperties.getIndexType();
    if (configuredIndexType == PgVectorStoreProperties.IndexType.HNSW
        && embeddingDimensions > MAX_HNSW_DIMENSIONS) {
      log.warn(
          "Disabling HNSW index for embedding dimensions={} because pgvector supports at most {} dimensions for HNSW",
          embeddingDimensions,
          MAX_HNSW_DIMENSIONS);
      return PgVectorStoreProperties.IndexType.NONE;
    }
    return configuredIndexType;
  }

  private String indexMethod(PgVectorStoreProperties.IndexType indexType) {
    return switch (indexType) {
      case HNSW -> "HNSW";
      case IVFFLAT -> "IVFFLAT";
      case NONE -> throw new IllegalArgumentException("NONE index type does not have a SQL index method");
    };
  }

  private String vectorOperatorClass(PgVectorStoreProperties.DistanceType distanceType) {
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
    Integer count = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = ?",
        Integer.class,
        schemaName);

    if (count == null || count == 0) {
      throw new IllegalArgumentException("Configured schema does not exist: " + schemaName);
    }
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

  private record StoreVectorKey(String tableName, int embeddingDimensions, boolean createIndex) {
  }
}