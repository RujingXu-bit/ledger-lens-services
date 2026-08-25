#!/usr/bin/env python3
"""Load a demo portfolio into a running transaction-service.

Deterministic on purpose: a fixed random seed means the analytics figures
computed from this data are reproducible, so a regression in the maths shows up
as a changed number rather than as noise.

    python3 scripts/seed-demo-data.py [--base-url http://localhost:8081] [--end-date 2026-08-25]

The series ends on --end-date, defaulting to today, because transaction-service
rejects trades dated in the future. Pin --end-date to make a load reproducible
across days as well as across runs.

Prints the portfolio id, which is what analytics-service will be pointed at.
"""

import argparse
import json
import random
import urllib.error
import urllib.request
import uuid
from datetime import date, datetime, timedelta, timezone

SYMBOLS = {"IWDA": 98.75, "VWCE": 111.50}
TRADING_DAYS = 180
SEED = 20260102


def post(base_url: str, path: str, payload, method: str = "POST"):
    request = urllib.request.Request(
        base_url + path,
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"},
        method=method,
    )
    try:
        with urllib.request.urlopen(request) as response:
            return json.loads(response.read() or b"null")
    except urllib.error.HTTPError as error:
        raise SystemExit(f"{method} {path} -> {error.code}\n{error.read().decode()}") from error


def trading_days_ending(end: date, count: int) -> list[date]:
    """The last `count` weekdays up to and including `end`, oldest first.

    Counted backwards from the end rather than forwards from a fixed start:
    transaction-service rejects a trade dated in the future, and it is right to.
    Holidays are ignored - this is demo data, not a market calendar.
    """
    days, day = [], end
    while len(days) < count:
        if day.weekday() < 5:
            days.append(day)
        day -= timedelta(days=1)
    return list(reversed(days))


def price_series(rng: random.Random, days: list[date]):
    """A lognormal-ish random walk: small daily drift, ~1.1% daily volatility.

    Enough structure that volatility, maximum drawdown and Sharpe come out with
    plausible magnitudes instead of degenerate ones.
    """
    levels = dict(SYMBOLS)
    for day in days:
        for symbol in SYMBOLS:
            shock = rng.gauss(0.0003, 0.011)
            levels[symbol] = round(levels[symbol] * (1 + shock), 4)
            yield {
                "symbol": symbol,
                "priceDate": day.isoformat(),
                "closePrice": levels[symbol],
                "currency": "EUR",
            }


def at_open(day: date) -> str:
    return datetime(day.year, day.month, day.day, 9, 0, tzinfo=timezone.utc).isoformat().replace("+00:00", "Z")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8081")
    parser.add_argument("--end-date", type=date.fromisoformat, default=date.today())
    args = parser.parse_args()
    base_url = args.base_url.rstrip("/")

    rng = random.Random(SEED)
    portfolio_id = str(uuid.UUID(int=rng.getrandbits(128), version=4))

    days = trading_days_ending(args.end_date, TRADING_DAYS)
    prices = list(price_series(rng, days))
    written = post(base_url, "/api/v1/prices", prices, method="PUT")
    print(f"prices written: {written['written']}")
    price_on = {(p["symbol"], p["priceDate"]): p["closePrice"] for p in prices}

    transactions = [{
        "portfolioId": portfolio_id,
        "type": "DEPOSIT",
        "amount": 50000,
        "currency": "EUR",
        "executedAt": at_open(days[0]),
    }]

    # An opening position, then a monthly contribution split across both funds -
    # the shape of an ordinary index-fund portfolio, which is what makes the
    # analytics worth looking at.
    for index, day in enumerate(days):
        if index % 21 != 0:
            continue
        if index > 0:
            transactions.append({
                "portfolioId": portfolio_id, "type": "DEPOSIT", "amount": 1500,
                "currency": "EUR", "executedAt": at_open(day),
            })
        for symbol, share in (("IWDA", 0.6), ("VWCE", 0.4)):
            cash = (50000 if index == 0 else 1500) * share
            price = price_on[(symbol, day.isoformat())]
            transactions.append({
                "portfolioId": portfolio_id, "type": "BUY", "symbol": symbol,
                "quantity": round(cash / price, 6), "pricePerUnit": price,
                "fee": 1.5, "currency": "EUR", "executedAt": at_open(day),
            })

    # One sale, so the holdings query has something to net off.
    sale_day = days[120]
    transactions.append({
        "portfolioId": portfolio_id, "type": "SELL", "symbol": "VWCE",
        "quantity": 10, "pricePerUnit": price_on[("VWCE", sale_day.isoformat())],
        "fee": 1.5, "currency": "EUR", "executedAt": at_open(sale_day),
    })

    # And one dividend, so income is represented in the cash flows.
    dividend_day = days[90]
    transactions.append({
        "portfolioId": portfolio_id, "type": "DIVIDEND", "symbol": "IWDA",
        "amount": 240.55, "currency": "EUR", "executedAt": at_open(dividend_day),
    })

    transactions.sort(key=lambda t: t["executedAt"])
    for transaction in transactions:
        post(base_url, "/api/v1/transactions", transaction)

    print(f"transactions written: {len(transactions)}")
    print(f"portfolio id: {portfolio_id}")


if __name__ == "__main__":
    main()
