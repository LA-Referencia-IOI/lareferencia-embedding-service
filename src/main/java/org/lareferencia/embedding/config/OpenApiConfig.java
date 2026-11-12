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
                                                .title("LA Referencia Multi-Store Embeddings API")
                                                .description("REST API for v1 embedding stores/caching and optional v2 document-vector platform resources with HTTP providers, PostgreSQL and pgvector.")
                                                .version("1.0.0")
                                                .contact(new Contact()
                                                                .name("LA Referencia")
                                                                .url("https://www.lareferencia.info"))
                                                .license(new License()
                                                                .name("AGPL-3.0")
                                                                .url("https://www.gnu.org/licenses/agpl-3.0.html")));
        }
        /** Swagger UI uses this order when tagsSorter is not configured. */
        @Bean
        public org.springdoc.core.customizers.OpenApiCustomizer versionedTagOrder() {
                return api -> {
                        var tags = new java.util.LinkedHashMap<String, io.swagger.v3.oas.models.tags.Tag>();
                        if (api.getTags() != null) api.getTags().forEach(tag -> tags.put(tag.getName(), tag));
                        if (api.getPaths() != null) api.getPaths().values().forEach(path -> path.readOperations().forEach(operation -> {
                                if (operation.getTags() != null) operation.getTags().forEach(name ->
                                                tags.computeIfAbsent(name, key -> new io.swagger.v3.oas.models.tags.Tag().name(key)));
                        }));
                        api.setTags(new java.util.ArrayList<>(tags.values()));
                        api.getTags().sort(java.util.Comparator
                                        .comparingInt((io.swagger.v3.oas.models.tags.Tag tag) ->
                                                        tag.getName().startsWith("v2 ") ? 0 :
                                                        tag.getName().startsWith("v1 ") ? 1 : 2)
                                        .thenComparing(io.swagger.v3.oas.models.tags.Tag::getName));
                };
        }

}
