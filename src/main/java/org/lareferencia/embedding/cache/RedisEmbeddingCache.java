package org.lareferencia.embedding.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
public class RedisEmbeddingCache implements EmbeddingCache {

    private final RedisTemplate<String, byte[]> redisTemplate;

    @Override
    public Optional<byte[]> get(String key) {
        log.debug("Checking Redis cache for key: {}", key);
        byte[] embedding = redisTemplate.opsForValue().get(key);
        return Optional.ofNullable(embedding);
    }

    @Override
    public void put(String key, byte[] embedding) {
        log.debug("Storing embedding in Redis cache for key: {}", key);
        redisTemplate.opsForValue().set(key, embedding);
    }
}
