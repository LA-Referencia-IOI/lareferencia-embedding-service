package org.lareferencia.embedding.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;

@Configuration
public class OpenApiConfig {

        @Bean
        public OpenAPI LAReferenciaEmbeddingServiceApplicationOpenAPI() {
                return new OpenAPI()
                                .info(new Info()
                                                .title("LA Referencia Multi-Storage Embeddings API")
                                                .description("REST API for managing embedding storages and generating persistent cached embeddings with Spring AI, Ollama, PostgreSQL, and pgvector.")
                                                .version("1.0.0")
                                                .contact(new Contact()
                                                                .name("LA Referencia")
                                                                .url("https://www.lareferencia.info"))
                                                .license(new License()
                                                                .name("AGPL-3.0")
                                                                .url("https://www.gnu.org/licenses/agpl-3.0.html")));
        }
}
