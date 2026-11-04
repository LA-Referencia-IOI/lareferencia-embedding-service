package org.lareferencia.embedding.v1.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lareferencia.embedding.v1.dto.CreateStoreRequest;
import org.lareferencia.embedding.v1.entity.StoreEntity;
import org.lareferencia.embedding.v1.exception.*;
import org.lareferencia.embedding.v1.repository.*;

class StoreServiceTest {
  private final StoreRepository repository = mock(StoreRepository.class);
  private final EmbeddingProviderService provider = mock(EmbeddingProviderService.class);
  private final EmbeddingRepository vectors = mock(EmbeddingRepository.class);
  private StoreService service;

  @BeforeEach void setup() {
    service = new StoreService(repository, provider, vectors);
  }

  private StoreEntity store(int dimensions) {
    return StoreEntity.builder().id(UUID.randomUUID()).name("publications").model("model")
        .embeddingDimension(dimensions).provider("ollama").createdAt(Instant.now()).build();
  }

  @Test void createsStoreAndTableInSameTransaction() {
    StoreEntity saved = store(3);
    when(provider.provider()).thenReturn("ollama");
    when(repository.saveAndFlush(any())).thenReturn(saved);
    var response = service.createStore(new CreateStoreRequest("publications", "model", 3, true));
    assertEquals(saved.getId(), response.id());
    assertEquals(3, response.embeddingDimensions());
    verify(vectors).ensureStoreTableExists(saved.getId(), 3, true);
  }

  @Test void rejectsDuplicateName() {
    when(repository.nameExists("publications")).thenReturn(true);
    assertThrows(StoreAlreadyExistsException.class,
        () -> service.createStore(new CreateStoreRequest("publications", "model", 3, false)));
    verify(repository, never()).saveAndFlush(any());
  }

  @Test void rejectsDuplicateModelAndDimensions() {
    when(repository.findByModelAndDimensions("model", 3)).thenReturn(List.of(store(3)));
    assertThrows(StoreAlreadyExistsException.class,
        () -> service.createStore(new CreateStoreRequest("other", "model", 3, false)));
  }

  @Test void findsStoresFromDatabaseOnEveryLookup() {
    var saved = store(3);
    when(repository.findByModelAndDimensions("model", 3)).thenReturn(List.of(), List.of(saved));
    assertThrows(StoreNotFoundException.class, () -> service.getRequiredStore("model", 3));
    assertEquals(saved, service.getRequiredStore("model", 3));
  }

  @Test void rejectsAmbiguousLegacyModelAndDimensions() {
    when(repository.findByModelAndDimensions("model", 3)).thenReturn(List.of(store(3), store(3)));
    assertThrows(InvalidRequestException.class, () -> service.getRequiredStore("model", 3));
  }

  @Test void listsCurrentDatabaseState() {
    when(repository.findAll()).thenReturn(List.of(), List.of(store(3)));
    assertTrue(service.listStores().isEmpty());
    assertEquals(1, service.listStores().size());
  }

  @Test void persistsMissingDimensionsAndRejectsMismatch() {
    var saved = store(3);
    assertThrows(InvalidRequestException.class, () -> service.ensureEmbeddingDimensions(saved, 4));
    assertThrows(InvalidRequestException.class, () -> service.ensureEmbeddingDimensions(saved, 0));
    saved.setEmbeddingDimension(null);
    assertEquals(3, service.ensureEmbeddingDimensions(saved, 3));
    verify(repository).save(saved);
  }
}
