package org.lareferencia.embedding.service;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import jakarta.annotation.PostConstruct;

import org.lareferencia.embedding.dto.CreateStoreRequest;
import org.lareferencia.embedding.dto.StoreResponse;
import org.lareferencia.embedding.entity.StoreEntity;
import org.lareferencia.embedding.exception.InvalidRequestException;
import org.lareferencia.embedding.exception.StoreAlreadyExistsException;
import org.lareferencia.embedding.exception.StoreNotFoundException;
import org.lareferencia.embedding.repository.EmbeddingRepository;
import org.lareferencia.embedding.repository.StoreRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class StoreService {

  private final StoreRepository storeRepository;
  private final EmbeddingProviderService embeddingProviderService;
  private final EmbeddingRepository embeddingRepository;

  private final ConcurrentMap<UUID, StoreEntity> storeCache = new ConcurrentHashMap<>();

  @PostConstruct
  void loadStoresIntoCache() {
    storeRepository.findAll().forEach(s -> storeCache.put(s.getId(), s));
    log.info("Loaded {} store(s) into memory cache", storeCache.size());
  }

  @Transactional
  public StoreResponse createStore(CreateStoreRequest request) {
    String normalizedName = request.name().trim();
    String normalizedModel = request.model().trim();
    String provider = embeddingProviderService.provider();

    boolean nameAlreadyExists = storeCache.values().stream()
        .anyMatch(s -> s.getName().equalsIgnoreCase(normalizedName));
    if (nameAlreadyExists) {
      throw new StoreAlreadyExistsException(normalizedName);
    }

    int embeddingDimensions = embeddingProviderService.resolveDimensions(normalizedModel);

    StoreEntity savedStore = storeRepository.save(StoreEntity.builder()
        .name(normalizedName)
        .model(normalizedModel)
        .provider(provider)
        .embeddingDimensions(embeddingDimensions)
        .build());
    embeddingRepository.ensureStoreTableExists(savedStore.getId(), embeddingDimensions,
        Boolean.TRUE.equals(request.createVectorIndex()));
    storeCache.put(savedStore.getId(), savedStore);
    log.info("Created embedding store id={} name={} model={}", savedStore.getId(), savedStore.getName(),
        savedStore.getModel());
    return toResponse(savedStore);
  }

  @Transactional
  public int ensureEmbeddingDimensions(StoreEntity store, int embeddingDimensions) {
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
    // store is the same reference stored in the cache, so the cache is already
    // updated
    log.info("Registered embedding dimensions={} for store id={} name={}", embeddingDimensions, store.getId(),
        store.getName());
    return embeddingDimensions;
  }

  public List<StoreResponse> listStores() {
    return storeCache.values().stream()
        .sorted(Comparator.comparing(StoreEntity::getId))
        .map(this::toResponse)
        .toList();
  }

  public StoreEntity getRequiredStore(UUID storeId) {
    StoreEntity store = storeCache.get(storeId);
    if (store == null) {
      throw new StoreNotFoundException(storeId);
    }
    return store;
  }

  private StoreResponse toResponse(StoreEntity store) {
    return new StoreResponse(store.getId(), store.getName(), store.getModel(), store.getProvider(),
        store.getEmbeddingDimensions(),
        store.getCreatedAt());
  }
}