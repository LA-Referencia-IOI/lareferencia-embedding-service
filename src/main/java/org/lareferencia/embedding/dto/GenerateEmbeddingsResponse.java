package org.lareferencia.embedding.dto;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GenerateEmbeddingsResponse(
    UUID storageId,
    String storageName,
    String model,
    String provider,
    String object,
    List<EmbeddingData> data,
    Usage usage) {
  public record EmbeddingData(
      String object,
      int index,
      List<Double> embedding) {
  }

  public record Usage(
      @JsonProperty("prompt_tokens") int promptTokens,
      @JsonProperty("total_tokens") int totalTokens) {
  }
}