package org.lareferencia.embedding.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
public class PostgresEmbeddingCache implements EmbeddingCache {

    private final EmbeddingRepository repository;

    @Override
    public Optional<byte[]> get(String key) {
        log.debug("Checking Postgres cache for key: {}", key);
        return repository.findById(key).map(EmbeddingEntity::getValue);
    }

    @Override
    public void put(String key, byte[] data) {
        log.debug("Storing embedding in Postgres cache for key: {}", key);
        EmbeddingEntity entity = EmbeddingEntity.builder()
                .key(key)
                .value(data)
                .build();
        repository.save(entity);
    }
}
