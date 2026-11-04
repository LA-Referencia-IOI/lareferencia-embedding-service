package org.lareferencia.embedding.config;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import org.lareferencia.embedding.v1.exception.ApiErrorResponse;
import org.lareferencia.embedding.v2.common.V2Error;
import org.lareferencia.embedding.v2.common.V2RequestFilter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Unmapped routes have no versioned controller to select its scoped advice. */
@Order(Ordered.LOWEST_PRECEDENCE)
@RestControllerAdvice
public class RoutingExceptionHandler {
  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<?> missing(NoResourceFoundException error, HttpServletRequest request) {
    Object requestId = request.getAttribute(V2RequestFilter.REQUEST_ID);
    if (requestId != null) {
      return ResponseEntity.status(404).body(V2Error.of(404,"not_found","Resource not found",requestId.toString()));
    }
    return ResponseEntity.status(404).body(new ApiErrorResponse(Instant.now(),404,"Not Found",
        "Resource not found",request.getRequestURI(),List.of(error.getMessage())));
  }
}
