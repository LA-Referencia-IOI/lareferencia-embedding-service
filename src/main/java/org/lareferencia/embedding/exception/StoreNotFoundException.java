package org.lareferencia.embedding.exception;

public class StoreNotFoundException extends RuntimeException {

  public StoreNotFoundException(Long storeId) {
    super("Embedding store '" + storeId + "' was not found");
  }
}