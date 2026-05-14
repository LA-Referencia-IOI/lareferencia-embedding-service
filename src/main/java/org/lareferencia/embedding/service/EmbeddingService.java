package org.lareferencia.embedding.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.lareferencia.embedding.config.ApplicationProperties;
import org.lareferencia.embedding.dto.GenerateEmbeddingsRequest;
import org.lareferencia.embedding.dto.GenerateEmbeddingsResponse;
import org.lareferencia.embedding.entity.StorageEntity;
import org.lareferencia.embedding.exception.EmbeddingProviderException;
import org.lareferencia.embedding.exception.InvalidRequestException;
import org.lareferencia.embedding.repository.EmbeddingRepository;
import org.lareferencia.embedding.util.PerKeyLockManager;
import org.lareferencia.embedding.util.TextNormalizer;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {

    private final StorageService storageService;
    private final EmbeddingRepository embeddingRepository;
    private final HashService hashService;
    private final EmbeddingProviderService embeddingProviderService;
    private final MeterRegistry meterRegistry;
    private final TextNormalizer textNormalizer;
    private final ApplicationProperties applicationProperties;
    private final PerKeyLockManager perKeyLockManager;

    public GenerateEmbeddingsResponse generateEmbeddings(GenerateEmbeddingsRequest request) {
        List<String> inputs = validateAndExtractInputs(request);
        StorageEntity storage = storageService.getRequiredStorage(request.model());
        Counter cacheHitCounter = cacheHitCounter(storage);
        Counter cacheMissCounter = cacheMissCounter(storage);
        Counter generationCounter = generationCounter(storage);
        Timer generationLatencyTimer = generationLatencyTimer(storage);

        log.info("Embedding request started storageId={} storageName={} model={} inputs={}",
                storage.getId(),
                storage.getName(),
                storage.getModel(),
                inputs.size());
        if (storage.getEmbeddingDimensions() != null) {
            embeddingRepository.ensureStorageTableExists(storage.getId(), storage.getEmbeddingDimensions(), false);
        }

        List<String> normalizedInputs = inputs.stream()
                .map(textNormalizer::normalize)
                .toList();

        List<String> hashes = normalizedInputs.stream()
                .map(hashService::hash)
                .toList();

        Map<String, float[]> cachedEmbeddings = embeddingRepository.findByHashes(storage.getId(),
                storage.getEmbeddingDimensions(),
                new LinkedHashSet<>(hashes));
        String provider = embeddingProviderService.provider();
        GenerateEmbeddingsResponse.Usage usage = new GenerateEmbeddingsResponse.Usage(0, 0);
        List<float[]> results = new ArrayList<>(Collections.nCopies(inputs.size(), null));
        LinkedHashMap<String, String> missingByHash = new LinkedHashMap<>();
        int cacheHits = 0;
        int cacheMisses = 0;

        for (int index = 0; index < hashes.size(); index++) {
            String hash = hashes.get(index);
            float[] cachedEmbedding = cachedEmbeddings.get(hash);
            if (cachedEmbedding != null) {
                cacheHitCounter.increment();
                cacheHits++;
                results.set(index, cachedEmbedding);
            } else {
                cacheMissCounter.increment();
                cacheMisses++;
                missingByHash.putIfAbsent(hash, normalizedInputs.get(index));
            }
        }

        log.info("Cache lookup completed storageId={} model={} hits={} misses={} uniqueMisses={}",
                storage.getId(),
                storage.getModel(),
                cacheHits,
                cacheMisses,
                missingByHash.size());

        if (!missingByHash.isEmpty()) {
            try (PerKeyLockManager.LockSet ignored = perKeyLockManager
                    .lockAll(lockKeys(storage.getId(), missingByHash.keySet()))) {
                log.debug("Rechecking cache after lock storageId={} model={} hashes={}",
                        storage.getId(),
                        storage.getModel(),
                        missingByHash.size());
                Map<String, float[]> cachedAfterLock = embeddingRepository.findByHashes(storage.getId(),
                        storage.getEmbeddingDimensions(),
                        missingByHash.keySet());
                LinkedHashMap<String, String> unresolvedByHash = new LinkedHashMap<>();
                Map<String, float[]> resolvedEmbeddings = new LinkedHashMap<>(cachedAfterLock);

                for (Map.Entry<String, String> entry : missingByHash.entrySet()) {
                    if (!cachedAfterLock.containsKey(entry.getKey())) {
                        unresolvedByHash.put(entry.getKey(), entry.getValue());
                    }
                }

                log.debug("Post-lock cache lookup completed storageId={} model={} resolvedAfterLock={} unresolved={}",
                        storage.getId(),
                        storage.getModel(),
                        cachedAfterLock.size(),
                        unresolvedByHash.size());

                if (!unresolvedByHash.isEmpty()) {
                    log.info("Fetching embeddings from external provider storageId={} model={} inputs={}",
                            storage.getId(),
                            storage.getModel(),
                            unresolvedByHash.size());
                    Timer.Sample sample = Timer.start(meterRegistry);
                    EmbeddingProviderService.EmbeddingBatchResult generatedBatch = embeddingProviderService
                            .embed(List.copyOf(unresolvedByHash.values()), storage.getModel());
                    sample.stop(generationLatencyTimer);

                    provider = generatedBatch.provider();
                    usage = addUsage(usage, generatedBatch.usage());

                    List<float[]> generatedEmbeddings = generatedBatch.embeddings();

                    int embeddingDimensions = validateAndResolveGeneratedDimensions(generatedEmbeddings,
                            unresolvedByHash.size(), storage);
                    embeddingDimensions = storageService.ensureEmbeddingDimensions(storage, embeddingDimensions);

                    LinkedHashMap<String, float[]> generatedByHash = new LinkedHashMap<>();
                    int generatedIndex = 0;
                    for (String hash : unresolvedByHash.keySet()) {
                        generatedByHash.put(hash, generatedEmbeddings.get(generatedIndex++));
                        generationCounter.increment();
                    }
                    log.debug("Persisting generated embeddings storageId={} model={} generated={}",
                            storage.getId(),
                            storage.getModel(),
                            generatedByHash.size());
                    embeddingRepository.saveEmbeddings(storage.getId(), embeddingDimensions, generatedByHash);
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
            data.add(new GenerateEmbeddingsResponse.EmbeddingData("embedding", index, toDoubleList(embedding)));
        }

        log.info("Embedding request finished storageId={} model={} outputs={}",
                storage.getId(),
                storage.getModel(),
                data.size());

        return new GenerateEmbeddingsResponse(
                storage.getId(),
                storage.getName(),
                storage.getModel(),
                provider,
                "list",
                data,
                usage);
    }

    private GenerateEmbeddingsResponse.Usage addUsage(
            GenerateEmbeddingsResponse.Usage existing,
            EmbeddingProviderService.UsageData delta) {
        return new GenerateEmbeddingsResponse.Usage(
                existing.promptTokens() + delta.promptTokens(),
                existing.totalTokens() + delta.totalTokens());
    }

    private int validateAndResolveGeneratedDimensions(
            List<float[]> generatedEmbeddings,
            int expectedSize,
            StorageEntity storage) {
        if (generatedEmbeddings.size() != expectedSize) {
            throw new EmbeddingProviderException(
                    "Provider returned " + generatedEmbeddings.size() + " embeddings for " + expectedSize + " inputs");
        }

        int expectedDimensions = -1;
        for (int index = 0; index < generatedEmbeddings.size(); index++) {
            float[] embedding = generatedEmbeddings.get(index);
            if (embedding == null || embedding.length == 0) {
                throw new EmbeddingProviderException(
                        "Provider returned an empty embedding at index " + index + " for storage " + storage.getName());
            }
            if (expectedDimensions < 0) {
                expectedDimensions = embedding.length;
                continue;
            }
            if (embedding.length != expectedDimensions) {
                throw new EmbeddingProviderException(
                        "Provider returned inconsistent embedding dimensions: " + embedding.length
                                + " at index " + index + " (expected " + expectedDimensions + ")");
            }
        }

        return expectedDimensions;
    }

    private List<String> validateAndExtractInputs(GenerateEmbeddingsRequest request) {
        if (request.model() == null) {
            throw new InvalidRequestException("model (storageId) must be provided");
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

    private Collection<String> lockKeys(UUID storageId, Collection<String> hashes) {
        return hashes.stream().map(hash -> storageId + ":" + hash).toList();
    }

    private List<Double> toDoubleList(float[] embedding) {
        List<Double> values = new ArrayList<>(embedding.length);
        for (float value : embedding) {
            values.add((double) value);
        }
        return values;
    }

    private Counter cacheHitCounter(StorageEntity storage) {
        return meterRegistry.counter("embedding.cache.hits", "storage", storage.getName(), "model", storage.getModel());
    }

    private Counter cacheMissCounter(StorageEntity storage) {
        return meterRegistry.counter("embedding.cache.misses", "storage", storage.getName(), "model",
                storage.getModel());
    }

    private Counter generationCounter(StorageEntity storage) {
        return meterRegistry.counter("embedding.generation.count", "storage", storage.getName(), "model",
                storage.getModel());
    }

    private Timer generationLatencyTimer(StorageEntity storage) {
        return meterRegistry.timer("embedding.generation.latency", "storage", storage.getName(), "model",
                storage.getModel());
    }
}
