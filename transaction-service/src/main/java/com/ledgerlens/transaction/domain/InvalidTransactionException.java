package com.ledgerlens.transaction.domain;

/**
 * A request that is well-formed but describes a transaction that cannot exist —
 * a BUY with no symbol, a DEPOSIT carrying a quantity.
 *
 * <p>Distinct from a Bean Validation failure on purpose. Bean Validation checks
 * the <em>shape</em> of the payload (is this field present, is this number
 * positive) and produces 400. This is a breach of a <em>domain rule</em>, which
 * the API reports as 422 Unprocessable Content: the syntax was fine, the
 * meaning was not.
 */
public class InvalidTransactionException extends RuntimeException {

    public InvalidTransactionException(String message) {
        super(message);
    }
}
