package org.lareferencia.embedding.v2.operations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.lareferencia.embedding.v2.config.DvpProperties;
import org.lareferencia.embedding.v2.profile.ProviderRegistry;
import org.springframework.boot.actuate.health.Status;
import org.springframework.jdbc.core.JdbcTemplate;

class V2ReadinessIndicatorTest {
  @Test void reportsSanitizedExtensionAndProviderConfiguration() {
    var jdbc=mock(JdbcTemplate.class);var providers=mock(ProviderRegistry.class);var props=new DvpProperties();
    when(jdbc.queryForList(anyString(),eq(String.class))).thenReturn(List.of("0.8.0"));when(providers.configuredCount()).thenReturn(2);
    var health=new V2ReadinessIndicator(jdbc,props,providers).health();
    assertEquals(Status.UP,health.getStatus());assertEquals("0.8.0",health.getDetails().get("pgvector_version"));assertEquals(2,health.getDetails().get("configured_provider_count"));
    assertFalse(health.toString().contains("http"));
  }
  @Test void reportsUnavailableOrIncompatibleDatabaseWithoutLeakingError() {
    var jdbc=mock(JdbcTemplate.class);var props=new DvpProperties();var indicator=new V2ReadinessIndicator(jdbc,props,mock(ProviderRegistry.class));
    when(jdbc.queryForList(anyString(),eq(String.class))).thenReturn(List.of("0.7.4"));assertEquals(Status.DOWN,indicator.health().getStatus());
    when(jdbc.queryForList(anyString(),eq(String.class))).thenThrow(new IllegalStateException("sensitive-database-address"));
    var failed=indicator.health();assertEquals(Status.DOWN,failed.getStatus());assertFalse(failed.toString().contains("sensitive-database-address"));
  }
}
