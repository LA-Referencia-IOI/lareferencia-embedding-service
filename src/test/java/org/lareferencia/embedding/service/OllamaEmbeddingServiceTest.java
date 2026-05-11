package org.lareferencia.embedding.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lareferencia.embedding.exception.EmbeddingProviderException;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.ollama.autoconfigure.OllamaConnectionDetails;

@ExtendWith(MockitoExtension.class)
class OllamaEmbeddingServiceTest {

  @Mock
  private EmbeddingModel embeddingModel;

  @Mock
  private OllamaConnectionDetails ollamaConnectionDetails;

  private OllamaEmbeddingService service;

  @BeforeEach
  void setUp() {
    service = new OllamaEmbeddingService(embeddingModel, ollamaConnectionDetails);
  }

  @Test
  void includesProviderUrlWhenEmbeddingCallFails() {
    when(ollamaConnectionDetails.getBaseUrl()).thenReturn("http://localhost:11434");
    when(embeddingModel.call(any())).thenThrow(new RuntimeException(new RuntimeException("HTTP connect timed out")));

    EmbeddingProviderException exception = assertThrows(EmbeddingProviderException.class,
        () -> service.embed(java.util.List.of("hello world"), "nomic-embed-text"));

    assertTrue(exception.getMessage().contains("HTTP connect timed out"));
    assertTrue(exception.getMessage().contains("providerUrl=http://localhost:11434/api/embed"));
  }
}