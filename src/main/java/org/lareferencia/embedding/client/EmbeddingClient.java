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

    public List<Double> generateEmbedding(String input, String model) {
        log.debug("Calling embedding provider: {} for model: {}", providerUrl, model);
        try {
            Map<String, Object> requestBody = Map.of(
                    "model", model,
                    "input", input);

            OllamaResponse response = restClient.post()
                    .uri(providerUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(OllamaResponse.class);

            if (response == null) {
                throw new RuntimeException("Received null response from embedding provider");
            }

            // Support both standard Ollama (/api/embeddings) and OpenAI-compatible
            // (/v1/embeddings) formats
            if (response.embedding() != null) {
                return response.embedding();
            } else if (response.data() != null && !response.data().isEmpty()
                    && response.data().get(0).embedding() != null) {
                return response.data().get(0).embedding();
            }

            throw new RuntimeException(
                    "Received invalid response structure from embedding provider (no embedding found)");
        } catch (RestClientException e) {
            log.error("Failed to generate embedding from provider", e);
            throw new RuntimeException("Failed to generate embedding", e);
        }
    }

    record OllamaResponse(List<Double> embedding, List<EmbeddingData> data) {
        record EmbeddingData(List<Double> embedding) {
        }
    }
}
