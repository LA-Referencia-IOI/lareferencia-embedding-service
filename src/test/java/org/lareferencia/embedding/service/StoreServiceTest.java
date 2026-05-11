package org.lareferencia.embedding.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lareferencia.embedding.dto.CreateStoreRequest;
import org.lareferencia.embedding.dto.StoreResponse;
import org.lareferencia.embedding.entity.EmbeddingStoreEntity;
import org.lareferencia.embedding.exception.EmbeddingProviderException;
import org.lareferencia.embedding.exception.InvalidRequestException;
import org.lareferencia.embedding.exception.StoreAlreadyExistsException;
import org.lareferencia.embedding.repository.EmbeddingRepository;
import org.lareferencia.embedding.repository.StoreRepository;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StoreServiceTest {

  @Mock
  private StoreRepository storeRepository;

  @Mock
  private OllamaEmbeddingService ollamaEmbeddingService;

  @Mock
  private EmbeddingRepository embeddingRepository;

  private StoreService storeService;

  @BeforeEach
  void setUp() {
    storeService = new StoreService(storeRepository, ollamaEmbeddingService, embeddingRepository);
  }

  @Test
  void createsStoreAfterValidatingModelAndAllocatingEmbeddingTable() {
    when(storeRepository.existsByNameIgnoreCase("publications")).thenReturn(false);
    when(ollamaEmbeddingService.resolveDimensions("nomic-embed-text")).thenReturn(768);
    when(storeRepository.save(any(EmbeddingStoreEntity.class))).thenReturn(EmbeddingStoreEntity.builder()
        .id(7L)
        .name("publications")
        .model("nomic-embed-text")
        .embeddingDimensions(768)
        .createdAt(Instant.now())
        .build());

    StoreResponse response = storeService.createStore(new CreateStoreRequest("publications", "nomic-embed-text"));

    assertEquals(7L, response.id());
    assertEquals("publications", response.name());
    assertEquals("nomic-embed-text", response.model());
    verify(ollamaEmbeddingService).resolveDimensions("nomic-embed-text");
    verify(embeddingRepository).ensureStoreTableExists(7L, 768);
  }

  @Test
  void rejectsDuplicateStoreNames() {
    when(storeRepository.existsByNameIgnoreCase("publications")).thenReturn(true);

    assertThrows(StoreAlreadyExistsException.class,
        () -> storeService.createStore(new CreateStoreRequest("publications", "nomic-embed-text")));
  }

  @Test
  void doesNotCreateStoreWhenProviderRejectsModel() {
    when(storeRepository.existsByNameIgnoreCase("publications")).thenReturn(false);
    when(ollamaEmbeddingService.resolveDimensions("missing-model"))
        .thenThrow(new EmbeddingProviderException("model 'missing-model' not found"));

    assertThrows(EmbeddingProviderException.class,
        () -> storeService.createStore(new CreateStoreRequest("publications", "missing-model")));

    verify(storeRepository, never()).save(any(EmbeddingStoreEntity.class));
    verify(embeddingRepository, never()).ensureStoreTableExists(any(Long.class), any(Integer.class));
  }

  @Test
  void listsRegisteredStores() {
    when(storeRepository.findAll(any(org.springframework.data.domain.Sort.class))).thenReturn(List.of(
        EmbeddingStoreEntity.builder().id(1L).name("publications").model("nomic-embed-text").createdAt(Instant.now())
            .build(),
        EmbeddingStoreEntity.builder().id(2L).name("repositories").model("mxbai-embed-large").createdAt(Instant.now())
            .build()));

    List<StoreResponse> responses = storeService.listStores();

    assertEquals(2, responses.size());
    assertEquals("publications", responses.get(0).name());
    assertEquals("repositories", responses.get(1).name());
  }

  @Test
  void persistsEmbeddingDimensionsWhenMissing() {
    EmbeddingStoreEntity store = EmbeddingStoreEntity.builder()
        .id(7L)
        .name("publications")
        .model("nomic-embed-text")
        .build();

    int dimensions = storeService.ensureEmbeddingDimensions(store, 768);

    assertEquals(768, dimensions);
    assertEquals(768, store.getEmbeddingDimensions());
    verify(storeRepository).save(store);
  }

  @Test
  void rejectsMismatchedEmbeddingDimensions() {
    EmbeddingStoreEntity store = EmbeddingStoreEntity.builder()
        .id(7L)
        .name("publications")
        .model("nomic-embed-text")
        .embeddingDimensions(768)
        .build();

    assertThrows(InvalidRequestException.class, () -> storeService.ensureEmbeddingDimensions(store, 1024));
  }
}