package com.sa.agentic.orchestrator.api;

import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps orchestrator failures to problem+json: unknown ids 404, rule violations 400, wrong-state operations 409. */
@RestControllerAdvice(assignableTypes = OrchestratorController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class OrchestratorExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorExceptionHandler.class);

    @ExceptionHandler(NoSuchElementException.class)
    ProblemDetail notFound(NoSuchElementException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    ProblemDetail badRequest(Exception e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                e instanceof IllegalArgumentException ? e.getMessage() : "request body is missing or invalid");
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail conflict(IllegalStateException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("orchestrator API error", e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "unexpected error");
    }
}
