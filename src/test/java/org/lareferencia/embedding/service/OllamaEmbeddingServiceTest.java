package org.lareferencia.embedding.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lareferencia.embedding.config.EmbeddingProviderProperties;
import org.lareferencia.embedding.exception.EmbeddingProviderException;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class EmbeddingProviderServiceTest {

  private EmbeddingProviderService service;
  private MockRestServiceServer mockServer;

  @BeforeEach
  void setUp() {
    EmbeddingProviderProperties properties = new EmbeddingProviderProperties();
    properties.setOllamaUrl("http://localhost:11434/v1/embeddings");

    RestClient.Builder builder = RestClient.builder();
    mockServer = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    service = new EmbeddingProviderService(restClient, new ObjectMapper(), properties);
  }

  @Test
  void includesProviderUrlWhenEmbeddingCallFails() {
    mockServer.expect(requestTo("http://localhost:11434/v1/embeddings"))
        .andRespond(withServerError());

    EmbeddingProviderException exception = assertThrows(EmbeddingProviderException.class,
        () -> service.embed(java.util.List.of("hello world"), "nomic-embed-text"));

    assertTrue(exception.getMessage().contains("providerUrl=http://localhost:11434/v1/embeddings"));
    mockServer.verify();
  }

  @Test
  void sendsBearerTokenAndParsesOpenAiStyleEmbeddings() {
    EmbeddingProviderProperties properties = new EmbeddingProviderProperties();
    properties.setOpenaiUrl("https://api.openai.com/v1/embeddings");
    properties.setApiKey("test-key");
    properties.setType(EmbeddingProviderProperties.ProviderType.OPENAI);

    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();
    EmbeddingProviderService openAiService = new EmbeddingProviderService(restClient, new ObjectMapper(), properties);

    server.expect(requestTo("https://api.openai.com/v1/embeddings"))
        .andExpect(header("Authorization", "Bearer test-key"))
        .andRespond(withSuccess(
            "{\"data\":[{\"embedding\":[0.1,0.2,0.3],\"index\":0}],\"usage\":{\"prompt_tokens\":7,\"total_tokens\":7}}",
            MediaType.APPLICATION_JSON));

    var result = openAiService.embed(java.util.List.of("hello world"), "text-embedding-3-small");
    assertEquals("openai", result.provider());
    assertEquals(7, result.usage().promptTokens());
    assertEquals(7, result.usage().totalTokens());
    assertEquals(1, result.embeddings().size());
    assertEquals(3, result.embeddings().get(0).length);
    server.verify();
  }

  @Test
  void doesNotExtractUsageForOllama() {
    mockServer.expect(requestTo("http://localhost:11434/v1/embeddings"))
        .andRespond(withSuccess(
            "{\"data\":[{\"embedding\":[0.1,0.2],\"index\":0}],\"usage\":{\"prompt_eval_count\":4,\"eval_count\":4}}",
            MediaType.APPLICATION_JSON));

    var result = service.embed(java.util.List.of("hello world"), "nomic-embed-text");
    assertEquals("ollama", result.provider());
    assertEquals(0, result.usage().promptTokens());
    assertEquals(0, result.usage().totalTokens());
    mockServer.verify();
  }

  @Test
  void extractsUsageForOpenRouter() {
    EmbeddingProviderProperties properties = new EmbeddingProviderProperties();
    properties.setOpenrouterUrl("https://openrouter.ai/api/v1/embeddings");
    properties.setApiKey("test-key");
    properties.setType(EmbeddingProviderProperties.ProviderType.OPENROUTER);

    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();
    EmbeddingProviderService openRouterService = new EmbeddingProviderService(restClient, new ObjectMapper(),
        properties);

    server.expect(requestTo("https://openrouter.ai/api/v1/embeddings"))
        .andExpect(header("Authorization", "Bearer test-key"))
        .andRespond(withSuccess(
            "{\"data\":[{\"embedding\":[0.1,0.2,0.3],\"index\":0}],\"usage\":{\"prompt_tokens\":6,\"total_tokens\":6}}",
            MediaType.APPLICATION_JSON));

    var result = openRouterService.embed(java.util.List.of("hello world"), "text-embedding-3-small");
    assertEquals("openrouter", result.provider());
    assertEquals(6, result.usage().promptTokens());
    assertEquals(6, result.usage().totalTokens());
    mockServer.verify();
  }

  @Test
  void resolveDimensionsFailsAfterSingleProbeAttempt() {
    mockServer.expect(requestTo("http://localhost:11434/v1/embeddings"))
        .andRespond(withServerError().body(
            "{\"error\":{\"message\":\"failed to encode response: json: unsupported value: NaN\"}}"));

    EmbeddingProviderException exception = assertThrows(EmbeddingProviderException.class,
        () -> service.resolveDimensions("nomic-embed-text"));

    assertTrue(exception.getMessage().contains("after 1 probe attempts"));
    mockServer.verify();
  }
}