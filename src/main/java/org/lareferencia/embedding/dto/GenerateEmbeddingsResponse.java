package org.lareferencia.embedding.dto;

import java.util.List;

public record GenerateEmbeddingsResponse(
    Long storeId,
    String storeName,
    String model,
    List<EmbeddingData> data) {
  public record EmbeddingData(
      int index,
      List<Double> embedding) {
  }
}