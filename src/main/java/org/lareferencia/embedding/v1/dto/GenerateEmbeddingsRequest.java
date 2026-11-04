package org.lareferencia.embedding.v1.dto;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record GenerateEmbeddingsRequest(
    @NotBlank(message = "Embedding model is required") @Size(max = 60, message = "Model name must have at most 60 characters") String model,

    @Positive(message = "Embedding dimensions must be a positive number") int dimensions,

    @NotNull(message = "input is required") JsonNode input) {

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
