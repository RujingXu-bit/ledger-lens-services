package com.ledgerlens.analytics.web;

import com.ledgerlens.analytics.client.UpstreamUnavailableException;
import com.ledgerlens.analytics.domain.InsufficientDataException;
import com.ledgerlens.analytics.domain.MissingPriceException;
import com.ledgerlens.analytics.service.PortfolioNotFoundException;
import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * RFC 9457 problem responses, matching transaction-service's format so that a
 * client speaks one error language to the whole system.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final URI UPSTREAM_UNAVAILABLE = URI.create("https://ledgerlens.dev/problems/upstream-unavailable");
    private static final URI NOT_FOUND = URI.create("https://ledgerlens.dev/problems/not-found");
    private static final URI INSUFFICIENT_DATA = URI.create("https://ledgerlens.dev/problems/insufficient-data");
    private static final URI MISSING_PRICE = URI.create("https://ledgerlens.dev/problems/missing-price");

    /**
     * 503, with {@code Retry-After}. Not 500: nothing is wrong with this
     * service, and the distinction tells a client whether retrying is
     * pointless. The upstream's own message is not echoed — a caller of
     * analytics-service has no business seeing transaction-service's internals.
     */
    @ExceptionHandler(UpstreamUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleUpstreamUnavailable(UpstreamUnavailableException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "The transaction service could not be reached, and no recent result was available to fall back on");
        problem.setTitle("Upstream unavailable");
        problem.setType(UPSTREAM_UNAVAILABLE);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "30")
                .body(problem);
    }

    @ExceptionHandler(PortfolioNotFoundException.class)
    public ProblemDetail handlePortfolioNotFound(PortfolioNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Portfolio not found");
        problem.setType(NOT_FOUND);
        return problem;
    }

    /** The data exists but cannot support the statistics: 422, not 500. */
    @ExceptionHandler(InsufficientDataException.class)
    public ProblemDetail handleInsufficientData(InsufficientDataException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        problem.setTitle("Not enough data to measure performance");
        problem.setType(INSUFFICIENT_DATA);
        return problem;
    }

    @ExceptionHandler(MissingPriceException.class)
    public ProblemDetail handleMissingPrice(MissingPriceException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        problem.setTitle("Missing price data");
        problem.setType(MISSING_PRICE);
        return problem;
    }
}
