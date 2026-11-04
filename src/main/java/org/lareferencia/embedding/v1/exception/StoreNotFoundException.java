package org.lareferencia.embedding.v1.exception;

public class StoreNotFoundException extends RuntimeException {

  public StoreNotFoundException(String storeName) {
    super("Embedding store '" + storeName + "' was not found");
  }

  public StoreNotFoundException(String model, int dimensions) {
    super("Embedding store for model '" + model + "' and dimensions " + dimensions + " was not found");
  }
}
