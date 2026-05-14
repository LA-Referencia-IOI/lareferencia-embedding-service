package org.lareferencia.embedding.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.lareferencia.embedding.exception.InvalidRequestException;

class StoreTableNamingServiceTest {

  private final StoreTableNamingService service = new StoreTableNamingService();

  @Test
  void createsSafeDynamicTableNames() {
    UUID storeId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    assertEquals("embeddings_11111111_1111_1111_1111_111111111111", service.tableName(storeId));
    assertEquals("idx_embeddings_11111111_1111_1111_1111_111111111111_created_at",
        service.createdAtIndexName(storeId));
  }

  @Test
  void rejectsInvalidStoreIdentifiers() {
    assertThrows(InvalidRequestException.class, () -> service.tableName(null));
  }
}