package org.lareferencia.embedding.dto;

import java.util.List;

public record EmbeddingResponse(String model, List<Double> embedding) {
}
