package com.ledgerlens.transaction.domain;

import java.math.BigDecimal;

/**
 * A position: how many units of a symbol a portfolio holds at a point in time.
 *
 * <p>There is no holdings table. A holding is a fold over the transaction log —
 * buys added, sells subtracted — computed on demand by a GROUP BY.
 *
 * <p>The alternative, a maintained {@code holdings} table, means writing two
 * places on every transaction. Any failure between the two writes, or any
 * insert that bypasses the service, leaves the two permanently inconsistent and
 * nothing ever notices. Deriving costs a scan of one portfolio's transactions,
 * which the (portfolio_id, executed_at) index makes cheap at this size. If it
 * ever stops being cheap the answer is a materialised view or an incremental
 * snapshot — a decision to make with measurements, not in advance.
 */
public record Holding(String symbol, BigDecimal quantity) {
}
