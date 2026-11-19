package org.lareferencia.embedding.v2.collection;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.UUID;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CollectionDetails(UUID id, String slug, String name, Instant createdAt, Counts counts) {
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Counts(long documents, long indexations) {}
}
