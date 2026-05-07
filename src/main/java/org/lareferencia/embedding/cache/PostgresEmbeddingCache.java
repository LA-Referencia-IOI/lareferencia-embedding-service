package org.lareferencia.embedding.cache;

import com.pgvector.PGvector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
public class PostgresEmbeddingCache implements EmbeddingCache {

    private final EmbeddingRepository repository;

    @Override
    public Optional<List<Double>> get(String key) {
        log.debug("Checking Postgres cache for key: {}", key);
        return repository.findById(key).map(entity -> {
            float[] vector = entity.getEmbedding();
            List<Double> list = new ArrayList<>(vector.length);
            for (float v : vector) {
                list.add((double) v);
            }
            return list;
        });
    }

    @Override
    public void put(String key, List<Double> embedding) {
        log.debug("Storing embedding in Postgres cache for key: {}", key);
        float[] vector = new float[embedding.size()];
        for (int i = 0; i < embedding.size(); i++) {
            vector[i] = embedding.get(i).floatValue();
        }

        // Use native query with explicit cast to vector
        String vectorString = new PGvector(vector).getValue();
        repository.saveWithCast(key, vectorString, LocalDateTime.now());
    }
}
