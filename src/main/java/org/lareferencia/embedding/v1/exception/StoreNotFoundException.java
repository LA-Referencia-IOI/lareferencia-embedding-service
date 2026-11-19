package org.lareferencia.embedding.v1.exception;

public class StoreNotFoundException extends RuntimeException {

  public StoreNotFoundException(String storeName) {
    super("Embedding store '" + storeName + "' was not found");
  }

  public StoreNotFoundException(String storeName, int dimensions) {
    super("Embedding store named '" + storeName + "' and dimensions " + dimensions + " was not found");
  }
}
