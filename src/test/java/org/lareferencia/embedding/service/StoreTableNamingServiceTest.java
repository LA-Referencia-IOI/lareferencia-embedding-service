package org.lareferencia.embedding.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.lareferencia.embedding.exception.InvalidRequestException;

class StoreTableNamingServiceTest {

  private final StoreTableNamingService service = new StoreTableNamingService();

  @Test
  void createsSafeDynamicTableNames() {
    assertEquals("embeddings_12", service.tableName(12L));
    assertEquals("idx_embeddings_12_created_at", service.createdAtIndexName(12L));
  }

  @Test
  void rejectsInvalidStoreIdentifiers() {
    assertThrows(InvalidRequestException.class, () -> service.tableName(0));
    assertThrows(InvalidRequestException.class, () -> service.tableName(-1));
  }
}