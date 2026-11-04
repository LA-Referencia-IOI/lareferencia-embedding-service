package org.lareferencia.embedding.v1.dto;

import java.time.Instant;
import java.util.UUID;

public record StoreResponse(
    UUID id,
    String name,
    String model,
    String provider,
    Integer embeddingDimensions,
    Instant createdAt) {
}