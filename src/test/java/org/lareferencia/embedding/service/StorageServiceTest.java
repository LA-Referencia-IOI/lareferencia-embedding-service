package org.lareferencia.embedding.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lareferencia.embedding.dto.CreateStorageRequest;
import org.lareferencia.embedding.dto.StorageResponse;
import org.lareferencia.embedding.entity.StorageEntity;
import org.lareferencia.embedding.exception.EmbeddingProviderException;
import org.lareferencia.embedding.exception.InvalidRequestException;
import org.lareferencia.embedding.exception.StorageAlreadyExistsException;
import org.lareferencia.embedding.repository.EmbeddingRepository;
import org.lareferencia.embedding.repository.StorageRepository;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StorageServiceTest {

  private static final UUID STORE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID STORE_ID_2 = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Mock
  private StorageRepository storageRepository;

  @Mock
  private EmbeddingProviderService embeddingProviderService;

  @Mock
  private EmbeddingRepository embeddingRepository;

  private StorageService storageService;

  @BeforeEach
  void setUp() {
    when(storageRepository.findAll()).thenReturn(List.of());
    storageService = new StorageService(storageRepository, embeddingProviderService, embeddingRepository);
    storageService.loadStoragesIntoCache();
  }

  @Test
  void createsStorageAfterValidatingModelAndAllocatingEmbeddingTable() {
    when(embeddingProviderService.provider()).thenReturn("openrouter");
    when(embeddingProviderService.resolveDimensions("nomic-embed-text")).thenReturn(768);
    when(storageRepository.save(any(StorageEntity.class))).thenReturn(StorageEntity.builder()
        .id(STORE_ID)
        .name("publications")
        .model("nomic-embed-text")
        .provider("openrouter")
        .embeddingDimensions(768)
        .createdAt(Instant.now())
        .build());

    StorageResponse response = storageService
        .createStorage(new CreateStorageRequest("publications", "nomic-embed-text"));

    assertEquals(STORE_ID, response.id());
    assertEquals("publications", response.name());
    assertEquals("nomic-embed-text", response.model());
    assertEquals("openrouter", response.provider());
    verify(embeddingProviderService).resolveDimensions("nomic-embed-text");
    verify(embeddingRepository).ensureStorageTableExists(STORE_ID, 768);
  }

  @Test
  void rejectsDuplicateStorageNames() {
    // Pre-populate cache with an existing storage
    when(storageRepository.findAll()).thenReturn(List.of(
        StorageEntity.builder().id(STORE_ID).name("publications").model("nomic-embed-text")
            .provider("openrouter").createdAt(Instant.now()).build()));
    storageService.loadStoragesIntoCache();

    assertThrows(StorageAlreadyExistsException.class,
        () -> storageService.createStorage(new CreateStorageRequest("publications", "nomic-embed-text")));
  }

  @Test
  void doesNotCreateStorageWhenProviderRejectsModel() {
    when(embeddingProviderService.provider()).thenReturn("openrouter");
    when(embeddingProviderService.resolveDimensions("missing-model"))
        .thenThrow(new EmbeddingProviderException("model 'missing-model' not found"));

    assertThrows(EmbeddingProviderException.class,
        () -> storageService.createStorage(new CreateStorageRequest("publications", "missing-model")));

    verify(storageRepository, never()).save(any(StorageEntity.class));
    verify(embeddingRepository, never()).ensureStorageTableExists(any(UUID.class), any(Integer.class));
  }

  @Test
  void listsRegisteredStorages() {
    when(storageRepository.findAll()).thenReturn(List.of(
        StorageEntity.builder().id(STORE_ID).name("publications").model("nomic-embed-text")
            .provider("openrouter").createdAt(Instant.now()).build(),
        StorageEntity.builder().id(STORE_ID_2).name("repositories").model("mxbai-embed-large")
            .provider("openai").createdAt(Instant.now()).build()));
    storageService.loadStoragesIntoCache();

    List<StorageResponse> responses = storageService.listStorages();

    assertEquals(2, responses.size());
  }

  @Test
  void persistsEmbeddingDimensionsWhenMissing() {
    StorageEntity storage = StorageEntity.builder()
        .id(STORE_ID)
        .name("publications")
        .model("nomic-embed-text")
        .provider("openrouter")
        .build();

    int dimensions = storageService.ensureEmbeddingDimensions(storage, 768);

    assertEquals(768, dimensions);
    assertEquals(768, storage.getEmbeddingDimensions());
    verify(storageRepository).save(storage);
  }

  @Test
  void rejectsMismatchedEmbeddingDimensions() {
    StorageEntity storage = StorageEntity.builder()
        .id(STORE_ID)
        .name("publications")
        .model("nomic-embed-text")
        .provider("openrouter")
        .embeddingDimensions(768)
        .build();

    assertThrows(InvalidRequestException.class, () -> storageService.ensureEmbeddingDimensions(storage, 1024));
  }
}