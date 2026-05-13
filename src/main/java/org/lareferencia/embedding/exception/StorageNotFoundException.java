package org.lareferencia.embedding.exception;

import java.util.UUID;

public class StorageNotFoundException extends RuntimeException {

  public StorageNotFoundException(UUID storageId) {
    super("Embedding storage '" + storageId + "' was not found");
  }
}