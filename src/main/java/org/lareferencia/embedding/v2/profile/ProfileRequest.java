package org.lareferencia.embedding.v2.profile;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProfileRequest(String slug,String model,String modelRevision,Integer dimensions,String executionMode,
    String providerRef,Preparation preparation) {
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Preparation(String version,String documentPrefix,String queryPrefix) {}
}
