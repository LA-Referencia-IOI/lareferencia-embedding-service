package org.lareferencia.embedding.controller;

import jakarta.validation.Valid;

import org.lareferencia.embedding.dto.GenerateEmbeddingsRequest;
import org.lareferencia.embedding.dto.GenerateEmbeddingsResponse;
import org.lareferencia.embedding.service.EmbeddingService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/embeddings")
@RequiredArgsConstructor
@Tag(name = "Embeddings", description = "Generate embeddings with store-aware persistent caching")
public class EmbeddingController {

    private final EmbeddingService embeddingService;

    @Operation(summary = "Generate embeddings", description = "Generates embeddings for a single text or a batch of texts using the model configured for the selected store. Cached vectors are returned when available.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Embeddings generated or reused successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request payload"),
            @ApiResponse(responseCode = "404", description = "Store not found"),
            @ApiResponse(responseCode = "502", description = "Ollama provider failure")
    })
    @PostMapping
    public GenerateEmbeddingsResponse generateEmbedding(@Valid @RequestBody GenerateEmbeddingsRequest request) {
        return embeddingService.generateEmbeddings(request);
    }
}
