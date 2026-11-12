package org.lareferencia.embedding.v2.operations;

import org.lareferencia.embedding.v2.config.DvpProperties;
import org.lareferencia.embedding.v2.config.VectorCapabilityCheck;
import org.lareferencia.embedding.v2.profile.ProviderRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Read-only diagnostics; details deliberately exclude endpoints, credentials and source content. */
@Component("dvp")
@ConditionalOnProperty(name="app.dvp.enabled",havingValue="true")
public class V2ReadinessIndicator implements HealthIndicator {
  private final JdbcTemplate jdbc;private final DvpProperties properties;private final ProviderRegistry providers;
  public V2ReadinessIndicator(@Qualifier("coordinationJdbc") JdbcTemplate jdbc,DvpProperties properties,ProviderRegistry providers) {
    this.jdbc=jdbc;this.properties=properties;this.providers=providers;
  }
  @Override public Health health() {
    try {
      var versions=jdbc.queryForList("SELECT extversion FROM pg_extension WHERE extname='vector'",String.class);
      if(versions.size()!=1 || !VectorCapabilityCheck.compatible(versions.getFirst()))
        return Health.down().withDetail("reason","compatible_pgvector_unavailable").build();
      return Health.up().withDetail("pgvector_version",versions.getFirst())
          .withDetail("role",properties.getRole().name()).withDetail("workers_enabled",properties.isWorkersEnabled())
          .withDetail("imports_enabled",properties.isImportEnabled()).withDetail("configured_provider_count",providers.configuredCount()).build();
    } catch(RuntimeException e) {return Health.down().withDetail("reason","database_unavailable").build();}
  }
}
