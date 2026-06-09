package org.lareferencia.embedding.exception;

public class StoreNotFoundException extends RuntimeException {

  public StoreNotFoundException(String storeName) {
    super("Embedding store '" + storeName + "' was not found");
  }
}