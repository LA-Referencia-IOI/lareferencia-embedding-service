package org.lareferencia.embedding.v2.document;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SourceTextInput(
    @Schema(requiredMode=Schema.RequiredMode.REQUIRED, description="Exact, case-sensitive key; 1–200 Unicode code points") String textId,
    @Schema(requiredMode=Schema.RequiredMode.REQUIRED, description="Text kind; at most 60 Unicode code points") String kind,
    @Schema(description="Optional language; at most 35 Unicode code points", nullable=true) String language,
    @Schema(requiredMode=Schema.RequiredMode.REQUIRED, description="Nonblank source content; stored without normalization") String content) {
  @Override public String toString() { return "SourceTextInput[redacted]"; }
}
