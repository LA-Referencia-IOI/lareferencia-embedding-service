package org.lareferencia.embedding.repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

import com.pgvector.PGvector;

import org.lareferencia.embedding.config.PgVectorStoreProperties;
import org.lareferencia.embedding.service.StoreTableNamingService;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
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
  private final EmbeddingModel embeddingModel;
  private final StoreTableNamingService tableNamingService;
  private final PgVectorStoreProperties pgVectorStoreProperties;
  private final ConcurrentMap<StoreVectorKey, PgVectorStore> vectorStores = new ConcurrentHashMap<>();

  public void ensureStoreTableExists(long storeId, int embeddingDimensions) {
    if (embeddingDimensions <= 0) {
      throw new IllegalArgumentException("Embedding dimensions must be a positive number");
    }

    getVectorStore(storeId, embeddingDimensions);
  }

  public Map<String, float[]> findByHashes(long storeId, Integer embeddingDimensions, Collection<String> hashes) {
    if (hashes == null || hashes.isEmpty()) {
      return Map.of();
    }

    if (embeddingDimensions == null || embeddingDimensions <= 0) {
      return Map.of();
    }

    JdbcTemplate nativeClient = nativeClient(storeId, embeddingDimensions);
    String qualifiedTableName = qualifiedTableName(storeId);

    List<String> orderedHashes = new ArrayList<>(hashes);
    String placeholders = orderedHashes.stream().map(ignored -> "?").collect(Collectors.joining(", "));
    String sql = "SELECT id AS hash, embedding::text AS embedding FROM %s WHERE id IN (%s)".formatted(
        qualifiedTableName,
        placeholders);

    Map<String, float[]> results = new LinkedHashMap<>();
    nativeClient.query(
        sql,
        (RowCallbackHandler) rs -> results.put(rs.getString("hash"), parseVector(rs.getString("embedding"))),
        orderedHashes.toArray());
    log.debug("Database cache lookup storeId={} requestedHashes={} found={}", storeId, orderedHashes.size(),
        results.size());
    return results;
  }

  public void saveEmbeddings(long storeId, int embeddingDimensions, Map<String, float[]> embeddingsByHash) {
    if (embeddingsByHash == null || embeddingsByHash.isEmpty()) {
      return;
    }

    embeddingsByHash.forEach(
        (hash, embedding) -> validateEmbedding(hash, embedding, embeddingDimensions));
    JdbcTemplate nativeClient = nativeClient(storeId, embeddingDimensions);
    String qualifiedTableName = qualifiedTableName(storeId);
    List<Map.Entry<String, float[]>> entries = new ArrayList<>(embeddingsByHash.entrySet());
    String sql = """
          INSERT INTO %s (id, embedding)
          VALUES (?, ?)
          ON CONFLICT (id) DO NOTHING
        """.formatted(qualifiedTableName);

    nativeClient.batchUpdate(sql, new BatchPreparedStatementSetter() {
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
    log.debug("Persisted embeddings into database storeId={} rows={} table={}", storeId, entries.size(),
        qualifiedTableName);
  }

  private PgVectorStore getVectorStore(long storeId, int embeddingDimensions) {
    String tableName = tableNamingService.tableName(storeId);
    StoreVectorKey key = new StoreVectorKey(tableName, embeddingDimensions);
    return vectorStores.computeIfAbsent(key, ignored -> buildVectorStore(tableName, embeddingDimensions));
  }

  private PgVectorStore buildVectorStore(String tableName, int embeddingDimensions) {
    validateIdentifier(pgVectorStoreProperties.getSchemaName(), "schema name");
    validateIdentifier(tableName, "table name");

    PgVectorStore.PgIndexType indexType = resolvedIndexType(embeddingDimensions);

    PgVectorStore vectorStore = PgVectorStore.builder(jdbcTemplate, embeddingModel)
        .schemaName(pgVectorStoreProperties.getSchemaName())
        .vectorTableName(tableName)
        .idType(PgVectorStore.PgIdType.TEXT)
        .vectorTableValidationsEnabled(pgVectorStoreProperties.isSchemaValidation())
        .dimensions(embeddingDimensions)
        .distanceType(pgVectorStoreProperties.getDistanceType())
        .indexType(indexType)
        .initializeSchema(pgVectorStoreProperties.isInitializeSchema())
        .maxDocumentBatchSize(pgVectorStoreProperties.getMaxDocumentBatchSize())
        .build();
    vectorStore.afterPropertiesSet();
    return vectorStore;
  }

  private JdbcTemplate nativeClient(long storeId, int embeddingDimensions) {
    return getVectorStore(storeId, embeddingDimensions)
        .<JdbcTemplate>getNativeClient()
        .orElseThrow(() -> new IllegalStateException("PgVectorStore native JdbcTemplate is not available"));
  }

  private String qualifiedTableName(long storeId) {
    String tableName = tableNamingService.tableName(storeId);
    return qualifiedTableName(tableName);
  }

  private String qualifiedTableName(String tableName) {
    validateIdentifier(pgVectorStoreProperties.getSchemaName(), "schema name");
    validateIdentifier(tableName, "table name");
    return pgVectorStoreProperties.getSchemaName() + "." + tableName;
  }

  private PgVectorStore.PgIndexType resolvedIndexType(int embeddingDimensions) {
    PgVectorStore.PgIndexType configuredIndexType = pgVectorStoreProperties.getIndexType();
    if (configuredIndexType == PgVectorStore.PgIndexType.HNSW && embeddingDimensions > MAX_HNSW_DIMENSIONS) {
      log.warn(
          "Disabling HNSW index for embedding dimensions={} because pgvector supports at most {} dimensions for HNSW",
          embeddingDimensions,
          MAX_HNSW_DIMENSIONS);
      return PgVectorStore.PgIndexType.NONE;
    }
    return configuredIndexType;
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

  private record StoreVectorKey(String tableName, int embeddingDimensions) {
  }
}