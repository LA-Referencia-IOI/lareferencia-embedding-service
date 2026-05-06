package org.lareferencia.embedding.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import net.jpountz.xxhash.XXHash64;
import net.jpountz.xxhash.XXHashFactory;
import org.lareferencia.embedding.cache.EmbeddingCache;
import org.lareferencia.embedding.client.EmbeddingClient;
import org.lareferencia.embedding.dto.EmbeddingResponse;
import org.lareferencia.embedding.util.EmbeddingSerializationUtil;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class EmbeddingService {

    private final EmbeddingCache embeddingCache;
    private final EmbeddingClient embeddingClient;
    private final XXHash64 xxHash64;
    private final MeterRegistry meterRegistry;

    public EmbeddingService(
            EmbeddingCache embeddingCache,
            EmbeddingClient embeddingClient,
            MeterRegistry meterRegistry) {
        this.embeddingCache = embeddingCache;
        this.embeddingClient = embeddingClient;
        this.xxHash64 = XXHashFactory.fastestInstance().hash64();
        this.meterRegistry = meterRegistry;
    }

    public EmbeddingResponse getEmbedding(String input, String model) {
        byte[] inputBytes = input.getBytes(StandardCharsets.UTF_8);
        long hash = xxHash64.hash(inputBytes, 0, inputBytes.length, 0);
        String cacheKey = String.format("embedding:%s:%s", model, Long.toHexString(hash));

        Optional<byte[]> cachedData = embeddingCache.get(cacheKey);

        if (cachedData.isPresent()) {
            log.info("Cache HIT for key: {}", cacheKey);
            meterRegistry.counter("embedding.cache.hits", "model", model).increment();
            List<Double> embedding = EmbeddingSerializationUtil.deserialize(cachedData.get());
            return new EmbeddingResponse(model, embedding);
        }

        log.info("Cache MISS for key: {}", cacheKey);
        meterRegistry.counter("embedding.cache.misses", "model", model).increment();

        Timer.Sample sample = Timer.start(meterRegistry);
        List<Double> embedding = embeddingClient.generateEmbedding(input, model);
        sample.stop(meterRegistry.timer("embedding.generation.latency", "model", model));

        meterRegistry.counter("embedding.generation.count", "model", model).increment();

        byte[] binaryData = EmbeddingSerializationUtil.serialize(embedding);
        embeddingCache.put(cacheKey, binaryData);

        return new EmbeddingResponse(model, embedding);
    }
}
