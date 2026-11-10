package org.lareferencia.embedding.v2.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.UUID;

/** Public job metadata; internal lease owner, raw errors and private file paths are excluded. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record JobResource(UUID id,String kind,String status,UUID collectionId,UUID indexationId,UUID parentJobId,
    JsonNode parameters,JsonNode counters,Instant createdAt,Instant finishedAt,String globalErrorCode) { }
