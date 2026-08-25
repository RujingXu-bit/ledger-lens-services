package com.ledgerlens.transaction.web;

import com.ledgerlens.transaction.domain.InvalidTransactionException;
import com.ledgerlens.transaction.domain.TransactionNotFoundException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns exceptions into RFC 9457 {@code application/problem+json} responses.
 *
 * <p>One machine-readable error format for the whole service, so
 * analytics-service can branch on {@code status} and {@code type} instead of
 * pattern-matching English prose. Extending {@link ResponseEntityExceptionHandler}
 * inherits Spring's handling of malformed JSON, wrong content types and
 * unsupported methods, which otherwise leak stack traces or bare HTML.
 *
 * <p>Nothing here echoes an exception's raw message for unexpected failures.
 * A stack trace or a SQL fragment in a response body is an information leak.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final URI VALIDATION_FAILED = URI.create("https://ledgerlens.dev/problems/validation-failed");
    private static final URI INVALID_TRANSACTION = URI.create("https://ledgerlens.dev/problems/invalid-transaction");
    private static final URI NOT_FOUND = URI.create("https://ledgerlens.dev/problems/not-found");

    /**
     * A payload whose shape is wrong: 400. Every field violation is reported at
     * once, because a client that has to fix one error per round trip will make
     * as many round trips as it has mistakes.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> errors.merge(error.getField(), error.getDefaultMessage(), (a, b) -> a + "; " + b));
        ex.getBindingResult().getGlobalErrors()
                .forEach(error -> errors.put(error.getObjectName(), error.getDefaultMessage()));

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "The request body failed validation");
        problem.setTitle("Validation failed");
        problem.setType(VALIDATION_FAILED);
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    /** Query and path parameter violations, raised by {@code @Validated} on the controller. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            errors.put(violation.getPropertyPath().toString(), violation.getMessage());
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "One or more request parameters are invalid");
        problem.setTitle("Validation failed");
        problem.setType(VALIDATION_FAILED);
        problem.setProperty("errors", errors);
        return problem;
    }

    /**
     * A well-formed request describing an impossible transaction: 422, not 400.
     * The distinction tells the caller whether to fix their serialiser or their
     * understanding of the domain.
     */
    @ExceptionHandler(InvalidTransactionException.class)
    public ProblemDetail handleInvalidTransaction(InvalidTransactionException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        problem.setTitle("Invalid transaction");
        problem.setType(INVALID_TRANSACTION);
        return problem;
    }

    @ExceptionHandler(TransactionNotFoundException.class)
    public ProblemDetail handleNotFound(TransactionNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Transaction not found");
        problem.setType(NOT_FOUND);
        problem.setProperty("transactionId", ex.getId().toString());
        return problem;
    }
}
