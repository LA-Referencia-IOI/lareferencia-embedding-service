package org.lareferencia.embedding.v2.document;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SourceTextResource(
    @JsonProperty("knd") String kind,
    @JsonProperty("lng") String language,
    @JsonProperty("cnt") String content,
    long revision,
    String contentHash,
    Instant updatedAt) {
  @Override
  public String toString() {
    return "SourceTextResource[redacted]";
  }
}
