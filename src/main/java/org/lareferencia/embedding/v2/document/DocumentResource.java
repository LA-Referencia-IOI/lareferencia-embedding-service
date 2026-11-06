package org.lareferencia.embedding.v2.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DocumentResource(UUID id,String externalId,UUID collectionId,long revision,JsonNode metadata,
    List<SourceTextResource> texts,Instant createdAt,Instant updatedAt) {
  @Override public String toString() { return "DocumentResource[redacted]"; }
}
