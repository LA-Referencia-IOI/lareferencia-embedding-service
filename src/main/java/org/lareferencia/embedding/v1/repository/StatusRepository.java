package org.lareferencia.embedding.v1.repository;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class StatusRepository {

  private final JdbcTemplate jdbcTemplate;

  public String currentDatabaseName() {
    return jdbcTemplate.queryForObject("SELECT current_database()", String.class);
  }

  public long currentDatabaseSizeBytes() {
    Long size = jdbcTemplate.queryForObject("SELECT pg_database_size(current_database())", Long.class);
    return size == null ? 0 : size;
  }

  public long storeCount() {
    Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stores", Long.class);
    return count == null ? 0 : count;
  }

  public List<EmbeddingTableStats> embeddingTableStats(String schemaName) {
    String sql = """
        SELECT
          c.relname AS table_name,
          pg_relation_size(c.oid) AS table_size_bytes,
          pg_indexes_size(c.oid) AS indexes_size_bytes,
          pg_total_relation_size(c.oid) AS total_size_bytes,
          COALESCE(s.n_live_tup, 0) AS estimated_rows
        FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        LEFT JOIN pg_stat_user_tables s ON s.relid = c.oid
        WHERE n.nspname = ?
          AND c.relkind IN ('r', 'p')
          AND c.relname LIKE ? ESCAPE '\\'
        ORDER BY c.relname
        """;
    return jdbcTemplate.query(sql, (rs, rowNum) -> new EmbeddingTableStats(
        schemaName,
        rs.getString("table_name"),
        rs.getLong("table_size_bytes"),
        rs.getLong("indexes_size_bytes"),
        rs.getLong("total_size_bytes"),
        rs.getLong("estimated_rows")),
        schemaName,
        "embeddings\\_%");
  }

  public record EmbeddingTableStats(
      String schemaName,
      String tableName,
      long tableSizeBytes,
      long indexesSizeBytes,
      long totalSizeBytes,
      long estimatedRows) {
  }
}
