package org.lareferencia.embedding.exception;

public class StoreAlreadyExistsException extends RuntimeException {

  public StoreAlreadyExistsException(String storeName) {
    super("Embedding store '" + storeName + "' already exists");
  }
}