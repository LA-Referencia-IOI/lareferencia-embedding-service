package org.lareferencia.embedding.dto;

import java.time.Instant;

public record StoreResponse(
        Long id,
        String name,
        String model,
        Integer embeddingDimensions,
        Instant createdAt) {
}