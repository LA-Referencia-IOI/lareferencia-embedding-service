package org.lareferencia.embedding;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class LAReferenciaEmbeddingServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(LAReferenciaEmbeddingServiceApplication.class, args);
	}
}
