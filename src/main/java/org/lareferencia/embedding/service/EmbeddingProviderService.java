package org.lareferencia.embedding.service;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.lareferencia.embedding.config.EmbeddingProviderProperties;
import org.lareferencia.embedding.exception.EmbeddingProviderException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmbeddingProviderService {

  private final RestClient embeddingRestClient;
  private final ObjectMapper objectMapper;
  private final EmbeddingProviderProperties ollamaClientProperties;

  public EmbeddingBatchResult embed(List<String> inputs, String model, int dimensions) {
    if (inputs == null || inputs.isEmpty()) {
      throw new EmbeddingProviderException("Input list for embeddings must not be empty");
    }
    if (model == null || model.isBlank()) {
      throw new EmbeddingProviderException("Embedding model must not be blank");
    }

    try {
      String provider = providerName();
      log.info("Calling external embedding provider provider={} model={} inputs={}", provider, model,
          inputs.size());
      JsonNode response = embeddingRestClient.post()
          .uri(providerEndpoint())
          .headers(this::applyAuthentication)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new EmbeddingRequest(model, inputs, dimensions))
          .retrieve()
          .body(JsonNode.class);

      EmbeddingBatchResult result = parseProviderResponse(response, provider);
      List<float[]> embeddings = result.embeddings();

      if (embeddings.size() != inputs.size()) {
        throw new EmbeddingProviderException(
            "Embedding model returned " + embeddings.size() + " embeddings for " + inputs.size() + " inputs");
      }
      log.info("External embedding provider returned provider={} model={} embeddings={}", provider, model,
          embeddings.size());
      return result;
    } catch (EmbeddingProviderException exception) {
      String provider = providerName();
      log.error("External embedding provider returned an application error provider={} model={} message={}",
          provider,
          model,
          exception.getMessage());
      throw exception;
    } catch (RuntimeException exception) {
      String providerMessage = resolveProviderMessage(exception);
      log.error("External embedding provider call failed provider={} model={} message={}", providerName(), model,
          providerMessage, exception);
      throw new EmbeddingProviderException(providerMessage, exception);
    }
  }

  private String resolveProviderMessage(RuntimeException exception) {
    Throwable current = exception;
    while (current.getCause() != null) {
      current = current.getCause();
    }

    String message = current.getMessage();
    if (message == null || message.isBlank()) {
      message = exception.getMessage();
    }
    if (message == null || message.isBlank()) {
      message = "Failed to generate embeddings using " + providerName();
    }

    return message + " (providerUrl=" + providerEndpoint() + ")";
  }

  private EmbeddingBatchResult parseProviderResponse(JsonNode response, String provider) {
    if (response == null) {
      throw new EmbeddingProviderException("Embedding response is empty");
    }

    JsonNode dataNode = response.get("data");
    if (dataNode != null && dataNode.isArray()) {
      return new EmbeddingBatchResult(toEmbeddingList(dataNode), provider, extractUsage(response, provider));
    }

    throw new EmbeddingProviderException("Embedding response does not contain 'data' vectors");
  }

  private UsageData extractUsage(JsonNode response, String provider) {
    if ("ollama".equals(provider)) {
      return new UsageData(0, 0);
    }

    JsonNode usageNode = response.get("usage");
    if (usageNode == null || !usageNode.isObject()) {
      return new UsageData(0, 0);
    }

    int promptTokens = readTokenCount(usageNode, "prompt_tokens");

    int totalTokens = readTokenCount(usageNode, "total_tokens");
    if (totalTokens == 0) {
      totalTokens = promptTokens;
    }

    return new UsageData(promptTokens, totalTokens);
  }

  private int readTokenCount(JsonNode usageNode, String fieldName) {
    JsonNode tokenNode = usageNode.get(fieldName);
    if (tokenNode == null || !tokenNode.isNumber()) {
      return 0;
    }
    return Math.max(0, tokenNode.asInt());
  }

  private List<float[]> toEmbeddingList(JsonNode dataNode) {
    List<float[]> embeddings = new java.util.ArrayList<>(dataNode.size());
    for (JsonNode itemNode : dataNode) {
      JsonNode embeddingNode = itemNode.get("embedding");
      if (embeddingNode == null || !embeddingNode.isArray()) {
        throw new EmbeddingProviderException("Embedding response contains malformed embedding payload");
      }
      embeddings.add(toFloatArray(embeddingNode));
    }
    return embeddings;
  }

  private float[] toFloatArray(JsonNode embeddingNode) {
    try {
      return objectMapper.convertValue(embeddingNode, float[].class);
    } catch (IllegalArgumentException exception) {
      throw new EmbeddingProviderException("Failed to parse embedding vector from response", exception);
    }
  }

  private void applyAuthentication(HttpHeaders headers) {
    if (StringUtils.hasText(ollamaClientProperties.getApiKey())) {
      headers.setBearerAuth(ollamaClientProperties.getApiKey());
    }
  }

  private String providerName() {
    return ollamaClientProperties.getType().name().toLowerCase();
  }

  public String provider() {
    return providerName();
  }

  private String providerEndpoint() {
    String url = switch (ollamaClientProperties.getType()) {
      case OLLAMA -> ollamaClientProperties.getOllamaUrl();
      case OPENAI -> ollamaClientProperties.getOpenaiUrl();
      case OPENROUTER -> ollamaClientProperties.getOpenrouterUrl();
    };
    if (!StringUtils.hasText(url)) {
      throw new EmbeddingProviderException("Embedding provider URL must not be blank for provider "
          + providerName());
    }
    return url;
  }

  private record EmbeddingRequest(String model, List<String> input, int dimensions) {
  }

  public record UsageData(int promptTokens, int totalTokens) {
  }

  public record EmbeddingBatchResult(List<float[]> embeddings, String provider, UsageData usage) {
  }
}
