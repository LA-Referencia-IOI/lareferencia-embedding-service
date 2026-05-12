package org.lareferencia.embedding.config;

import java.net.http.HttpClient;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class OllamaConfig {

    @Bean
    RestClient ollamaRestClient(OllamaClientProperties ollamaClientProperties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(ollamaClientProperties.getConnectTimeout())
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(ollamaClientProperties.getRequestTimeout());

        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }
}