package org.lareferencia.embedding.v1.config;

import java.net.http.HttpClient;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class EmbeddingProviderConfig {

    @Bean
    RestClient embeddingRestClient(EmbeddingProviderProperties providerProperties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(providerProperties.getConnectTimeout())
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(providerProperties.getRequestTimeout());

        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }
}