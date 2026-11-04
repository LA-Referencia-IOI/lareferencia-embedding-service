package org.lareferencia.embedding.v2.common;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record V2Error(Instant timestamp, int status, String code, String message,
    String requestId, List<Object> details) {
  public static V2Error of(int status, String code, String message, String requestId) {
    return new V2Error(Instant.now(),status,code,message,requestId,List.of());
  }
}
