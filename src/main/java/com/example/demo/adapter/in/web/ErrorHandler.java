package com.example.demo.adapter.in.web;

import com.example.demo.domain.DomainException;
import com.example.demo.domain.NotFoundException;
import java.net.URI;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps exceptions to RFC 9457 problem responses.
 *
 * <ul>
 *   <li>{@link NotFoundException} → 404, other {@link DomainException} → 422, with the exception message
 *       as detail.
 *   <li>Spring MVC's own exceptions (missing header, validation, malformed body, type mismatch, 405, ...)
 *       → their usual 4xx, via the superclass.
 *   <li>Anything else → generic 500 with an {@code errorId}; the log carries the same id and the stack
 *       trace.
 * </ul>
 *
 * <p>Every problem gets an explicit {@code instance} ({@code urn:uuid:...}); otherwise Spring fills it
 * with the request path, which can contain identifiers. Invalid input should be rejected with Bean
 * Validation or a {@link DomainException}, not a bare {@code IllegalArgumentException} (which is a 500
 * here, because it usually signals a bug).
 */
@RestControllerAdvice
public class ErrorHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) {
        return withInstance(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage()));
    }

    @ExceptionHandler(DomainException.class)
    ProblemDetail businessRule(DomainException e) {
        return withInstance(ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e) {
        var errorId = UUID.randomUUID().toString();
        log.error("Unhandled exception errorId={}", errorId, e);
        var body = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");
        body.setInstance(URI.create("urn:uuid:" + errorId));
        body.setProperty("errorId", errorId);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(
            Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            withInstance(problem);
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private static ProblemDetail withInstance(ProblemDetail body) {
        if (body.getInstance() == null) {
            body.setInstance(URI.create("urn:uuid:" + UUID.randomUUID()));
        }
        return body;
    }
}
