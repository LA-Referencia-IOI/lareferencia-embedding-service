package org.lareferencia.embedding.v2.document;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.UUID;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DocumentSummary(UUID id,String externalId,UUID collectionId,long revision,JsonNode metadata,
    Instant createdAt,Instant updatedAt) {
  @Override public String toString() { return "DocumentSummary[redacted]"; }
}
