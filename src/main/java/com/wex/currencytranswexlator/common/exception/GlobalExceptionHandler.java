package com.wex.currencytranswexlator.common.exception;

import com.wex.currencytranswexlator.conversion.ConversionService;
import com.wex.currencytranswexlator.exchange.provider.TreasuryApiClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Centralized exception handler using RFC 7807 ProblemDetail responses.
 *
 * Uses Spring Boot's auto-configured ObjectMapper (OOTB) rather than a custom
 * ObjectMapper bean. ProblemDetail.setProperty() relies on Jackson @JsonAnyGetter
 * to flatten additional properties into the top-level JSON, which requires the
 * auto-configured ObjectMapper's full module registration to work correctly.
 *
 * The only Jackson customization needed (WRITE_BIGDECIMAL_AS_PLAIN) is set via
 * spring.jackson.serialization.write-bigdecimal-as-plain in application.yml.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /** 400 - Bean Validation failure with per-field error messages */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidationErrors(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
            .collect(Collectors.toMap(
                FieldError::getField,
                fe -> fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "Invalid value",
                (a, b) -> a
            ));

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST, "Request validation failed");
        problem.setProperty("fieldErrors", fieldErrors);
        problem.setProperty("timestamp", Instant.now().toString());
        return ResponseEntity.badRequest().body(problem);
    }

    /** 400 - Missing required header */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ProblemDetail> handleMissingHeader(MissingRequestHeaderException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST, "Required header missing: " + ex.getHeaderName());
        problem.setProperty("timestamp", Instant.now().toString());
        return ResponseEntity.badRequest().body(problem);
    }

    /** 404 - Transaction not found */
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(NotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setProperty("timestamp", Instant.now().toString());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    /** 409 - Idempotency key conflict */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ProblemDetail> handleConflict(ConflictException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setProperty("timestamp", Instant.now().toString());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * 422 - No exchange rate available within 6 months.
     * Required message: "Purchase cannot be converted to the target currency."
     */
    @ExceptionHandler(ConversionService.RateNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleRateNotFound(ConversionService.RateNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        problem.setProperty("timestamp", Instant.now().toString());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(problem);
    }

    /** 503 - Treasury API unavailable and no local rate available */
    @ExceptionHandler(TreasuryApiClient.TreasuryApiUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleTreasuryUnavailable(
        TreasuryApiClient.TreasuryApiUnavailableException ex) {
        log.warn("Treasury API unavailable: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Exchange rate service temporarily unavailable. Please retry shortly.");
        problem.setProperty("retryAfterSeconds", 30);
        problem.setProperty("timestamp", Instant.now().toString());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .header("Retry-After", "30")
            .body(problem);
    }

    /** 500 - Catch-all */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "An unexpected error occurred. Please contact support.");
        problem.setProperty("timestamp", Instant.now().toString());
        return ResponseEntity.internalServerError().body(problem);
    }
}
