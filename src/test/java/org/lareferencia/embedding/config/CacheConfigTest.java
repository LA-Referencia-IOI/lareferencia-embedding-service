package org.lareferencia.embedding.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lareferencia.embedding.cache.EmbeddingCache;
import org.lareferencia.embedding.cache.PostgresEmbeddingCache;
import org.lareferencia.embedding.cache.RocksDbEmbeddingCache;
import org.lareferencia.embedding.cache.SqliteEmbeddingCache;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CacheConfigTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withUserConfiguration(CacheConfig.class);

  @Test
  void defaultsToPostgresCache() {
    contextRunner
        .withBean(DataSource.class, () -> mock(DataSource.class))
        .run(context -> {
          assertThat(context).hasSingleBean(EmbeddingCache.class);
          assertThat(context.getBean(EmbeddingCache.class)).isInstanceOf(PostgresEmbeddingCache.class);
        });
  }

  @Test
  void selectsRocksDbCacheWhenConfigured() {
    contextRunner
        .withPropertyValues(
            "cache.type=rocksdb",
            "cache.rocksdb.path=target/test-rocksdb/config-cache")
        .run(context -> {
          assertThat(context).hasSingleBean(EmbeddingCache.class);
          assertThat(context.getBean(EmbeddingCache.class)).isInstanceOf(RocksDbEmbeddingCache.class);
          assertThat(context).doesNotHaveBean(DataSource.class);
        });
  }

  @Test
  void selectsSqliteCacheWhenConfigured(@TempDir Path tempDir) {
    contextRunner
        .withPropertyValues(
            "cache.type=sqlite",
            "cache.sqlite.path=" + tempDir.resolve("config-cache.db"))
        .run(context -> {
          assertThat(context).hasSingleBean(EmbeddingCache.class);
          assertThat(context.getBean(EmbeddingCache.class)).isInstanceOf(SqliteEmbeddingCache.class);
          assertThat(context).doesNotHaveBean(DataSource.class);
        });
  }
}