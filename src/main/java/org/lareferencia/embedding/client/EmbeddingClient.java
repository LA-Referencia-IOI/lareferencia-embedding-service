package org.lareferencia.embedding.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class EmbeddingClient {

    private final RestClient restClient;
    private final String providerUrl;

    public EmbeddingClient(
            RestClient.Builder restClientBuilder,
            @Value("${embedding.provider-url}") String providerUrl) {
        this.restClient = restClientBuilder.build();
        this.providerUrl = providerUrl;
    }

    public List<List<Double>> generateEmbeddings(List<String> inputs, String model) {
        log.debug("Calling embedding provider: {} for model: {} with {} inputs", providerUrl, model, inputs.size());
        try {
            Map<String, Object> requestBody = Map.of(
                    "model", model,
                    "input", inputs.size() == 1 ? inputs.get(0) : inputs);

            OllamaResponse response = restClient.post()
                    .uri(providerUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(OllamaResponse.class);

            if (response == null) {
                throw new RuntimeException("Received null response from embedding provider");
            }

            // Support both standard Ollama (/api/embeddings), newer (/api/embed) 
            // and OpenAI-compatible (/v1/embeddings) formats
            if (response.embeddings() != null && !response.embeddings().isEmpty()) {
                return response.embeddings();
            } else if (response.embedding() != null) {
                return List.of(response.embedding());
            } else if (response.data() != null && !response.data().isEmpty()) {
                return response.data().stream()
                        .map(OllamaResponse.EmbeddingData::embedding)
                        .toList();
            }

            throw new RuntimeException(
                    "Received invalid response structure from embedding provider (no embeddings found)");
        } catch (RestClientException e) {
            log.error("Failed to generate embedding from provider", e);
            throw new RuntimeException("Failed to generate embedding", e);
        }
    }

    public Object getAvailableModels() {
        String modelsUrl = providerUrl.replace("/embeddings", "/models");
        log.debug("Fetching available models from: {}", modelsUrl);
        try {
            return restClient.get()
                    .uri(modelsUrl)
                    .retrieve()
                    .body(Object.class);
        } catch (RestClientException e) {
            log.error("Failed to fetch models from provider", e);
            throw new RuntimeException("Failed to fetch models", e);
        }
    }

    record OllamaResponse(List<Double> embedding, List<List<Double>> embeddings, List<EmbeddingData> data) {
        record EmbeddingData(List<Double> embedding) {
        }
    }
}
