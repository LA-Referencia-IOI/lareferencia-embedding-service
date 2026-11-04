package org.lareferencia.embedding.v1.controller;

import jakarta.validation.Valid;

import org.lareferencia.embedding.v1.dto.GenerateEmbeddingsRequest;
import org.lareferencia.embedding.v1.dto.GenerateEmbeddingsResponse;
import org.lareferencia.embedding.v1.service.EmbeddingService;
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
@Tag(name = "Embeddings", description = "Generate embeddings with model and dimensions aware persistent caching")
public class EmbeddingController {

    private final EmbeddingService embeddingService;

    @Operation(summary = "Generate embeddings", description = "Generates embeddings for a single text or a batch of texts using the store registered for the requested model and dimensions. Cached vectors are returned when available.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Embeddings generated or reused successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request payload"),
            @ApiResponse(responseCode = "404", description = "Store not found"),
            @ApiResponse(responseCode = "409", description = "Store provider differs from configured provider"),
            @ApiResponse(responseCode = "502", description = "Embedding provider failure")
    })
    @PostMapping
    public GenerateEmbeddingsResponse generateEmbedding(@Valid @RequestBody GenerateEmbeddingsRequest request) {
        return embeddingService.generateEmbeddings(request);
    }
}
