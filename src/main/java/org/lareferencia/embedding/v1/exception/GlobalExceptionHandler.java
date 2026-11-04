package org.lareferencia.embedding.v1.exception;

import java.time.Instant;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import lombok.extern.slf4j.Slf4j;

@RestControllerAdvice(basePackages = "org.lareferencia.embedding.v1")
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(StoreProviderMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleProviderMismatch(StoreProviderMismatchException ex,
            HttpServletRequest request) {
        return buildResponse(HttpStatus.CONFLICT, ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleIntegrity(org.springframework.dao.DataIntegrityViolationException ex,
            HttpServletRequest request) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof org.postgresql.util.PSQLException pg && pg.getServerErrorMessage() != null) {
                String constraint = pg.getServerErrorMessage().getConstraint();
                if (List.of("uk_store_name", "uk_store_model_dimension", "uk_store_name_ci",
                        "uk_store_model_dimension_ci").contains(constraint == null ? "" : constraint)) {
                    return buildResponse(HttpStatus.CONFLICT, "Embedding store already exists", request, List.of());
                }
            }
            cause = cause.getCause();
        }
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected database error", request, List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidationException(
            MethodArgumentNotValidException ex,
            HttpServletRequest request) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .toList();
        log.warn("Validation error path={} details={}", request.getRequestURI(), details);
        return buildResponse(HttpStatus.BAD_REQUEST, "Validation failed", request, details);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadablePayload(
            HttpMessageNotReadableException ex,
            HttpServletRequest request) {
        String message = ex.getMostSpecificCause() != null ? ex.getMostSpecificCause().getMessage() : ex.getMessage();
        log.warn("Unreadable payload path={} message={}", request.getRequestURI(), message);
        return buildResponse(HttpStatus.BAD_REQUEST, "Invalid JSON payload", request, List.of(message));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(
            ConstraintViolationException ex,
            HttpServletRequest request) {
        List<String> details = ex.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .toList();
        log.warn("Constraint violation path={} details={}", request.getRequestURI(), details);
        return buildResponse(HttpStatus.BAD_REQUEST, "Constraint violation", request, details);
    }

    @ExceptionHandler(StoreNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleStoreNotFound(
            StoreNotFoundException ex,
            HttpServletRequest request) {
        return buildResponse(HttpStatus.NOT_FOUND, ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(StoreAlreadyExistsException.class)
    public ResponseEntity<ApiErrorResponse> handleDuplicateStore(
            StoreAlreadyExistsException ex,
            HttpServletRequest request) {
        return buildResponse(HttpStatus.CONFLICT, ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidRequest(
            InvalidRequestException ex,
            HttpServletRequest request) {
        log.warn("Invalid request path={} message={}", request.getRequestURI(), ex.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(EmbeddingProviderException.class)
    public ResponseEntity<ApiErrorResponse> handleProviderFailure(
            EmbeddingProviderException ex,
            HttpServletRequest request) {
        log.error("Embedding provider error path={} message={}", request.getRequestURI(), ex.getMessage(), ex);
        return buildResponse(HttpStatus.BAD_GATEWAY, ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(
            Exception ex,
            HttpServletRequest request) {
        log.error("Unexpected application error path={} message={}", request.getRequestURI(), ex.getMessage(), ex);
        String detail = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected internal error", request,
                List.of(detail));
    }

    private ResponseEntity<ApiErrorResponse> buildResponse(
            HttpStatus status,
            String message,
            HttpServletRequest request,
            List<String> details) {
        ApiErrorResponse body = new ApiErrorResponse(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                request.getRequestURI(),
                details);
        return ResponseEntity.status(status).body(body);
    }

    private String formatFieldError(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }
}
