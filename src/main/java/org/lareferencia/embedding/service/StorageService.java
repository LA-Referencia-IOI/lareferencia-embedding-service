package org.lareferencia.embedding.service;

import java.util.List;
import java.util.UUID;

import org.lareferencia.embedding.dto.CreateStorageRequest;
import org.lareferencia.embedding.dto.StorageResponse;
import org.lareferencia.embedding.entity.StorageEntity;
import org.lareferencia.embedding.exception.InvalidRequestException;
import org.lareferencia.embedding.exception.StorageAlreadyExistsException;
import org.lareferencia.embedding.exception.StorageNotFoundException;
import org.lareferencia.embedding.repository.EmbeddingRepository;
import org.lareferencia.embedding.repository.StorageRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class StorageService {

  private final StorageRepository storageRepository;
  private final EmbeddingProviderService ollamaEmbeddingService;
  private final EmbeddingRepository embeddingRepository;

  @Transactional
  public StorageResponse createStorage(CreateStorageRequest request) {
    String normalizedName = request.name().trim();
    String normalizedModel = request.model().trim();
    String provider = ollamaEmbeddingService.provider();

    if (storageRepository.existsByNameIgnoreCase(normalizedName)) {
      throw new StorageAlreadyExistsException(normalizedName);
    }

    int embeddingDimensions = ollamaEmbeddingService.resolveDimensions(normalizedModel);

    StorageEntity savedStorage = storageRepository.save(StorageEntity.builder()
        .name(normalizedName)
        .model(normalizedModel)
        .provider(provider)
        .embeddingDimensions(embeddingDimensions)
        .build());
    embeddingRepository.ensureStorageTableExists(savedStorage.getId(), embeddingDimensions);
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
    log.info("Registered embedding dimensions={} for storage id={} name={}", embeddingDimensions, storage.getId(),
        storage.getName());
    return embeddingDimensions;
  }

  @Transactional(readOnly = true)
  public List<StorageResponse> listStorages() {
    return storageRepository.findAll(Sort.by(Sort.Direction.ASC, "id")).stream()
        .map(this::toResponse)
        .toList();
  }

  @Transactional(readOnly = true)
  public StorageEntity getRequiredStorage(UUID storageId) {
    return storageRepository.findById(storageId)
        .orElseThrow(() -> new StorageNotFoundException(storageId));
  }

  private StorageResponse toResponse(StorageEntity storage) {
    return new StorageResponse(storage.getId(), storage.getName(), storage.getModel(), storage.getProvider(),
        storage.getEmbeddingDimensions(),
        storage.getCreatedAt());
  }
}