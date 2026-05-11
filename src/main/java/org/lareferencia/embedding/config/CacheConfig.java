package org.lareferencia.embedding.config;

import javax.sql.DataSource;

import org.lareferencia.embedding.cache.EmbeddingCache;
import org.lareferencia.embedding.cache.PostgresEmbeddingCache;
import org.lareferencia.embedding.cache.RocksDbEmbeddingCache;
import org.lareferencia.embedding.cache.SqliteEmbeddingCache;
import org.rocksdb.RocksDBException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
@EnableConfigurationProperties(CacheProperties.class)
public class CacheConfig {

    @Bean
    @ConditionalOnProperty(name = "cache.type", havingValue = "postgres", matchIfMissing = true)
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @ConditionalOnProperty(name = "cache.type", havingValue = "postgres", matchIfMissing = true)
    @ConditionalOnMissingBean(DataSource.class)
    public DataSource dataSource(DataSourceProperties dataSourceProperties) {
        return dataSourceProperties.initializeDataSourceBuilder().build();
    }

    @Bean
    @ConditionalOnProperty(name = "cache.type", havingValue = "postgres", matchIfMissing = true)
    public EmbeddingCache postgresEmbeddingCache(DataSource dataSource) {
        log.debug("init PostgresEmbeddingCache...");
        return new PostgresEmbeddingCache(new JdbcTemplate(dataSource));
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "cache.type", havingValue = "rocksdb")
    public EmbeddingCache rocksDbEmbeddingCache(CacheProperties cacheProperties) throws RocksDBException {
        log.debug("init RocksDbEmbeddingCache...");
        return new RocksDbEmbeddingCache(cacheProperties.getRocksdb().getPath());
    }

    @Bean
    @ConditionalOnProperty(name = "cache.type", havingValue = "sqlite")
    public EmbeddingCache sqliteEmbeddingCache(CacheProperties cacheProperties) {
        log.debug("init SqliteEmbeddingCache...");
        return new SqliteEmbeddingCache(cacheProperties.getSqlite().getPath());
    }
}
