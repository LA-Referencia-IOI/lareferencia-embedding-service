package org.lareferencia.embedding.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import net.jpountz.xxhash.XXHash64;
import net.jpountz.xxhash.XXHashFactory;
import org.lareferencia.embedding.cache.EmbeddingCache;
import org.lareferencia.embedding.client.EmbeddingClient;
import org.lareferencia.embedding.dto.EmbeddingResponse;
import org.lareferencia.embedding.util.TextNormalizer;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Slf4j
@Service
public class EmbeddingService {

    private final EmbeddingCache embeddingCache;
    private final EmbeddingClient embeddingClient;
    private final XXHash64 xxHash64;
    private final MeterRegistry meterRegistry;
    private final TextNormalizer textNormalizer;

    public EmbeddingService(
            EmbeddingCache embeddingCache,
            EmbeddingClient embeddingClient,
            MeterRegistry meterRegistry,
            TextNormalizer textNormalizer) {
        this.embeddingCache = embeddingCache;
        this.embeddingClient = embeddingClient;
        this.xxHash64 = XXHashFactory.fastestInstance().hash64();
        this.meterRegistry = meterRegistry;
        this.textNormalizer = textNormalizer;
    }

    public EmbeddingResponse getEmbeddings(List<String> inputs, String model) {
        List<String> normalizedInputs = inputs.stream()
                .map(textNormalizer::normalize)
                .toList();

        List<String> cacheKeys = normalizedInputs.stream()
                .map(input -> {
                    byte[] inputBytes = input.getBytes(StandardCharsets.UTF_8);
                    long hash = xxHash64.hash(inputBytes, 0, inputBytes.length, 0);
                    return String.format("%s:%s", model, Long.toHexString(hash));
                })
                .toList();

        List<List<Double>> results = new ArrayList<>(Collections.nCopies(inputs.size(), null));
        List<Integer> missingIndices = new ArrayList<>();
        List<String> missingNormalizedInputs = new ArrayList<>();

        for (int i = 0; i < cacheKeys.size(); i++) {
            String key = cacheKeys.get(i);
            Optional<List<Double>> cachedEmbedding = embeddingCache.get(key);

            if (cachedEmbedding.isPresent()) {
                log.debug("Cache HIT for key: {}", key);
                meterRegistry.counter("embedding.cache.hits", "model", model).increment();
                results.set(i, cachedEmbedding.get());
            } else {
                log.debug("Cache MISS for key: {}", key);
                meterRegistry.counter("embedding.cache.misses", "model", model).increment();
                missingIndices.add(i);
                missingNormalizedInputs.add(normalizedInputs.get(i));
            }
        }

        if (!missingNormalizedInputs.isEmpty()) {
            Timer.Sample sample = Timer.start(meterRegistry);
            List<List<Double>> missingEmbeddings = embeddingClient.generateEmbeddings(missingNormalizedInputs, model);
            sample.stop(meterRegistry.timer("embedding.generation.latency", "model", model));

            if (missingEmbeddings.size() != missingNormalizedInputs.size()) {
                throw new RuntimeException("Provider returned " + missingEmbeddings.size() + 
                    " embeddings for " + missingNormalizedInputs.size() + " inputs");
            }

            for (int i = 0; i < missingIndices.size(); i++) {
                int originalIndex = missingIndices.get(i);
                List<Double> embedding = missingEmbeddings.get(i);
                results.set(originalIndex, embedding);

                String key = cacheKeys.get(originalIndex);
                embeddingCache.put(key, embedding);
                meterRegistry.counter("embedding.generation.count", "model", model).increment();
            }
        }

        List<EmbeddingResponse.EmbeddingData> data = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            data.add(new EmbeddingResponse.EmbeddingData(results.get(i), i));
        }

        return new EmbeddingResponse(model, data);
    }

    public Object getAvailableModels() {
        return embeddingClient.getAvailableModels();
    }

    public Optional<EmbeddingResponse> getCachedEmbedding(String key) {
        return embeddingCache.get(key)
                .map(embedding -> {
                    // Extract model from key (key format is model:hash)
                    String model = key.split(":")[0];
                    List<EmbeddingResponse.EmbeddingData> data = List.of(
                            new EmbeddingResponse.EmbeddingData(embedding, 0)
                    );
                    return new EmbeddingResponse(model, data);
                });
    }
}
