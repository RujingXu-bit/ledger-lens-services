#!/usr/bin/env python3
"""An independent second implementation of the performance maths, in Python.

Why this exists: the Java unit tests prove the calculator does what its author
thought it should. They cannot catch a misunderstanding baked into both the code
and the tests, because the same person wrote both. A separate implementation,
written from the formulas rather than from the Java, run over the same live
data, can - and on real data at real scale rather than on three hand-built
valuation points.

Agreement is weak-but-real evidence. Disagreement is a definite bug in one of
them, which is the outcome worth paying for.

    python3 scripts/crosscheck-metrics.py <portfolio-id> [--base-url http://localhost:8081]
"""
import argparse
import json
import math
import urllib.request
from collections import defaultdict
from datetime import date, datetime

TRADING_DAYS_PER_YEAR = 252
DAYS_PER_YEAR = 365.0


def money_weighted_return(series):
    """XIRR by bisection, matching the Java implementation's conventions.

    Signs are from the investor's side: the opening value and every deposit are
    outflows, withdrawals and the closing value are inflows. The flow on the
    first day is skipped because the opening value already contains it.
    """
    start = series[0][0]
    flows = [(0.0, -series[0][1])]
    for day, _, external in series[1:]:
        if external:
            flows.append(((day - start).days / DAYS_PER_YEAR, -external))
    flows.append((((series[-1][0]) - start).days / DAYS_PER_YEAR, series[-1][1]))

    def npv(rate):
        return sum(amount / (1 + rate) ** years for years, amount in flows)

    low, high = -0.9999, 100.0
    if npv(low) * npv(high) > 0:
        return None
    for _ in range(200):
        mid = (low + high) / 2
        if npv(low) * npv(mid) <= 0:
            high = mid
        else:
            low = mid
    return (low + high) / 2


def get(base_url, path):
    with urllib.request.urlopen(base_url + path) as response:
        return json.loads(response.read())


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("portfolio_id")
    parser.add_argument("--base-url", default="http://localhost:8081")
    parser.add_argument("--risk-free-rate", type=float, default=0.0)
    args = parser.parse_args()
    base = args.base_url.rstrip("/")

    transactions = get(base, f"/api/v1/transactions?portfolioId={args.portfolio_id}&size=500")
    prices = get(base, "/api/v1/prices?symbols=IWDA,VWCE&from=2000-01-01&to=2100-01-01")

    history = defaultdict(dict)
    for price in prices:
        history[price["symbol"]][date.fromisoformat(price["priceDate"])] = float(price["closePrice"])
    trading_days = sorted({date.fromisoformat(p["priceDate"]) for p in prices})

    def close_on(symbol, day):
        """Forward fill: the last close at or before `day`, never after it."""
        known = [d for d in history[symbol] if d <= day]
        if not known:
            raise SystemExit(f"no price for {symbol} on or before {day}")
        return history[symbol][max(known)]

    for transaction in transactions:
        transaction["day"] = datetime.fromisoformat(
            transaction["executedAt"].replace("Z", "+00:00")).date()
    transactions.sort(key=lambda t: t["day"])

    cash, positions, series, cursor = 0.0, defaultdict(float), [], 0
    for day in trading_days:
        external_flow = 0.0
        while cursor < len(transactions) and transactions[cursor]["day"] <= day:
            transaction = transactions[cursor]
            cursor += 1
            cash += float(transaction["cashAmount"])
            if transaction["type"] == "BUY":
                positions[transaction["symbol"]] += float(transaction["quantity"])
            elif transaction["type"] == "SELL":
                positions[transaction["symbol"]] -= float(transaction["quantity"])
            # A dividend is income, not an external flow.
            if transaction["type"] in ("DEPOSIT", "WITHDRAWAL"):
                external_flow += float(transaction["cashAmount"])
        nav = cash + sum(q * close_on(s, day) for s, q in positions.items() if q)
        series.append((day, nav, external_flow))

    returns = [(series[i][1] - series[i - 1][1] - series[i][2]) / series[i - 1][1]
               for i in range(1, len(series)) if series[i - 1][1] != 0]

    total = math.prod(1 + r for r in returns) - 1
    annualised = (1 + total) ** (TRADING_DAYS_PER_YEAR / len(returns)) - 1
    mean = sum(returns) / len(returns)
    volatility = math.sqrt(sum((r - mean) ** 2 for r in returns) / (len(returns) - 1)) \
        * math.sqrt(TRADING_DAYS_PER_YEAR)

    index, peak, drawdown = 1.0, 1.0, 0.0
    for r in returns:
        index *= 1 + r
        peak = max(peak, index)
        drawdown = min(drawdown, index / peak - 1)

    print(f"window                {series[0][0]} .. {series[-1][0]}")
    print(f"observations          {len(returns)}")
    print(f"starting value        {series[0][1]:.4f}")
    print(f"ending value          {series[-1][1]:.4f}")
    print(f"total return          {total:.6f}")
    print(f"annualised return     {annualised:.6f}")
    mwr = money_weighted_return(series)
    print(f"money-weighted return {'undefined' if mwr is None else f'{mwr:.6f}'}")
    print(f"annualised volatility {volatility:.6f}")
    print(f"max drawdown          {drawdown:.6f}")
    print(f"sharpe ratio          {(annualised - args.risk_free_rate) / volatility:.6f}"
          f"   (risk-free rate {args.risk_free_rate})")


if __name__ == "__main__":
    main()
