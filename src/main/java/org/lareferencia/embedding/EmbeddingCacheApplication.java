package org.lareferencia.embedding;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class EmbeddingCacheApplication {

	public static void main(String[] args) {
		SpringApplication.run(EmbeddingCacheApplication.class, args);
	}
}
