package org.lareferencia.embedding.controller;

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
public class ReadOnlyController {

    private final EmbeddingService embeddingService;

    /**
     * GET /api/v1/models
     * Returns available models from the provider.
     */
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
    @GetMapping("/embeddings/{key}")
    public ResponseEntity<EmbeddingResponse> getEmbedding(@PathVariable String key) {
        return embeddingService.getCachedEmbedding(key)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
