-- The ledger. One row per thing that happened to a portfolio, never updated,
-- never deleted. Holdings and every analytics figure are derived from it.

CREATE TABLE transactions (
    id             UUID          PRIMARY KEY,
    portfolio_id   UUID          NOT NULL,
    type           VARCHAR(16)   NOT NULL,

    -- Security-trade fields. NULL for pure cash movements.
    symbol         VARCHAR(16),
    quantity       NUMERIC(19, 8),
    price_per_unit NUMERIC(19, 8),

    fee            NUMERIC(19, 4) NOT NULL DEFAULT 0,

    -- The signed cash impact of this transaction: negative when money leaves
    -- the portfolio, positive when it arrives. Derived by the application, so
    -- that "cash balance" is one SUM instead of five special cases.
    cash_amount    NUMERIC(19, 4) NOT NULL,

    currency       VARCHAR(3)    NOT NULL,
    executed_at    TIMESTAMPTZ   NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL,

    CONSTRAINT transactions_type_known CHECK (
        type IN ('BUY', 'SELL', 'DIVIDEND', 'DEPOSIT', 'WITHDRAWAL')
    ),

    -- The same invariants the domain model enforces, restated where they cannot
    -- be bypassed. Application code is one way into this table today; a
    -- migration script or a support engineer with psql is another.
    CONSTRAINT transactions_shape CHECK (
        (type IN ('BUY', 'SELL')
             AND symbol IS NOT NULL
             AND quantity IS NOT NULL AND quantity > 0
             AND price_per_unit IS NOT NULL AND price_per_unit >= 0)
        OR (type = 'DIVIDEND'
             AND symbol IS NOT NULL
             AND quantity IS NULL AND price_per_unit IS NULL)
        OR (type IN ('DEPOSIT', 'WITHDRAWAL')
             AND symbol IS NULL
             AND quantity IS NULL AND price_per_unit IS NULL)
    ),

    CONSTRAINT transactions_fee_non_negative CHECK (fee >= 0),
    CONSTRAINT transactions_currency_iso CHECK (currency ~ '^[A-Z]{3}$')
);

-- Every read this service serves is "one portfolio, in time order, optionally
-- within a window" - which is exactly this index. analytics-service will lean
-- on it hard from day 5.
CREATE INDEX idx_transactions_portfolio_executed_at
    ON transactions (portfolio_id, executed_at);
