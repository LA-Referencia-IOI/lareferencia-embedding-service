package org.lareferencia.embedding.v2.document;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SourceTextInput(
    @JsonIgnore @Schema(hidden=true) String key,
    @JsonProperty("knd") @Schema(requiredMode=Schema.RequiredMode.REQUIRED, description="Text kind; at most 60 Unicode code points") String kind,
    @JsonProperty("lng") @Schema(description="Optional language; at most 35 Unicode code points", nullable=true) String language,
    @JsonProperty("cnt") @Schema(requiredMode=Schema.RequiredMode.REQUIRED, description="Nonblank source content; stored without normalization") String content) {
  @Override public String toString() { return "SourceTextInput[redacted]"; }
}
