package com.ledgerlens.transaction.domain;

import java.util.UUID;

public class TransactionNotFoundException extends RuntimeException {

    private final UUID id;

    public TransactionNotFoundException(UUID id) {
        super("No transaction with id " + id);
        this.id = id;
    }

    public UUID getId() {
        return id;
    }
}
