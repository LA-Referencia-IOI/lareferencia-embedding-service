package org.lareferencia.embedding.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.lareferencia.embedding.dto.EmbeddingRequest;
import org.lareferencia.embedding.dto.EmbeddingResponse;
import org.lareferencia.embedding.service.EmbeddingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/embeddings")
@RequiredArgsConstructor
@Tag(name = "Embedding Generation", description = "Endpoints for generating and retrieving embeddings with caching")
public class EmbeddingController {

    private final EmbeddingService embeddingService;

    @Operation(summary = "Generate or retrieve embeddings", description = "Takes a single string or a list of strings and returns embeddings following the OpenAI API contract. Results are cached.")
    @PostMapping
    public ResponseEntity<EmbeddingResponse> generateEmbedding(@RequestBody EmbeddingRequest request) {
        if (request == null || request.input() == null || request.model() == null || request.model().trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        var inputs = request.inputs();
        if (inputs.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        EmbeddingResponse response = embeddingService.getEmbeddings(inputs, request.model());
        return ResponseEntity.ok(response);
    }
}
