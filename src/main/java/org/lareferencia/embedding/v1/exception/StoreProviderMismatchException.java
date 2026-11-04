package org.lareferencia.embedding.v1.exception;

public class StoreProviderMismatchException extends RuntimeException {
  public StoreProviderMismatchException() {
    super("Store provider differs from the configured provider; use a compatible store");
  }
}
