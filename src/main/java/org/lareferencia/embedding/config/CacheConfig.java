package org.lareferencia.embedding.config;

import org.lareferencia.embedding.cache.EmbeddingCache;
import org.lareferencia.embedding.cache.EmbeddingRepository;
import org.lareferencia.embedding.cache.PostgresEmbeddingCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CacheConfig {

    @Bean
    public EmbeddingCache embeddingCache(EmbeddingRepository repository) {
        return new PostgresEmbeddingCache(repository);
    }
}
