package org.lareferencia.embedding.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateStoreRequest(
    @NotBlank(message = "Store name is required") @Size(max = 60, message = "Store name must have at most 60 characters") @Pattern(regexp = "^[a-zA-Z0-9_-]+$", message = "Store name may only contain letters, numbers, hyphens, and underscores") String name,
    @NotBlank(message = "Embedding model is required") @Size(max = 60, message = "Model name must have at most 60 characters") String model,
    @NotNull(message = "Embedding dimension is required") int dimension,
    Boolean createVectorIndex) {
}