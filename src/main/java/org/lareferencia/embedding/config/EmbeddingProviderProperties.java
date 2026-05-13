package org.lareferencia.embedding.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.embedding.provider")
public class EmbeddingProviderProperties {

  public enum ProviderType {
    OLLAMA,
    OPENAI,
    OPENROUTER
  }

  private ProviderType type = ProviderType.OLLAMA;
  private String ollamaUrl = "http://localhost:11434/v1/embeddings";
  private String openaiUrl = "https://api.openai.com/v1/embeddings";
  private String openrouterUrl = "https://openrouter.ai/api/v1/embeddings";
  private String apiKey;
  private Duration connectTimeout = Duration.ofSeconds(5);
  private Duration requestTimeout = Duration.ofSeconds(30);

  public ProviderType getType() {
    return type;
  }

  public void setType(ProviderType type) {
    this.type = type;
  }

  public String getOllamaUrl() {
    return ollamaUrl;
  }

  public void setOllamaUrl(String ollamaUrl) {
    this.ollamaUrl = ollamaUrl;
  }

  public String getOpenaiUrl() {
    return openaiUrl;
  }

  public void setOpenaiUrl(String openaiUrl) {
    this.openaiUrl = openaiUrl;
  }

  public String getOpenrouterUrl() {
    return openrouterUrl;
  }

  public void setOpenrouterUrl(String openrouterUrl) {
    this.openrouterUrl = openrouterUrl;
  }

  public String getApiKey() {
    return apiKey;
  }

  public void setApiKey(String apiKey) {
    this.apiKey = apiKey;
  }

  public Duration getConnectTimeout() {
    return connectTimeout;
  }

  public void setConnectTimeout(Duration connectTimeout) {
    this.connectTimeout = connectTimeout;
  }

  public Duration getRequestTimeout() {
    return requestTimeout;
  }

  public void setRequestTimeout(Duration requestTimeout) {
    this.requestTimeout = requestTimeout;
  }
}