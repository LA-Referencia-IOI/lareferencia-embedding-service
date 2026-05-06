package org.lareferencia.embedding.config;

import org.lareferencia.embedding.cache.EmbeddingCache;
import org.lareferencia.embedding.cache.EmbeddingRepository;
import org.lareferencia.embedding.cache.PostgresEmbeddingCache;
import org.lareferencia.embedding.cache.RedisEmbeddingCache;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;

@Configuration
public class CacheConfig {

    @Bean
    @ConditionalOnProperty(name = "cache.type", havingValue = "redis")
    public EmbeddingCache redisEmbeddingCache(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, byte[]> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(RedisSerializer.string());
        template.setValueSerializer(RedisSerializer.byteArray());
        template.afterPropertiesSet();
        return new RedisEmbeddingCache(template);
    }

    @Bean
    @ConditionalOnProperty(name = "cache.type", havingValue = "postgres", matchIfMissing = true)
    public EmbeddingCache postgresEmbeddingCache(EmbeddingRepository repository) {
        return new PostgresEmbeddingCache(repository);
    }
}
