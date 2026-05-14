package org.lareferencia.embedding.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lareferencia.embedding.dto.CreateStoreRequest;
import org.lareferencia.embedding.dto.StoreResponse;
import org.lareferencia.embedding.entity.StoreEntity;
import org.lareferencia.embedding.exception.EmbeddingProviderException;
import org.lareferencia.embedding.exception.InvalidRequestException;
import org.lareferencia.embedding.exception.StoreAlreadyExistsException;
import org.lareferencia.embedding.repository.EmbeddingRepository;
import org.lareferencia.embedding.repository.StoreRepository;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StoreServiceTest {

  private static final UUID STORE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID STORE_ID_2 = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Mock
  private StoreRepository storeRepository;

  @Mock
  private EmbeddingProviderService embeddingProviderService;

  @Mock
  private EmbeddingRepository embeddingRepository;

  private StoreService storeService;

  @BeforeEach
  void setUp() {
    when(storeRepository.findAll()).thenReturn(List.of());
    storeService = new StoreService(storeRepository, embeddingProviderService, embeddingRepository);
    storeService.loadStoresIntoCache();
  }

  @Test
  void createsStoreAfterValidatingModelAndAllocatingEmbeddingTable() {
    when(embeddingProviderService.provider()).thenReturn("openrouter");
    when(embeddingProviderService.resolveDimensions("nomic-embed-text")).thenReturn(768);
    when(storeRepository.save(any(StoreEntity.class))).thenReturn(StoreEntity.builder()
        .id(STORE_ID)
        .name("publications")
        .model("nomic-embed-text")
        .provider("openrouter")
        .embeddingDimensions(768)
        .createdAt(Instant.now())
        .build());

    StoreResponse response = storeService
        .createStore(new CreateStoreRequest("publications", "nomic-embed-text", null));

    assertEquals(STORE_ID, response.id());
    assertEquals("publications", response.name());
    assertEquals("nomic-embed-text", response.model());
    assertEquals("openrouter", response.provider());
    verify(embeddingProviderService).resolveDimensions("nomic-embed-text");
    verify(embeddingRepository).ensureStoreTableExists(STORE_ID, 768, false);
  }

  @Test
  void rejectsDuplicateStoreNames() {
    // Pre-populate cache with an existing store
    when(storeRepository.findAll()).thenReturn(List.of(
        StoreEntity.builder().id(STORE_ID).name("publications").model("nomic-embed-text")
            .provider("openrouter").createdAt(Instant.now()).build()));
    storeService.loadStoresIntoCache();

    assertThrows(StoreAlreadyExistsException.class,
        () -> storeService.createStore(new CreateStoreRequest("publications", "nomic-embed-text", null)));
  }

  @Test
  void doesNotCreateStoreWhenProviderRejectsModel() {
    when(embeddingProviderService.provider()).thenReturn("openrouter");
    when(embeddingProviderService.resolveDimensions("missing-model"))
        .thenThrow(new EmbeddingProviderException("model 'missing-model' not found"));

    assertThrows(EmbeddingProviderException.class,
        () -> storeService.createStore(new CreateStoreRequest("publications", "missing-model", null)));

    verify(storeRepository, never()).save(any(StoreEntity.class));
    verify(embeddingRepository, never()).ensureStoreTableExists(any(UUID.class), any(Integer.class), anyBoolean());
  }

  @Test
  void listsRegisteredStores() {
    when(storeRepository.findAll()).thenReturn(List.of(
        StoreEntity.builder().id(STORE_ID).name("publications").model("nomic-embed-text")
            .provider("openrouter").createdAt(Instant.now()).build(),
        StoreEntity.builder().id(STORE_ID_2).name("repositories").model("mxbai-embed-large")
            .provider("openai").createdAt(Instant.now()).build()));
    storeService.loadStoresIntoCache();

    List<StoreResponse> responses = storeService.listStores();

    assertEquals(2, responses.size());
  }

  @Test
  void persistsEmbeddingDimensionsWhenMissing() {
    StoreEntity store = StoreEntity.builder()
        .id(STORE_ID)
        .name("publications")
        .model("nomic-embed-text")
        .provider("openrouter")
        .build();

    int dimensions = storeService.ensureEmbeddingDimensions(store, 768);

    assertEquals(768, dimensions);
    assertEquals(768, store.getEmbeddingDimensions());
    verify(storeRepository).save(store);
  }

  @Test
  void rejectsMismatchedEmbeddingDimensions() {
    StoreEntity store = StoreEntity.builder()
        .id(STORE_ID)
        .name("publications")
        .model("nomic-embed-text")
        .provider("openrouter")
        .embeddingDimensions(768)
        .build();

    assertThrows(InvalidRequestException.class, () -> storeService.ensureEmbeddingDimensions(store, 1024));
  }
}