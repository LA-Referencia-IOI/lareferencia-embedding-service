package org.lareferencia.embedding.v1.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.lareferencia.embedding.v1.config.PgVectorStoreProperties;
import org.lareferencia.embedding.v1.dto.StatusResponse;
import org.lareferencia.embedding.v1.entity.StoreEntity;
import org.lareferencia.embedding.v1.repository.StatusRepository;
import org.lareferencia.embedding.v1.repository.StoreRepository;
import org.mockito.Mockito;

class StatusServiceTest {

  private static final UUID STORE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

  @Test
  void combinesSystemAndDatabaseStatus() {
    StatusRepository statusRepository = Mockito.mock(StatusRepository.class);
    StoreRepository storeRepository = Mockito.mock(StoreRepository.class);
    StoreTableNamingService tableNamingService = new StoreTableNamingService();
    PgVectorStoreProperties properties = new PgVectorStoreProperties();
    StoreEntity store = StoreEntity.builder()
        .id(STORE_ID)
        .name("publications")
        .model("bge-m3")
        .provider("ollama")
        .embeddingDimension(1024)
        .createdAt(Instant.now())
        .build();

    when(storeRepository.findAll()).thenReturn(List.of(store));
    when(statusRepository.currentDatabaseName()).thenReturn("embeddings");
    when(statusRepository.currentDatabaseSizeBytes()).thenReturn(4096L);
    when(statusRepository.storeCount()).thenReturn(1L);
    when(statusRepository.embeddingTableStats("public")).thenReturn(List.of(
        new StatusRepository.EmbeddingTableStats(
            "public",
            tableNamingService.tableName(STORE_ID),
            2048L,
            1024L,
            3072L,
            12L)));

    StatusService statusService = new StatusService(statusRepository, storeRepository, tableNamingService, properties);

    StatusResponse response = statusService.status();

    assertNotNull(response.timestamp());
    assertNotNull(response.system().cpu());
    assertNotNull(response.system().ram());
    assertNotNull(response.system().disk());
    assertEquals("embeddings", response.database().name());
    assertEquals(4096L, response.database().sizeBytes());
    assertEquals("4.00 KiB", response.database().size());
    assertEquals(1L, response.database().storeCount());
    assertEquals(1L, response.database().embeddingTableCount());
    assertEquals("publications", response.database().embeddingTables().getFirst().storeName());
    assertEquals(12L, response.database().embeddingTables().getFirst().estimatedRows());
    assertEquals("3.00 KiB", response.database().embeddingTables().getFirst().totalSize());
  }

  @Test
  void byteFormattingIsStableAcrossLocales() {
    java.util.Locale original = java.util.Locale.getDefault();
    try {
      for (java.util.Locale locale : java.util.List.of(java.util.Locale.US,
          java.util.Locale.forLanguageTag("es-ES"), java.util.Locale.forLanguageTag("pt-BR"))) {
        java.util.Locale.setDefault(locale);
        combinesSystemAndDatabaseStatus();
      }
    } finally {
      java.util.Locale.setDefault(original);
    }
  }
}
