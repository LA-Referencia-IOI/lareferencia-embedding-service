package org.lareferencia.embedding.dto;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.validation.constraints.NotNull;

public record GenerateEmbeddingsRequest(
    @NotNull(message = "model name is required") String model,

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