package org.lareferencia.embedding.v2.document;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;

/** Preserves the stored replay JSON while keeping source text out of framework debug logs. */
public final class DocumentResponseBody {
  private final JsonNode value;
  public DocumentResponseBody(JsonNode value) { this.value=value; }
  @JsonValue public JsonNode value() { return value; }
  @Override public String toString() { return "DocumentResponseBody[redacted]"; }
}
