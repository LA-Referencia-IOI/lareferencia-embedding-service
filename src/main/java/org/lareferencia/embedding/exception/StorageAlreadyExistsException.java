package org.lareferencia.embedding.exception;

public class StorageAlreadyExistsException extends RuntimeException {

  public StorageAlreadyExistsException(String storageName) {
    super("Embedding storage '" + storageName + "' already exists");
  }
}