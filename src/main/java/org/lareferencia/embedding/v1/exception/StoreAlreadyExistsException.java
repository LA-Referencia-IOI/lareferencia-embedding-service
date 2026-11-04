package org.lareferencia.embedding.v1.exception;

public class StoreAlreadyExistsException extends RuntimeException {

  public StoreAlreadyExistsException(String storeName) {
    super("Embedding store '" + storeName + "' already exists");
  }

  public StoreAlreadyExistsException(String model, int dimensions) {
    super("Embedding store for model '" + model + "' and dimensions " + dimensions + " already exists");
  }
}
