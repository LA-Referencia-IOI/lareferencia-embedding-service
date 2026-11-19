package org.lareferencia.embedding.v1.service;

import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.lareferencia.embedding.v1.dto.CreateStoreRequest;
import org.lareferencia.embedding.v1.dto.StoreResponse;
import org.lareferencia.embedding.v1.entity.StoreEntity;
import org.lareferencia.embedding.v1.exception.InvalidRequestException;
import org.lareferencia.embedding.v1.exception.StoreAlreadyExistsException;
import org.lareferencia.embedding.v1.exception.StoreNotFoundException;
import org.lareferencia.embedding.v1.repository.EmbeddingRepository;
import org.lareferencia.embedding.v1.repository.StoreRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StoreService {
  private final StoreRepository storeRepository;
  private final EmbeddingProviderService embeddingProviderService;
  private final EmbeddingRepository embeddingRepository;

  @Transactional
  public StoreResponse createStore(CreateStoreRequest request) {
    String name = request.name().trim();
    String model = request.model().trim();
    if (storeRepository.nameExists(name)) {
      throw new StoreAlreadyExistsException(name);
    }
    // Database uniqueness, not this preflight, serializes competing creations.
    StoreEntity store =
        storeRepository.saveAndFlush(
            StoreEntity.builder()
                .name(name)
                .model(model)
                .provider(embeddingProviderService.provider())
                .embeddingDimension(request.dimensions())
                .build());
    embeddingRepository.ensureStoreTableExists(
        store.getId(), request.dimensions(), Boolean.TRUE.equals(request.createVectorIndex()));
    return toResponse(store);
  }

  @Transactional
  public int ensureEmbeddingDimensions(StoreEntity store, int dimensions) {
    if (dimensions <= 0) {
      throw new InvalidRequestException("Embedding dimensions must be a positive number");
    }
    if (store.getEmbeddingDimension() != null && store.getEmbeddingDimension() != dimensions) {
      throw new InvalidRequestException(
          "Embedding dimensions "
              + dimensions
              + " do not match the configured store dimensions "
              + store.getEmbeddingDimension());
    }
    if (store.getEmbeddingDimension() == null) {
      store.setEmbeddingDimension(dimensions);
      storeRepository.save(store);
    }
    return dimensions;
  }

  public List<StoreResponse> listStores() {
    return storeRepository.findAll().stream()
        .sorted(Comparator.comparing(StoreEntity::getId))
        .map(this::toResponse)
        .toList();
  }

  public StoreEntity getRequiredStore(String name) {
    return storeRepository.findByName(name).orElseThrow(() -> new StoreNotFoundException(name));
  }

  public StoreEntity getRequiredStore(String name, int dimensions) {
    return storeRepository
        .findByNameAndDimensions(name, dimensions)
        .orElseThrow(() -> new StoreNotFoundException(name, dimensions));
  }

  private StoreResponse toResponse(StoreEntity store) {
    return new StoreResponse(
        store.getId(),
        store.getName(),
        store.getModel(),
        store.getProvider(),
        store.getEmbeddingDimension(),
        store.getCreatedAt());
  }
}
