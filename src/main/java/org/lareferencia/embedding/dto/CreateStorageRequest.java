package org.lareferencia.embedding.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateStorageRequest(
                @NotBlank(message = "Storage name is required") @Size(max = 60, message = "Storage name must have at most 60 characters") @Pattern(regexp = "^[a-zA-Z0-9_ -]+$", message = "Storage name may only contain letters, numbers, spaces, hyphens, and underscores") String name,

                @NotBlank(message = "Embedding model is required") @Size(max = 60, message = "Model name must have at most 60 characters") String model,

                Boolean createVectorIndex) {
}