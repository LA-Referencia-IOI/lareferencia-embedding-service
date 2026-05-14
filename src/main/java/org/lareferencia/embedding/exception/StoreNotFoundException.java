package org.lareferencia.embedding.exception;

import java.util.UUID;

public class StoreNotFoundException extends RuntimeException {

  public StoreNotFoundException(UUID storeId) {
    super("Embedding store '" + storeId + "' was not found");
  }
}