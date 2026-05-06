package org.lareferencia.embedding.controller;

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
@RequestMapping("/api/embeddings")
@RequiredArgsConstructor
public class EmbeddingController {

    private final EmbeddingService embeddingService;

    @PostMapping
    public ResponseEntity<EmbeddingResponse> generateEmbedding(@RequestBody EmbeddingRequest request) {
        if (request == null || request.input() == null || request.input().trim().isEmpty() ||
            request.model() == null || request.model().trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        
        EmbeddingResponse response = embeddingService.getEmbedding(request.input(), request.model());
        return ResponseEntity.ok(response);
    }
}
