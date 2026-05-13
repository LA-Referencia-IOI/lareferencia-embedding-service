package org.lareferencia.embedding.dto;

import java.time.Instant;
import java.util.UUID;

public record StorageResponse(
    UUID id,
    String name,
    String model,
    String provider,
    Integer embeddingDimensions,
    Instant createdAt) {
}