package org.lareferencia.embedding.v2.document;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SourceTextResource(String textId,String kind,String language,String content,long revision,
    String contentHash,Instant updatedAt) {
  @Override public String toString() { return "SourceTextResource[redacted]"; }
}
