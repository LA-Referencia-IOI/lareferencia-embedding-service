package org.lareferencia.embedding.cache;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SqliteEmbeddingCache implements EmbeddingCache {

  private static final String CREATE_TABLE_SQL = """
      CREATE TABLE IF NOT EXISTS embeddings (
          hash_key TEXT PRIMARY KEY,
          embedding BLOB NOT NULL,
          created_at TEXT NOT NULL
      )
      """;
  private static final String SELECT_SQL = "SELECT embedding FROM embeddings WHERE hash_key = ?";
  private static final String UPSERT_SQL = """
      INSERT INTO embeddings (hash_key, embedding, created_at)
      VALUES (?, ?, ?)
      ON CONFLICT(hash_key) DO UPDATE SET
          embedding = excluded.embedding,
          created_at = excluded.created_at
      """;

  private final JdbcTemplate jdbcTemplate;
  private final AtomicBoolean schemaInitialized = new AtomicBoolean(false);

  public SqliteEmbeddingCache(String databasePath) {
    try {
      Path storagePath = Path.of(databasePath).toAbsolutePath();
      Path parent = storagePath.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }

      DriverManagerDataSource dataSource = new DriverManagerDataSource();
      dataSource.setDriverClassName("org.sqlite.JDBC");
      dataSource.setUrl("jdbc:sqlite:" + storagePath);
      this.jdbcTemplate = new JdbcTemplate(dataSource);
    } catch (Exception exception) {
      throw new IllegalStateException("Failed to initialize SQLite storage", exception);
    }
  }

  @Override
  public Optional<List<Double>> get(String key) {
    ensureSchemaInitialized();
    log.debug("Checking SQLite cache for key: {}", key);
    List<List<Double>> results = jdbcTemplate.query(
        SELECT_SQL,
        (rs, rowNum) -> deserialize(rs.getBytes(1)),
        key);
    return results.stream().findFirst();
  }

  @Override
  public void put(String key, List<Double> embedding) {
    ensureSchemaInitialized();
    log.debug("Storing embedding in SQLite cache for key: {}", key);
    jdbcTemplate.update(UPSERT_SQL, key, serialize(embedding), LocalDateTime.now().toString());
  }

  private void ensureSchemaInitialized() {
    if (schemaInitialized.compareAndSet(false, true)) {
      try {
        jdbcTemplate.execute(CREATE_TABLE_SQL);
      } catch (RuntimeException exception) {
        schemaInitialized.set(false);
        throw exception;
      }
    }
  }

  private static byte[] serialize(List<Double> embedding) {
    ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES + (embedding.size() * Double.BYTES))
        .order(ByteOrder.LITTLE_ENDIAN);
    buffer.putInt(embedding.size());
    for (Double value : embedding) {
      buffer.putDouble(value);
    }
    return buffer.array();
  }

  private static List<Double> deserialize(byte[] payload) {
    ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
    int size = buffer.getInt();
    List<Double> embedding = new ArrayList<>(size);
    for (int index = 0; index < size; index++) {
      embedding.add(buffer.getDouble());
    }
    return embedding;
  }
}