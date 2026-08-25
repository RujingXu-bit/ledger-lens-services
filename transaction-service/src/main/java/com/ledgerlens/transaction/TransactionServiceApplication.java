package com.ledgerlens.transaction;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * transaction-service: the system of record.
 *
 * <p>It is the only component in the system permitted to touch the transactions
 * database. Everything else asks it over HTTP. That single rule is what makes
 * this a microservice rather than a shared-database distributed monolith.
 */
@SpringBootApplication
public class TransactionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TransactionServiceApplication.class, args);
    }
}
