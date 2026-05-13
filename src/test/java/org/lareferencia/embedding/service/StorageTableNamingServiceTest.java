package org.lareferencia.embedding.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.lareferencia.embedding.exception.InvalidRequestException;

class StorageTableNamingServiceTest {

  private final StorageTableNamingService service = new StorageTableNamingService();

  @Test
  void createsSafeDynamicTableNames() {
    UUID storageId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    assertEquals("embeddings_11111111_1111_1111_1111_111111111111", service.tableName(storageId));
    assertEquals("idx_embeddings_11111111_1111_1111_1111_111111111111_created_at", service.createdAtIndexName(storageId));
  }

  @Test
  void rejectsInvalidStorageIdentifiers() {
    assertThrows(InvalidRequestException.class, () -> service.tableName(null));
  }
}