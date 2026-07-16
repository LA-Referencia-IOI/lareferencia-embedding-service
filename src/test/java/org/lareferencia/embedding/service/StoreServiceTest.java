package org.lareferencia.embedding.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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
    when(storeRepository.save(any(StoreEntity.class))).thenReturn(StoreEntity.builder()
        .id(STORE_ID)
        .name("publications")
        .model("nomic-embed-text")
        .provider("openrouter")
        .embeddingDimension(768)
        .createdAt(Instant.now())
        .build());

    StoreResponse response = storeService
        .createStore(new CreateStoreRequest("publications", "nomic-embed-text", 768, false));

    assertEquals(STORE_ID, response.id());
    assertEquals("publications", response.name());
    assertEquals("nomic-embed-text", response.model());
    assertEquals("openrouter", response.provider());
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
        () -> storeService.createStore(new CreateStoreRequest("publications", "nomic-embed-text", 768, false)));
  }

  @Test
  void rejectsDuplicateModelAndDimension() {
    when(storeRepository.findAll()).thenReturn(List.of(
        StoreEntity.builder().id(STORE_ID).name("publications").model("nomic-embed-text")
            .provider("openrouter").embeddingDimension(768).createdAt(Instant.now()).build()));
    storeService.loadStoresIntoCache();

    assertThrows(StoreAlreadyExistsException.class,
        () -> storeService.createStore(new CreateStoreRequest("repositories", "nomic-embed-text", 768, false)));
  }

  @Test
  void allowsSameModelWithDifferentDimensions() {
    when(storeRepository.findAll()).thenReturn(List.of(
        StoreEntity.builder().id(STORE_ID).name("publications-768").model("nomic-embed-text")
            .provider("openrouter").embeddingDimension(768).createdAt(Instant.now()).build()));
    storeService.loadStoresIntoCache();
    when(embeddingProviderService.provider()).thenReturn("openrouter");
    when(storeRepository.save(any(StoreEntity.class))).thenReturn(StoreEntity.builder()
        .id(STORE_ID_2)
        .name("publications-1024")
        .model("nomic-embed-text")
        .provider("openrouter")
        .embeddingDimension(1024)
        .createdAt(Instant.now())
        .build());

    StoreResponse response = storeService
        .createStore(new CreateStoreRequest("publications-1024", "nomic-embed-text", 1024, false));

    assertEquals(STORE_ID_2, response.id());
    assertEquals("nomic-embed-text", response.model());
    assertEquals(1024, response.embeddingDimensions());
  }

  @Test
  void findsRequiredStoreByModelAndDimension() {
    when(storeRepository.findAll()).thenReturn(List.of(
        StoreEntity.builder().id(STORE_ID).name("publications-768").model("nomic-embed-text")
            .provider("openrouter").embeddingDimension(768).createdAt(Instant.now()).build(),
        StoreEntity.builder().id(STORE_ID_2).name("publications-1024").model("nomic-embed-text")
            .provider("openrouter").embeddingDimension(1024).createdAt(Instant.now()).build()));
    storeService.loadStoresIntoCache();

    StoreEntity store = storeService.getRequiredStore("nomic-embed-text", 1024);

    assertEquals(STORE_ID_2, store.getId());
    assertEquals("publications-1024", store.getName());
  }

  @Test
  void rejectsAmbiguousStoreLookupByModelAndDimension() {
    when(storeRepository.findAll()).thenReturn(List.of(
        StoreEntity.builder().id(STORE_ID).name("publications-a").model("nomic-embed-text")
            .provider("openrouter").embeddingDimension(768).createdAt(Instant.now()).build(),
        StoreEntity.builder().id(STORE_ID_2).name("publications-b").model("nomic-embed-text")
            .provider("openrouter").embeddingDimension(768).createdAt(Instant.now()).build()));
    storeService.loadStoresIntoCache();

    assertThrows(InvalidRequestException.class, () -> storeService.getRequiredStore("nomic-embed-text", 768));
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
    assertEquals(768, store.getEmbeddingDimension());
    verify(storeRepository).save(store);
  }

  @Test
  void rejectsMismatchedEmbeddingDimensions() {
    StoreEntity store = StoreEntity.builder()
        .id(STORE_ID)
        .name("publications")
        .model("nomic-embed-text")
        .provider("openrouter")
        .embeddingDimension(768)
        .build();

    assertThrows(InvalidRequestException.class, () -> storeService.ensureEmbeddingDimensions(store, 1024));
  }
}
