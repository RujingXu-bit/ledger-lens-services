package com.ledgerlens.analytics.domain;

/**
 * analytics-service's own view of what a transaction can be.
 *
 * <p>This enum is a deliberate duplicate of the one in transaction-service, not
 * an import from a shared module. Sharing it would make one service's
 * serialisation format the other's compile-time dependency, and the two could
 * no longer be released independently — which is the only thing the split
 * between them was bought for.
 *
 * <p>What matters here is not the same as what matters there. transaction-service
 * cares which fields a type requires; this service cares only whether a type
 * moves a position, moves money in or out from outside, or is income.
 */
public enum TransactionKind {

    BUY(PositionEffect.INCREASE, FlowKind.INTERNAL),
    SELL(PositionEffect.DECREASE, FlowKind.INTERNAL),

    /**
     * Income. Emphatically <em>not</em> an external flow: a dividend is return
     * the portfolio earned, and netting it out would erase the performance it
     * represents.
     */
    DIVIDEND(PositionEffect.NONE, FlowKind.INTERNAL),

    /** Money arriving from outside. Raises net asset value without being return. */
    DEPOSIT(PositionEffect.NONE, FlowKind.EXTERNAL),

    /** Money leaving. Lowers net asset value without being a loss. */
    WITHDRAWAL(PositionEffect.NONE, FlowKind.EXTERNAL);

    public enum PositionEffect { INCREASE, DECREASE, NONE }

    private enum FlowKind { EXTERNAL, INTERNAL }

    private final PositionEffect positionEffect;
    private final FlowKind flowKind;

    TransactionKind(PositionEffect positionEffect, FlowKind flowKind) {
        this.positionEffect = positionEffect;
        this.flowKind = flowKind;
    }

    public PositionEffect positionEffect() {
        return positionEffect;
    }

    /**
     * True when this transaction changes net asset value for a reason that is
     * not performance, and must therefore be removed from the day's return.
     */
    public boolean isExternalCashFlow() {
        return flowKind == FlowKind.EXTERNAL;
    }
}
