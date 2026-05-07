package org.lareferencia.embedding.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.lareferencia.embedding.dto.EmbeddingResponse;
import org.lareferencia.embedding.service.EmbeddingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Read-Only Operations", description = "Endpoints for querying metadata and existing cache entries")
public class ReadOnlyController {

    private final EmbeddingService embeddingService;

    /**
     * GET /api/v1/models
     * Returns available models from the provider.
     */
    @Operation(summary = "List available models", description = "Queries the upstream provider to list all models available for inference.")
    @GetMapping("/models")
    public ResponseEntity<Object> getModels() {
        try {
            return ResponseEntity.ok(embeddingService.getAvailableModels());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * GET /api/v1/embeddings/{key}
     * Returns a cached embedding by its key (format: model:hash).
     */
    @Operation(summary = "Lookup cached embedding", description = "Retrieves a specific embedding from the database using its cache key (format: model:hash).")
    @GetMapping("/embeddings/{key}")
    public ResponseEntity<EmbeddingResponse> getEmbedding(@PathVariable String key) {
        return embeddingService.getCachedEmbedding(key)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
