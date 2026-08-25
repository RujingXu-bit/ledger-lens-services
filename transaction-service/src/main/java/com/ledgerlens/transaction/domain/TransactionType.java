package com.ledgerlens.transaction.domain;

/**
 * The subset of transaction types this project models.
 *
 * <p>Deliberately small. It covers what the analytics need — securities bought
 * and sold, income received, and cash moving in and out of the portfolio — and
 * stops there. Corporate actions, transfers in kind, FX conversions and short
 * positions all exist in the real product and are out of scope here.
 */
public enum TransactionType {

    /** Securities bought: cash leaves the portfolio. */
    BUY(true),

    /** Securities sold: cash arrives. */
    SELL(true),

    /** Income from a held security: cash arrives, position unchanged. */
    DIVIDEND(false),

    /** Cash paid into the portfolio from outside. */
    DEPOSIT(false),

    /** Cash taken out of the portfolio. */
    WITHDRAWAL(false);

    private final boolean securityTrade;

    TransactionType(boolean securityTrade) {
        this.securityTrade = securityTrade;
    }

    /** True when the type changes a position, and therefore needs quantity and price. */
    public boolean isSecurityTrade() {
        return securityTrade;
    }

    /** True when the type refers to a security, whether or not it moves a position. */
    public boolean requiresSymbol() {
        return securityTrade || this == DIVIDEND;
    }
}
