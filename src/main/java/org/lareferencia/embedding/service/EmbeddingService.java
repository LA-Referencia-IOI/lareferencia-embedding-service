package org.lareferencia.embedding.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.lareferencia.embedding.config.ApplicationProperties;
import org.lareferencia.embedding.dto.GenerateEmbeddingsRequest;
import org.lareferencia.embedding.dto.GenerateEmbeddingsResponse;
import org.lareferencia.embedding.entity.EmbeddingStoreEntity;
import org.lareferencia.embedding.exception.EmbeddingProviderException;
import org.lareferencia.embedding.exception.InvalidRequestException;
import org.lareferencia.embedding.repository.EmbeddingRepository;
import org.lareferencia.embedding.util.PerKeyLockManager;
import org.lareferencia.embedding.util.TextNormalizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {

    private final StoreService storeService;
    private final EmbeddingRepository embeddingRepository;
    private final HashService hashService;
    private final EmbeddingProviderRegistry embeddingProviderRegistry;
    private final MeterRegistry meterRegistry;
    private final TextNormalizer textNormalizer;
    private final ApplicationProperties applicationProperties;
    private final PerKeyLockManager perKeyLockManager;

    @Transactional
    public GenerateEmbeddingsResponse generateEmbeddings(GenerateEmbeddingsRequest request) {
        List<String> inputs = validateAndExtractInputs(request);
        EmbeddingStoreEntity store = storeService.getRequiredStore(request.store());
        log.info("Embedding request started storeId={} storeName={} model={} inputs={}",
                store.getId(),
                store.getName(),
                store.getModel(),
                inputs.size());
        if (store.getEmbeddingDimensions() != null) {
            embeddingRepository.ensureStoreTableExists(store.getId(), store.getEmbeddingDimensions());
        }

        List<String> normalizedInputs = inputs.stream()
                .map(textNormalizer::normalize)
                .toList();

        List<String> hashes = normalizedInputs.stream()
                .map(hashService::hash)
                .toList();

        Map<String, float[]> cachedEmbeddings = embeddingRepository.findByHashes(store.getId(),
                store.getEmbeddingDimensions(),
                new LinkedHashSet<>(hashes));
        List<float[]> results = new ArrayList<>(Collections.nCopies(inputs.size(), null));
        LinkedHashMap<String, String> missingByHash = new LinkedHashMap<>();
        int cacheHits = 0;
        int cacheMisses = 0;

        for (int index = 0; index < hashes.size(); index++) {
            String hash = hashes.get(index);
            float[] cachedEmbedding = cachedEmbeddings.get(hash);
            if (cachedEmbedding != null) {
                cacheHitCounter(store).increment();
                cacheHits++;
                results.set(index, cachedEmbedding);
            } else {
                cacheMissCounter(store).increment();
                cacheMisses++;
                missingByHash.putIfAbsent(hash, normalizedInputs.get(index));
            }
        }

        log.info("Cache lookup completed storeId={} model={} hits={} misses={} uniqueMisses={}",
                store.getId(),
                store.getModel(),
                cacheHits,
                cacheMisses,
                missingByHash.size());

        if (!missingByHash.isEmpty()) {
            try (PerKeyLockManager.LockSet ignored = perKeyLockManager
                    .lockAll(lockKeys(store.getId(), missingByHash.keySet()))) {
                log.debug("Rechecking cache after lock storeId={} model={} hashes={}",
                        store.getId(),
                        store.getModel(),
                        missingByHash.size());
                Map<String, float[]> cachedAfterLock = embeddingRepository.findByHashes(store.getId(),
                        store.getEmbeddingDimensions(),
                        missingByHash.keySet());
                LinkedHashMap<String, String> unresolvedByHash = new LinkedHashMap<>();
                Map<String, float[]> resolvedEmbeddings = new LinkedHashMap<>(cachedAfterLock);

                for (Map.Entry<String, String> entry : missingByHash.entrySet()) {
                    if (!cachedAfterLock.containsKey(entry.getKey())) {
                        unresolvedByHash.put(entry.getKey(), entry.getValue());
                    }
                }

                log.debug("Post-lock cache lookup completed storeId={} model={} resolvedAfterLock={} unresolved={}",
                        store.getId(),
                        store.getModel(),
                        cachedAfterLock.size(),
                        unresolvedByHash.size());

                if (!unresolvedByHash.isEmpty()) {
                    log.info("Fetching embeddings from external provider storeId={} model={} inputs={}",
                            store.getId(),
                            store.getModel(),
                            unresolvedByHash.size());
                    Timer.Sample sample = Timer.start(meterRegistry);
                    List<float[]> generatedEmbeddings = embeddingProviderRegistry.getDefaultProvider()
                            .embed(List.copyOf(unresolvedByHash.values()), store.getModel());
                    sample.stop(generationLatencyTimer(store));

                    if (generatedEmbeddings.size() != unresolvedByHash.size()) {
                        throw new EmbeddingProviderException(
                                "Ollama returned " + generatedEmbeddings.size() + " embeddings for "
                                        + unresolvedByHash.size() + " inputs");
                    }

                    int embeddingDimensions = storeService.ensureEmbeddingDimensions(store,
                            generatedEmbeddings.get(0).length);

                    LinkedHashMap<String, float[]> generatedByHash = new LinkedHashMap<>();
                    int generatedIndex = 0;
                    for (String hash : unresolvedByHash.keySet()) {
                        generatedByHash.put(hash, generatedEmbeddings.get(generatedIndex++));
                        generationCounter(store).increment();
                    }
                    log.debug("Persisting generated embeddings storeId={} model={} generated={}",
                            store.getId(),
                            store.getModel(),
                            generatedByHash.size());
                    embeddingRepository.saveEmbeddings(store.getId(), embeddingDimensions, generatedByHash);
                    resolvedEmbeddings.putAll(generatedByHash);
                }

                for (int index = 0; index < hashes.size(); index++) {
                    if (results.get(index) == null) {
                        float[] embedding = resolvedEmbeddings.get(hashes.get(index));
                        if (embedding == null) {
                            throw new IllegalStateException(
                                    "Embedding resolution failed for hash " + hashes.get(index));
                        }
                        results.set(index, embedding);
                    }
                }
            }
        }

        List<GenerateEmbeddingsResponse.EmbeddingData> data = new ArrayList<>(results.size());
        for (int index = 0; index < results.size(); index++) {
            float[] embedding = results.get(index);
            if (embedding == null) {
                throw new IllegalStateException("Embedding result is missing at index " + index);
            }
            data.add(new GenerateEmbeddingsResponse.EmbeddingData(index, toDoubleList(embedding)));
        }

        log.info("Embedding request finished storeId={} model={} outputs={}",
                store.getId(),
                store.getModel(),
                data.size());

        return new GenerateEmbeddingsResponse(store.getId(), store.getName(), store.getModel(), data);
    }

    private List<String> validateAndExtractInputs(GenerateEmbeddingsRequest request) {
        if (request.store() == null || request.store() <= 0) {
            throw new InvalidRequestException("storeId must be a positive number");
        }

        List<String> inputs = request.inputs();
        if (inputs.isEmpty()) {
            throw new InvalidRequestException("input must be a string or an array of strings");
        }
        if (inputs.size() > applicationProperties.getMaxBatchSize()) {
            throw new InvalidRequestException(
                    "input batch size exceeds the configured maximum of " + applicationProperties.getMaxBatchSize());
        }

        List<String> sanitizedInputs = new ArrayList<>(inputs.size());
        for (String input : inputs) {
            if (input == null || input.isBlank()) {
                throw new InvalidRequestException("input text must not be blank");
            }
            if (input.length() > applicationProperties.getMaxInputLength()) {
                throw new InvalidRequestException("input text exceeds the configured maximum length of "
                        + applicationProperties.getMaxInputLength());
            }
            sanitizedInputs.add(input);
        }
        return sanitizedInputs;
    }

    private Collection<String> lockKeys(Long storeId, Collection<String> hashes) {
        return hashes.stream().map(hash -> storeId + ":" + hash).toList();
    }

    private List<Double> toDoubleList(float[] embedding) {
        List<Double> values = new ArrayList<>(embedding.length);
        for (float value : embedding) {
            values.add((double) value);
        }
        return values;
    }

    private Counter cacheHitCounter(EmbeddingStoreEntity store) {
        return meterRegistry.counter("embedding.cache.hits", "store", store.getName(), "model", store.getModel());
    }

    private Counter cacheMissCounter(EmbeddingStoreEntity store) {
        return meterRegistry.counter("embedding.cache.misses", "store", store.getName(), "model", store.getModel());
    }

    private Counter generationCounter(EmbeddingStoreEntity store) {
        return meterRegistry.counter("embedding.generation.count", "store", store.getName(), "model", store.getModel());
    }

    private Timer generationLatencyTimer(EmbeddingStoreEntity store) {
        return meterRegistry.timer("embedding.generation.latency", "store", store.getName(), "model", store.getModel());
    }
}
