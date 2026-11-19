package org.lareferencia.embedding.v1.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;

public record GenerateEmbeddingsRequest(
    @NotBlank(message = "Store name is required")
        @Size(max = 60, message = "Store name must have at most 60 characters")
        @JsonProperty("model")
        @Schema(description = "Store name; the store model is sent to the embedding provider")
        String storeName,
    @Positive(message = "Embedding dimensions must be a positive number") int dimensions,
    @NotNull(message = "input is required") JsonNode input,
    @Schema(
            description = "Persist newly generated vectors; the existing cache is used in both modes; defaults to true",
            defaultValue = "true")
        Boolean save) {

  public GenerateEmbeddingsRequest(String storeName, int dimensions, JsonNode input) {
    this(storeName, dimensions, input, null);
  }

  public boolean shouldSave() {
    return !Boolean.FALSE.equals(save);
  }

  public List<String> inputs() {
    if (input == null) {
      return List.of();
    }
    if (input.isTextual()) {
      return List.of(input.asText());
    }
    if (!input.isArray()) {
      return List.of();
    }

    List<String> values = new ArrayList<>();
    for (JsonNode item : input) {
      if (!item.isTextual()) {
        return List.of();
      }
      values.add(item.asText());
    }
    return values;
  }
}
