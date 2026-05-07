package org.lareferencia.embedding.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI embeddingCacheOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("LA Referencia Embedding Cache API")
                        .description("High-performance deterministic embedding cache for large-scale indexing pipelines.")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("LA Referencia")
                                .url("https://www.lareferencia.info"))
                        .license(new License()
                                .name("MIT License")
                                .url("https://opensource.org/licenses/MIT")));
    }
}
