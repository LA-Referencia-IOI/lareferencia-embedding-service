package org.lareferencia.embedding.v2.job;

import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.OptionalLong;
import java.util.random.RandomGenerator;

/** Bounded retry classification and scheduling; never sleeps a worker or logs provider content. */
public final class RetryPolicy {
  private RetryPolicy() { }

  /** Empty means permanent or exhausted. Attempts are counted when an item is claimed. */
  public static OptionalLong delayMillis(int attempts,Integer status,boolean transportFailure,String retryAfter,
                                         Clock clock,RandomGenerator random) {
    if(attempts<1 || attempts>=5 || !(transportFailure || (status!=null && (status==429 || status>=500))))
      return OptionalLong.empty();
    long delay=(1L << (attempts-1))*1000 + random.nextLong(1001);
    long requested=0;
    if(retryAfter!=null && !retryAfter.isBlank()) {
      try { requested=Math.max(0,Math.multiplyExact(Long.parseLong(retryAfter.trim()),1000)); }
      catch(NumberFormatException | ArithmeticException ignored) {
        try {
          Instant date=ZonedDateTime.parse(retryAfter.trim(),DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
          requested=Math.max(0,java.time.Duration.between(clock.instant(),date).toMillis());
        } catch(RuntimeException invalid) { /* Invalid Retry-After falls back to exponential delay. */ }
      }
    }
    return OptionalLong.of(Math.min(300_000,Math.max(delay,requested)));
  }
}
