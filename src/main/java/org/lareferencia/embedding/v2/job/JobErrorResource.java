package org.lareferencia.embedding.v2.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.UUID;

/** Fixed diagnostic and locator without source content or provider responses. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record JobErrorResource(String itemKey,UUID documentId,JsonNode locator,String code,String message,int attempts) { }
