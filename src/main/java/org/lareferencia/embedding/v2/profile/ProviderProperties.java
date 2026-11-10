package org.lareferencia.embedding.v2.profile;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Provider credentials are referenced by environment variable name, never stored with a profile. */
@Data
@ConfigurationProperties("app.dvp")
public class ProviderProperties {
  private Map<String,Provider> providers=new LinkedHashMap<>();
  @Data public static class Provider {
    private String type;
    private String endpoint;
    private String secretRef;
    private Duration connectTimeout=Duration.ofSeconds(5);
    private Duration requestTimeout=Duration.ofSeconds(30);
    private int concurrency=4;
    private int batchSize=128;
    @Override public String toString() { return "Provider[redacted]"; }
  }
}
