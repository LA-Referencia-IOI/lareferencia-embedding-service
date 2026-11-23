package org.lareferencia.embedding.v2.search;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.lareferencia.embedding.v2.common.V2RequestFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Per-request timings; never records the query or its vector. */
final class SearchTimings {
  private static final Logger log = LoggerFactory.getLogger(SearchTimings.class);
  private final MeterRegistry metrics;
  private final String mode;
  private final String queryType;
  private final long started = System.nanoTime();
  private Long embeddingNanos;
  private Long databaseNanos;

  SearchTimings(MeterRegistry metrics, String mode, String queryType) {
    this.metrics = metrics;
    this.mode = mode;
    this.queryType = queryType;
  }

  <T> T measure(String stage, Supplier<T> action) {
    long start = System.nanoTime();
    String outcome = "success";
    try {
      return action.get();
    } catch (RuntimeException e) {
      outcome = "error";
      throw e;
    } finally {
      long elapsed = System.nanoTime() - start;
      if (stage.equals("embedding")) embeddingNanos = elapsed;
      else if (stage.equals("database")) databaseNanos = elapsed;
      metrics.timer("dvp.search.stage.duration", "stage", stage, "mode", mode, "outcome", outcome)
          .record(elapsed, TimeUnit.NANOSECONDS);
    }
  }

  void log(String outcome) {
    Object requestId = null;
    if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)
      requestId = attributes.getRequest().getAttribute(V2RequestFilter.REQUEST_ID);
    log.info(
        "v2 search request_id={} mode={} query_type={} outcome={} embedding_ms={} database_ms={} total_ms={}",
        requestId,
        mode,
        queryType,
        outcome,
        millis(embeddingNanos),
        millis(databaseNanos),
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
  }

  private static Object millis(Long nanos) {
    return nanos == null ? "not_run" : TimeUnit.NANOSECONDS.toMillis(nanos);
  }
}
