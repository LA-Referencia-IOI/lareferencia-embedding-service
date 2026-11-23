package org.lareferencia.embedding.v2.search;

import static org.junit.jupiter.api.Assertions.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.lareferencia.embedding.v2.common.V2RequestFilter;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class SearchTimingsTest {
  @Test
  void recordsSuccessfulAndFailedStagesSeparately() {
    var metrics = new SimpleMeterRegistry();
    var timings = new SearchTimings(metrics, "exact", "text");
    assertEquals("vector", timings.measure("embedding", () -> "vector"));
    assertThrows(IllegalStateException.class, () -> timings.measure("database", () -> {
      throw new IllegalStateException("failed");
    }));
    assertEquals(1, metrics.get("dvp.search.stage.duration")
        .tags("stage", "embedding", "mode", "exact", "outcome", "success").timer().count());
    assertEquals(1, metrics.get("dvp.search.stage.duration")
        .tags("stage", "database", "mode", "exact", "outcome", "error").timer().count());
    assertNull(metrics.find("dvp.search.stage.duration").tags("stage", "database", "outcome", "success").timer());
    timings.log("error");
  }

  @Test
  void logsRequestIdAndMarksSkippedEmbeddingForVectorQueries() {
    var logger = (Logger) LoggerFactory.getLogger(SearchTimings.class);
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    var request = new MockHttpServletRequest();
    request.setAttribute(V2RequestFilter.REQUEST_ID, "request-123");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    try {
      var timings = new SearchTimings(new SimpleMeterRegistry(), "exact", "vector");
      timings.measure("database", () -> "result");
      timings.log("success");
      var message = appender.list.getFirst().getFormattedMessage();
      assertTrue(message.contains("request_id=request-123"));
      assertTrue(message.contains("query_type=vector"));
      assertTrue(message.contains("embedding_ms=not_run"));
      assertTrue(message.contains("database_ms="));
    } finally {
      RequestContextHolder.resetRequestAttributes();
      logger.detachAppender(appender);
      appender.stop();
    }
  }
}
