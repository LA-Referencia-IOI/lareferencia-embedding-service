package org.lareferencia.embedding.v2.indexation;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;

/** Keeps the original JSON while selecting the strict v2 request converter. */
public record IndexationBody(@JsonValue JsonNode value) {
  @JsonCreator(mode=JsonCreator.Mode.DELEGATING)
  public IndexationBody { }
}
