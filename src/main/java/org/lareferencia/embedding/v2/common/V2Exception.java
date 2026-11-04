package org.lareferencia.embedding.v2.common;

public class V2Exception extends RuntimeException {
  private final int status;
  private final String code;
  public V2Exception(int status, String code, String message) {
    super(message); this.status=status; this.code=code;
  }
  public int status() { return status; }
  public String code() { return code; }
  public static V2Exception badRequest(String message) { return new V2Exception(400,"invalid_request",message); }
}
