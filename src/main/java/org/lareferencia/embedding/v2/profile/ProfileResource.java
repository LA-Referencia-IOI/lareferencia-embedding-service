package org.lareferencia.embedding.v2.profile;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.UUID;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProfileResource(UUID id,String slug,String model,String modelRevision,int dimensions,String executionMode,
    String providerRef,ProfileRequest.Preparation preparation,String fingerprint,boolean inferenceCapable,Instant createdAt) {}
