package org.lareferencia.embedding.service;

import java.util.List;

import org.lareferencia.embedding.dto.CreateStoreRequest;
import org.lareferencia.embedding.dto.StoreResponse;
import org.lareferencia.embedding.entity.EmbeddingStoreEntity;
import org.lareferencia.embedding.exception.InvalidRequestException;
import org.lareferencia.embedding.exception.StoreAlreadyExistsException;
import org.lareferencia.embedding.exception.StoreNotFoundException;
import org.lareferencia.embedding.repository.EmbeddingRepository;
import org.lareferencia.embedding.repository.StoreRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class StoreService {

  private final StoreRepository storeRepository;
  private final OllamaEmbeddingService ollamaEmbeddingService;
  private final EmbeddingRepository embeddingRepository;

  @Transactional
  public StoreResponse createStore(CreateStoreRequest request) {
    String normalizedName = request.name().trim();
    String normalizedModel = request.model().trim();

    if (storeRepository.existsByNameIgnoreCase(normalizedName)) {
      throw new StoreAlreadyExistsException(normalizedName);
    }

    int embeddingDimensions = ollamaEmbeddingService.resolveDimensions(normalizedModel);

    EmbeddingStoreEntity savedStore = storeRepository.save(EmbeddingStoreEntity.builder()
        .name(normalizedName)
        .model(normalizedModel)
        .embeddingDimensions(embeddingDimensions)
        .build());
    embeddingRepository.ensureStoreTableExists(savedStore.getId(), embeddingDimensions);
    log.info("Created embedding store id={} name={} model={}", savedStore.getId(), savedStore.getName(),
        savedStore.getModel());
    return toResponse(savedStore);
  }

  @Transactional
  public int ensureEmbeddingDimensions(EmbeddingStoreEntity store, int embeddingDimensions) {
    if (embeddingDimensions <= 0) {
      throw new InvalidRequestException("Embedding dimensions must be a positive number");
    }

    Integer existingDimensions = store.getEmbeddingDimensions();
    if (existingDimensions != null) {
      if (existingDimensions != embeddingDimensions) {
        throw new InvalidRequestException("Embedding dimensions " + embeddingDimensions
            + " do not match the configured store dimensions " + existingDimensions);
      }
      return existingDimensions;
    }

    store.setEmbeddingDimensions(embeddingDimensions);
    storeRepository.save(store);
    log.info("Registered embedding dimensions={} for store id={} name={}", embeddingDimensions, store.getId(),
        store.getName());
    return embeddingDimensions;
  }

  @Transactional(readOnly = true)
  public List<StoreResponse> listStores() {
    return storeRepository.findAll(Sort.by(Sort.Direction.ASC, "id")).stream()
        .map(this::toResponse)
        .toList();
  }

  @Transactional(readOnly = true)
  public EmbeddingStoreEntity getRequiredStore(Long storeId) {
    return storeRepository.findById(storeId)
        .orElseThrow(() -> new StoreNotFoundException(storeId));
  }

  private StoreResponse toResponse(EmbeddingStoreEntity store) {
    return new StoreResponse(store.getId(), store.getName(), store.getModel(), store.getEmbeddingDimensions(),
        store.getCreatedAt());
  }
}