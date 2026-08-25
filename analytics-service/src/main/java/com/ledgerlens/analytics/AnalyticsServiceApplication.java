package com.ledgerlens.analytics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * analytics-service: stateless computation over data it does not own.
 *
 * <p>Holds no database. Fetches transactions from transaction-service and
 * derives return, volatility, maximum drawdown and the Sharpe ratio. Losing an
 * instance loses nothing but in-flight requests.
 */
@SpringBootApplication
public class AnalyticsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AnalyticsServiceApplication.class, args);
    }
}
