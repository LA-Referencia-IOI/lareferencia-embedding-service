package org.lareferencia.embedding.service;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import jakarta.annotation.PostConstruct;

import org.lareferencia.embedding.dto.CreateStorageRequest;
import org.lareferencia.embedding.dto.StorageResponse;
import org.lareferencia.embedding.entity.StorageEntity;
import org.lareferencia.embedding.exception.InvalidRequestException;
import org.lareferencia.embedding.exception.StorageAlreadyExistsException;
import org.lareferencia.embedding.exception.StorageNotFoundException;
import org.lareferencia.embedding.repository.EmbeddingRepository;
import org.lareferencia.embedding.repository.StorageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class StorageService {

  private final StorageRepository storageRepository;
  private final EmbeddingProviderService embeddingProviderService;
  private final EmbeddingRepository embeddingRepository;

  private final ConcurrentMap<UUID, StorageEntity> storageCache = new ConcurrentHashMap<>();

  @PostConstruct
  void loadStoragesIntoCache() {
    storageRepository.findAll().forEach(s -> storageCache.put(s.getId(), s));
    log.info("Loaded {} storage(s) into memory cache", storageCache.size());
  }

  @Transactional
  public StorageResponse createStorage(CreateStorageRequest request) {
    String normalizedName = request.name().trim();
    String normalizedModel = request.model().trim();
    String provider = embeddingProviderService.provider();

    boolean nameAlreadyExists = storageCache.values().stream()
        .anyMatch(s -> s.getName().equalsIgnoreCase(normalizedName));
    if (nameAlreadyExists) {
      throw new StorageAlreadyExistsException(normalizedName);
    }

    int embeddingDimensions = embeddingProviderService.resolveDimensions(normalizedModel);

    StorageEntity savedStorage = storageRepository.save(StorageEntity.builder()
        .name(normalizedName)
        .model(normalizedModel)
        .provider(provider)
        .embeddingDimensions(embeddingDimensions)
        .build());
    embeddingRepository.ensureStorageTableExists(savedStorage.getId(), embeddingDimensions);
    storageCache.put(savedStorage.getId(), savedStorage);
    log.info("Created embedding storage id={} name={} model={}", savedStorage.getId(), savedStorage.getName(),
        savedStorage.getModel());
    return toResponse(savedStorage);
  }

  @Transactional
  public int ensureEmbeddingDimensions(StorageEntity storage, int embeddingDimensions) {
    if (embeddingDimensions <= 0) {
      throw new InvalidRequestException("Embedding dimensions must be a positive number");
    }

    Integer existingDimensions = storage.getEmbeddingDimensions();
    if (existingDimensions != null) {
      if (existingDimensions != embeddingDimensions) {
        throw new InvalidRequestException("Embedding dimensions " + embeddingDimensions
            + " do not match the configured storage dimensions " + existingDimensions);
      }
      return existingDimensions;
    }

    storage.setEmbeddingDimensions(embeddingDimensions);
    storageRepository.save(storage);
    // storage is the same reference stored in the cache, so the cache is already
    // updated
    log.info("Registered embedding dimensions={} for storage id={} name={}", embeddingDimensions, storage.getId(),
        storage.getName());
    return embeddingDimensions;
  }

  public List<StorageResponse> listStorages() {
    return storageCache.values().stream()
        .sorted(Comparator.comparing(StorageEntity::getId))
        .map(this::toResponse)
        .toList();
  }

  public StorageEntity getRequiredStorage(UUID storageId) {
    StorageEntity storage = storageCache.get(storageId);
    if (storage == null) {
      throw new StorageNotFoundException(storageId);
    }
    return storage;
  }

  private StorageResponse toResponse(StorageEntity storage) {
    return new StorageResponse(storage.getId(), storage.getName(), storage.getModel(), storage.getProvider(),
        storage.getEmbeddingDimensions(),
        storage.getCreatedAt());
  }
}