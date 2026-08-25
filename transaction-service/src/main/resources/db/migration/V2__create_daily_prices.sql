-- Reference data, not ledger data - and the difference drives every decision
-- below. A transaction is an event that happened once and is never edited. A
-- closing price is a fact about the world that a vendor can restate: prices get
-- corrected, splits get applied retroactively. So this table is upserted, while
-- `transactions` is append-only.

CREATE TABLE daily_prices (
    symbol      VARCHAR(16)   NOT NULL,

    -- A DATE, not a TIMESTAMPTZ. A closing price belongs to a trading day, not
    -- to an instant; storing 22:00Z would invite a timezone conversion to move
    -- a price to the wrong day.
    price_date  DATE          NOT NULL,

    close_price NUMERIC(19, 8) NOT NULL,
    currency    VARCHAR(3)    NOT NULL,
    updated_at  TIMESTAMPTZ   NOT NULL,

    -- A natural composite key, unlike transactions' surrogate UUID. There can
    -- only ever be one close for a symbol on a day, so the database should be
    -- the thing that knows it - not a uniqueness check in application code that
    -- races with itself under concurrency.
    CONSTRAINT daily_prices_pk PRIMARY KEY (symbol, price_date),

    CONSTRAINT daily_prices_non_negative CHECK (close_price >= 0),
    CONSTRAINT daily_prices_currency_iso CHECK (currency ~ '^[A-Z]{3}$')
);

-- The primary key already covers (symbol, price_date), which serves
-- "one symbol over a date range". analytics-service asks for several symbols
-- over one range, so it also needs the date-leading order.
CREATE INDEX idx_daily_prices_date ON daily_prices (price_date, symbol);
