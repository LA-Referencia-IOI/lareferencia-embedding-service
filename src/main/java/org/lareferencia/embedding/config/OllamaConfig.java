package org.lareferencia.embedding.config;

import java.net.http.HttpClient;

import org.springframework.ai.model.ollama.autoconfigure.OllamaConnectionDetails;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class OllamaConfig {

  @Bean
  OllamaApi ollamaApi(OllamaConnectionDetails connectionDetails, OllamaClientProperties ollamaClientProperties) {
    HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(ollamaClientProperties.getConnectTimeout())
        .build();

    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(ollamaClientProperties.getRequestTimeout());

    RestClient.Builder restClientBuilder = RestClient.builder()
        .requestFactory(requestFactory);

    return OllamaApi.builder()
        .baseUrl(connectionDetails.getBaseUrl())
        .restClientBuilder(restClientBuilder)
        .build();
  }
}