package io.github.andres.pigeon.infrastructure.adapter.in.rest;

import io.github.andres.pigeon.domain.exception.DomainException;
import io.github.andres.pigeon.domain.exception.DuplicateEventException;
import io.github.andres.pigeon.domain.exception.InvalidStateTransitionException;
import io.github.andres.pigeon.domain.exception.NotificationNotFoundException;
import io.github.andres.pigeon.domain.exception.SensitiveDataException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        boolean isSensitiveData = ex.getBindingResult().getAllErrors().stream()
                .anyMatch(err -> err.getDefaultMessage() != null && err.getDefaultMessage().contains("Sensitive data"));

        String typeSuffix = isSensitiveData ? "sensitive-data-rejected" : "validation-error";
        String title = isSensitiveData ? "Sensitive Data Detected" : "Validation Failed";

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                isSensitiveData ? "Full card numbers or long account numbers were detected and rejected."
                        : "One or more request parameters failed validation."
        );
        problemDetail.setType(URI.create("https://pigeon.bank.internal/errors/" + typeSuffix));
        problemDetail.setTitle(title);
        problemDetail.setProperty("timestamp", Instant.now());

        Map<String, String> fieldErrors = new HashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(fieldError.getField(), fieldError.getDefaultMessage());
        }
        if (!fieldErrors.isEmpty()) {
            problemDetail.setProperty("errors", fieldErrors);
        }

        return ResponseEntity.badRequest().body(problemDetail);
    }

    @ExceptionHandler(SensitiveDataException.class)
    public ResponseEntity<ProblemDetail> handleSensitiveDataException(SensitiveDataException ex) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problemDetail.setType(URI.create("https://pigeon.bank.internal/errors/sensitive-data-rejected"));
        problemDetail.setTitle("Sensitive Data Rejected");
        problemDetail.setProperty("timestamp", Instant.now());
        return ResponseEntity.badRequest().body(problemDetail);
    }

    @ExceptionHandler(DuplicateEventException.class)
    public ResponseEntity<ProblemDetail> handleDuplicateEventException(DuplicateEventException ex) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problemDetail.setType(URI.create("https://pigeon.bank.internal/errors/idempotency-key-conflict"));
        problemDetail.setTitle("Idempotency Key Conflict");
        problemDetail.setProperty("timestamp", Instant.now());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problemDetail);
    }

    @ExceptionHandler(NotificationNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(NotificationNotFoundException ex) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problemDetail.setType(URI.create("https://pigeon.bank.internal/errors/not-found"));
        problemDetail.setTitle("Notification Not Found");
        problemDetail.setProperty("timestamp", Instant.now());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problemDetail);
    }

    @ExceptionHandler(InvalidStateTransitionException.class)
    public ResponseEntity<ProblemDetail> handleInvalidStateTransition(InvalidStateTransitionException ex) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problemDetail.setType(URI.create("https://pigeon.bank.internal/errors/invalid-state-transition"));
        problemDetail.setTitle("Invalid State Transition");
        problemDetail.setProperty("timestamp", Instant.now());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problemDetail);
    }

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ProblemDetail> handleDomainException(DomainException ex) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problemDetail.setType(URI.create("https://pigeon.bank.internal/errors/domain-error"));
        problemDetail.setTitle("Domain Rule Violation");
        problemDetail.setProperty("timestamp", Instant.now());
        return ResponseEntity.badRequest().body(problemDetail);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGenericException(Exception ex) {
        log.error("Unhandled internal server error: ", ex);
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected internal error occurred. Please contact system support."
        );
        problemDetail.setType(URI.create("https://pigeon.bank.internal/errors/internal-error"));
        problemDetail.setTitle("Internal Server Error");
        problemDetail.setProperty("timestamp", Instant.now());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problemDetail);
    }
}
