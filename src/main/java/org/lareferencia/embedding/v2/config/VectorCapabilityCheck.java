package org.lareferencia.embedding.v2.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="app.dvp.enabled", havingValue="true")
public class VectorCapabilityCheck implements ApplicationRunner {
  private final JdbcTemplate jdbc;
  public VectorCapabilityCheck(@Qualifier("coordinationJdbc") JdbcTemplate jdbc) { this.jdbc = jdbc; }

  public static boolean compatible(String version) {
    if (version == null || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+")) return false;
    String[] v = version.split("\\.");
    return Integer.parseInt(v[0]) > 0 || Integer.parseInt(v[1]) >= 8;
  }
  @Override public void run(ApplicationArguments args) {
    var versions = jdbc.queryForList("SELECT extversion FROM pg_extension WHERE extname='vector'",String.class);
    if (versions.size()!=1 || !compatible(versions.getFirst())) {
      throw new IllegalStateException("v2 requires administrator-installed pgvector >= 0.8.0");
    }
  }
}
