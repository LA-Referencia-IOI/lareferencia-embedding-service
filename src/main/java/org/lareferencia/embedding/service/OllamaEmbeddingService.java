package org.lareferencia.embedding.service;

import java.util.List;

import org.lareferencia.embedding.exception.EmbeddingProviderException;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.model.ollama.autoconfigure.OllamaConnectionDetails;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class OllamaEmbeddingService {

  private static final String DIMENSION_PROBE_TEXT = "dimension probe";
  private static final String OLLAMA_PROVIDER = "ollama";
  private static final String OLLAMA_EMBEDDINGS_PATH = "/api/embed";
  private final EmbeddingModel embeddingModel;
  private final OllamaConnectionDetails ollamaConnectionDetails;

  public List<float[]> embed(List<String> inputs, String model) {
    try {
      log.info("Calling external embedding provider provider={} model={} inputs={}", OLLAMA_PROVIDER, model,
          inputs.size());
      EmbeddingResponse response = embeddingModel.call(new EmbeddingRequest(
          inputs,
          OllamaOptions.builder().model(model).build()));

      List<float[]> embeddings = response.getResults().stream()
          .map(Embedding::getOutput)
          .toList();

      if (embeddings.size() != inputs.size()) {
        throw new EmbeddingProviderException(
            "Embedding model returned " + embeddings.size() + " embeddings for " + inputs.size() + " inputs");
      }
      log.info("External embedding provider returned provider={} model={} embeddings={}", OLLAMA_PROVIDER, model,
          embeddings.size());
      return embeddings;
    } catch (EmbeddingProviderException exception) {
      log.error("External embedding provider returned an application error provider={} model={} message={}",
          OLLAMA_PROVIDER,
          model,
          exception.getMessage());
      throw exception;
    } catch (RuntimeException exception) {
      String providerMessage = resolveProviderMessage(exception);
      log.error("External embedding provider call failed provider={} model={} message={}", OLLAMA_PROVIDER, model,
          providerMessage, exception);
      throw new EmbeddingProviderException(providerMessage, exception);
    }
  }

  public int resolveDimensions(String model) {
    log.info("Resolving embedding dimensions from provider provider={} model={}", OLLAMA_PROVIDER, model);
    List<float[]> embeddings = embed(List.of(DIMENSION_PROBE_TEXT), model);
    if (embeddings.isEmpty() || embeddings.get(0) == null || embeddings.get(0).length == 0) {
      throw new EmbeddingProviderException(
          "Embedding provider returned an empty embedding while probing model " + model);
    }
    int dimensions = embeddings.get(0).length;
    log.info("Resolved embedding dimensions from provider provider={} model={} dimensions={}", OLLAMA_PROVIDER, model,
        dimensions);
    return dimensions;
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
      message = "Failed to generate embeddings using Spring AI";
    }

    return message + " (providerUrl=" + providerEndpoint() + ")";
  }

  private String providerEndpoint() {
    String baseUrl = ollamaConnectionDetails.getBaseUrl();
    if (baseUrl.endsWith("/")) {
      return baseUrl.substring(0, baseUrl.length() - 1) + OLLAMA_EMBEDDINGS_PATH;
    }
    return baseUrl + OLLAMA_EMBEDDINGS_PATH;
  }
}