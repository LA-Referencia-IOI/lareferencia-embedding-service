package org.lareferencia.embedding.v2.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingRequestHeaderException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages="org.lareferencia.embedding.v2")
public class V2ExceptionHandler {
  @ExceptionHandler(V2Exception.class)
  ResponseEntity<V2Error> domain(V2Exception e, HttpServletRequest request) {
    var builder = ResponseEntity.status(e.status());
    if (e.status()==429 || e.code().equals("idempotency_in_progress")) builder.header("Retry-After","1");
    return builder.body(V2Error.of(e.status(),e.code(),e.getMessage(),requestId(request)));
  }
  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<V2Error> unreadable(HttpMessageNotReadableException e, HttpServletRequest request) {
    for (Throwable cause=e; cause!=null; cause=cause.getCause()) {
      if (cause instanceof V2Exception v) return domain(v,request);
    }
    return domain(V2Exception.badRequest("Invalid JSON or unknown fields"), request);
  }
  @ExceptionHandler(MissingRequestHeaderException.class)
  ResponseEntity<V2Error> missingHeader(MissingRequestHeaderException e, HttpServletRequest request) {
    String message = "Idempotency-Key".equalsIgnoreCase(e.getHeaderName())
        ? "Idempotency-Key header is required"
        : "A required request header is missing";
    return domain(V2Exception.badRequest(message), request);
  }
  @ExceptionHandler({MethodArgumentNotValidException.class, MethodArgumentTypeMismatchException.class,
      org.springframework.web.bind.MissingServletRequestParameterException.class,
      org.springframework.web.bind.ServletRequestBindingException.class})
  ResponseEntity<V2Error> invalid(Exception e, HttpServletRequest request) {
    return domain(V2Exception.badRequest("Invalid or missing request parameters"),request);
  }
  @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
  ResponseEntity<V2Error> method(Exception e, HttpServletRequest request) {
    return domain(new V2Exception(405,"method_not_allowed","Method not supported"),request);
  }
  @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
  ResponseEntity<V2Error> media(Exception e, HttpServletRequest request) {
    return domain(new V2Exception(415,"unsupported_media_type","JSON content type is required"),request);
  }
  @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
  ResponseEntity<V2Error> uploadTooLarge(Exception e,HttpServletRequest request) {
    return domain(new V2Exception(413,"file_too_large","Import file byte limit exceeded"),request);
  }
  @ExceptionHandler(org.springframework.web.multipart.support.MissingServletRequestPartException.class)
  ResponseEntity<V2Error> missingPart(Exception e,HttpServletRequest request) {
    return domain(V2Exception.badRequest("Import file and options parts are required"),request);
  }
  @ExceptionHandler({DataAccessException.class, org.springframework.transaction.TransactionException.class})
  ResponseEntity<V2Error> database(Exception e, HttpServletRequest request) {
    return domain(new V2Exception(503,"database_unavailable","Database operation unavailable"),request);
  }
  @ExceptionHandler(Exception.class)
  ResponseEntity<V2Error> unexpected(Exception e, HttpServletRequest request) {
    return domain(new V2Exception(500,"internal_error","Unexpected internal error"),request);
  }
  private String requestId(HttpServletRequest request) {
    Object id=request.getAttribute(V2RequestFilter.REQUEST_ID);
    return id==null ? java.util.UUID.randomUUID().toString() : id.toString();
  }
}
