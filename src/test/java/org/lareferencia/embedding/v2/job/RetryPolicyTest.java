package org.lareferencia.embedding.v2.job;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Random;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {
  private final Clock clock=Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"),ZoneOffset.UTC);
  @Test void retriesOnlyTransientFailuresWithBoundedBackoffAndRetryAfter() {
    for(int attempt=1;attempt<=4;attempt++) {
      long delay=RetryPolicy.delayMillis(attempt,503,false,null,clock,new Random(1)).orElseThrow();
      long base=(1L << (attempt-1))*1000;assertTrue(delay>=base && delay<=base+1000);
    }
    assertTrue(RetryPolicy.delayMillis(1,422,false,null,clock,new Random()).isEmpty());
    assertTrue(RetryPolicy.delayMillis(5,429,false,null,clock,new Random()).isEmpty());
    assertEquals(120_000,RetryPolicy.delayMillis(1,429,false,"120",clock,new Random()).orElseThrow());
    assertEquals(300_000,RetryPolicy.delayMillis(1,503,false,"9999",clock,new Random()).orElseThrow());
    assertEquals(30_000,RetryPolicy.delayMillis(1,null,true,"Tue, 10 Nov 2026 00:00:30 GMT",clock,new Random()).orElseThrow());
    assertTrue(RetryPolicy.delayMillis(1,null,true,"invalid",clock,new Random()).isPresent());
  }
}
