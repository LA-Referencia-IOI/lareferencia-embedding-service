package org.lareferencia.embedding.service;

import java.util.List;
import java.util.Locale;

import org.lareferencia.embedding.exception.InvalidRequestException;
import org.springframework.stereotype.Component;

@Component
public class EmbeddingProviderRegistry {

  private final List<EmbeddingProviderService> providers;

  public EmbeddingProviderRegistry(List<EmbeddingProviderService> providers) {
    this.providers = List.copyOf(providers);
  }

  public EmbeddingProviderService getDefaultProvider() {
    if (providers.isEmpty()) {
      throw new IllegalStateException("No embedding providers are configured");
    }
    return providers.get(0);
  }

  public EmbeddingProviderService getRequiredProvider(String providerName) {
    return providers.stream()
        .filter(provider -> provider.providerName().equalsIgnoreCase(normalize(providerName)))
        .findFirst()
        .orElseThrow(() -> new InvalidRequestException("Unsupported embedding provider: " + providerName));
  }

  private String normalize(String providerName) {
    if (providerName == null || providerName.isBlank()) {
      throw new InvalidRequestException("Embedding provider must not be blank");
    }
    return providerName.trim().toLowerCase(Locale.ROOT);
  }
}