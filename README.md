# ledger-lens-services

Portfolio analytics rebuilt as Spring Boot microservices, deployed to Azure and
described in Kubernetes manifests. The domain — transactions, holdings, return,
volatility, maximum drawdown, Sharpe ratio — is borrowed from
Ledger Lens (Python/FastAPI); no code is shared, and the
original project is untouched.

| | |
|---|---|
| Java | 21 |
| Spring Boot | 3.5.16 |
| Build | Maven multi-module (wrapper committed) |
| Database | PostgreSQL 16 |
| Tests | JUnit 5, Testcontainers (real Postgres), WireMock |
| API errors | RFC 9457 `application/problem+json` |

## Services

| Service | Port | Owns | Depends on |
|---|---|---|---|
| `transaction-service` | 8081 | transactions + holdings, and the only database in the system | Postgres |
| `analytics-service` | 8082 | nothing — stateless computation | `transaction-service` over HTTP |

```
              ┌───────────────────────┐        ┌──────────────────────┐
  client ───▶ │  analytics-service    │ ─HTTP▶ │ transaction-service  │ ──▶ Postgres
              │  (stateless, no DB)   │        │  (system of record)  │
              └───────────────────────┘        └──────────────────────┘
```

The arrow points one way and never back. `transaction-service` does not know
`analytics-service` exists, which is what lets analytics be redeployed,
rewritten or deleted without touching the system of record.

## Why the split is here and not somewhere else

**A service boundary is a data-ownership boundary, not a code-layering one.**
The test applied throughout: *which component is allowed to write these rows?*
Exactly one may, and everyone else asks it. Two services sharing a database
would be a distributed monolith — all the operational cost of microservices,
none of the independence.

**Why transactions and holdings live together.** A holding is derived from the
transactions of the same portfolio; they change together and must stay
consistent with each other. Splitting them would put a business invariant across
a network boundary and require distributed transactions to defend it. They form
one aggregate, so they form one service.

**Why analytics is separate.** It has different properties in every dimension
that matters:

| | transaction-service | analytics-service |
|---|---|---|
| State | owns a database | none |
| Workload | write-heavy, transactional | read-heavy, CPU-bound |
| Correctness | must never lose or corrupt a write | recomputable from scratch |
| Failure | outage = data unavailable | outage = degraded, serve stale or nothing |
| Reason to change | new transaction types, new fields | new metric, changed formula |

Those last two rows are the real argument. A Sharpe-ratio bug must not be able
to take down the ledger, and a redeploy of a new metric must not restart the
component holding the database connections.

**Honest caveat, and the interview answer.** At this data volume a single
Spring Boot application would be the correct engineering choice. The split is
justified by the *shape* of the difference above, not the load. Microservices
buy independent deployment and failure isolation at the price of network calls,
partial failure, and eventual consistency — worth paying when teams and release
cadences diverge, not when a monolith still fits.

## Why there is no `common` module

Tempting: put the transaction DTO in a shared module so both services import
it. Rejected. A shared DTO makes one service's serialisation format the other's
compile-time dependency, so they can no longer be released independently — the
single property the split was bought for. `analytics-service` will declare its
own view of a transaction containing only the fields it needs, and consumer
tests catch drift. The parent POM shares *build configuration* only, never code.

The modules live in one repository for convenience. Each keeps its own
Dockerfile and its own pipeline stages, so the boundary stays real.

## Running it locally

Prerequisites: JDK 21, Docker.

```bash
docker compose up -d          # Postgres on host port 5433
./mvnw verify                 # build + tests (Testcontainers needs Docker running)
./mvnw -pl transaction-service spring-boot:run   # :8081
./mvnw -pl analytics-service  spring-boot:run   # :8082
```

Load a demo portfolio — 180 trading days of prices for two funds, an opening
position, monthly contributions, a sale and a dividend. Deterministic, so the
analytics figures computed from it are reproducible:

```bash
python3 scripts/seed-demo-data.py
curl "localhost:8082/api/v1/portfolios/<the-id-it-printed>/performance"
```

```bash
curl localhost:8081/actuator/health/readiness
curl localhost:8082/actuator/health/liveness
```

`/actuator/health/liveness` and `/actuator/health/readiness` exist from day one
because Kubernetes probes will point at them; liveness answers "is this process
wedged, restart it", readiness answers "may this instance take traffic yet".

## transaction-service API

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/transactions` | Record a transaction. `201` + `Location`. |
| `GET` | `/api/v1/transactions/{id}` | One transaction, or `404`. |
| `GET` | `/api/v1/transactions?portfolioId=&from=&to=&page=&size=` | A portfolio's ledger, oldest first. `portfolioId` is required. |
| `GET` | `/api/v1/portfolios/{id}/holdings?asOf=` | Positions, derived from the ledger. No holdings table exists. |
| `PUT` | `/api/v1/prices` | Bulk load closing prices. Idempotent. |
| `GET` | `/api/v1/prices?symbols=&from=&to=` | Closing prices for several symbols over a date range. |


No `PUT`, no `DELETE`. The ledger is append-only: a mistaken entry is corrected
with a reversing transaction, the way double-entry bookkeeping has always done
it. That is also what lets every analytics figure be recomputed from scratch.

Types modelled: `BUY`, `SELL`, `DIVIDEND`, `DEPOSIT`, `WITHDRAWAL`. Enough to
make the analytics real; corporate actions, FX and short positions are not.

### Holdings are derived, never stored

There is no `holdings` table. A position is a fold over the transaction log —
buys added, sells subtracted — computed by a `GROUP BY` on request, and
answerable as at any past instant via `?asOf=`.

A maintained holdings table would mean writing two places on every transaction.
Any failure between the two writes, or any insert that bypasses the service,
leaves them permanently inconsistent and nothing ever notices. Deriving has one
source of truth and cannot disagree with itself.

The cost is a scan of one portfolio's transactions, which the
`(portfolio_id, executed_at)` index makes cheap at this size. When it stops
being cheap the answer is a materialised view or an incremental snapshot — a
decision to take with measurements, not in advance of them.

### Prices are reference data, and behave differently on purpose

| | `transactions` | `daily_prices` |
|---|---|---|
| Nature | events that happened | facts about the world |
| Writes | append-only, `POST`, not idempotent | upsert, `PUT`, idempotent |
| Corrections | a reversing entry | overwrite the row |
| Key | surrogate UUID | natural `(symbol, price_date)` |

`PUT` because a price load gets retried, replayed, and run twice by a nervous
operator; running it twice must leave the same state. `POST /transactions` is
deliberately the opposite — posting the same trade twice means it happened
twice.

The bulk write drops out of JPA into `INSERT ... ON CONFLICT DO UPDATE`.
`saveAll` on an entity with an assigned key issues a SELECT per row to choose
between insert and update, so a year of prices for ten symbols would be ~2,500
round trips before a single write. Set-based work belongs in SQL; keeping it
behind `DailyPriceBatchWriter` means nothing else in the service notices.

**Honest caveat:** market data is arguably its own bounded context and in a
larger system would be its own service fed by a vendor feed. Folding it into
transaction-service is a deliberate scope decision for a two-week project, not
a claim that it belongs here.

### Errors

Every failure is RFC 9457 `application/problem+json`, so a caller can branch on
`status` and `type` instead of parsing prose:

| Status | When |
|---|---|
| `400` | The payload's *shape* is wrong — missing field, negative quantity, unparseable UUID. Every field error is reported at once. |
| `422` | The payload is well-formed but describes an impossible transaction — a `BUY` with no symbol, a `DEPOSIT` carrying a quantity. |
| `404` | No such transaction. |

The 400/422 split is deliberate: it tells the caller whether to fix their
serialiser or their understanding of the domain. Bean Validation on the request
record enforces shape; `Transaction.of` enforces meaning.

### Money

`BigDecimal` throughout, never `double` — binary floating point cannot represent
0.10, and a ledger that cannot represent ten cents is not a ledger. Cash is
scaled to 4 decimal places and quantities to 8 (fractional shares), rounded
`HALF_EVEN`, because `HALF_UP` is biased upwards and that bias accumulates into
money nobody paid.

The signed `cashAmount` is always computed server-side and never accepted from
the client. A client that could set it could book a purchase that increases the
cash balance.

### Schema

Flyway owns the schema; Hibernate runs with `ddl-auto: validate` and refuses to
start if the entities and the migrated tables have drifted apart. The same
invariants the domain enforces are restated as `CHECK` constraints, because
application code is one way into that table and a migration script or an
engineer at a `psql` prompt is another.

## analytics-service

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/v1/portfolios/{id}/performance?from=&to=&riskFreeRate=` | The four figures. `from` defaults to the first transaction, `to` to today. |

### The maths

Four figures, all derived from one daily net-asset-value series that this
service rebuilds from data it does not own — the transaction ledger and the
price history.

#### Time-weighted, not money-weighted

The daily return removes external cash flows before measuring anything:

```
r(t) = ( NAV(t) - NAV(t-1) - CF(t) ) / NAV(t-1)
```

Without the `CF(t)` term a 1,500 EUR contribution into a 50,000 EUR portfolio
reads as a 3% gain, and the measurement rewards saving rather than investing.

What counts as an external flow is a business judgement, not a technicality:

| | External? | Why |
|---|---|---|
| Deposit / withdrawal | **yes** | changes value without being performance |
| Buy / sell | no | internal — cash becomes stock; only the fee is a real cost |
| Dividend | **no** | this *is* return; netting it out erases the performance it represents |

Time-weighted return was chosen over money-weighted (IRR/XIRR) because the same
daily return series feeds all four figures — chain-link it for total return,
take its standard deviation for volatility, accumulate it for drawdown. A
money-weighted return is a separate root-finding algorithm that feeds only
itself, and a Sharpe ratio built on it would be meaningless: Sharpe needs a
periodic return series, which is precisely what the time-weighted method
produces. XIRR remains a genuinely useful figure and is a candidate for later,
not a gap in what is here.

#### The rest of the decisions

- **Maximum drawdown is measured on the return index, never on net asset
  value.** A withdrawal lowers NAV without losing anyone a cent; on a NAV series
  a portfolio paying a monthly income would look like a disaster.
- **Annualisation is geometric**, `(1+r)^(252/n) - 1`, not `r × 252/n`. Returns
  compound, and the arithmetic version overstates anything volatile.
- **Volatility uses the sample deviation (n-1).** Dividing by n biases the
  estimate downwards, which flatters every Sharpe ratio built on it.
- **The risk-free rate is an input, defaulted to zero and echoed back in the
  response.** A Sharpe ratio without its risk-free rate is not comparable to
  anything, and an assumption hidden inside a formula cannot be audited.
- **Sharpe is `null` when volatility is zero**, because the ratio is undefined
  rather than infinite.
- **A missing price fails loudly.** Valuing a held position at zero would show a
  catastrophic loss that never happened; a wrong number that looks plausible is
  worse than an error, because nobody investigates it.
- **Prices are filled forward, never interpolated and never taken from the
  future.** Valuing today with tomorrow's close is lookahead bias.
- **Money is `BigDecimal`; the statistics are `double`.** A cash balance must be
  exact. Standard deviation and geometric annualisation need `sqrt`, `log` and
  `pow`, which BigDecimal lacks or only approximates — and sixteen significant
  digits of volatility is already more precision than the prices justify. The
  conversion happens at the boundary, so the API never publishes a float.

### Talking to transaction-service

One class, `TransactionServiceClient`, knows the other service exists.
Everything above it works in domain types, so replacing REST with a queue or a
local database changes that file and nothing else.

`RestClient`, not `RestTemplate` (superseded) and not `WebClient` (a reactive
stack for one blocking fetch followed by arithmetic).

#### What a synchronous call between services actually needs

**A timeout, and a decision about what happens when it fires.** A call with no
timeout inherits the operating system's, which is minutes. Under load that
parks every thread in this service on a dead upstream, and one service's outage
becomes two. Connect 2s, read 5s, both configurable.

**A retry small enough to be safe.** Both calls are `GET`, so repeating them is
harmless, and one immediate retry absorbs the common case of a connection
dropped during a rolling deploy. It stops at two attempts: retries are load
amplification, and an upstream in trouble receiving every request twice is how
a slowdown becomes an outage.

**No circuit breaker, and no Resilience4j.** Deliberate. A circuit breaker earns
its keep when there is enough traffic for it to observe and enough instances for
the failure to be worth isolating; here it would be configuration nobody can
justify. The short timeout plus the bounded retry covers the same failure mode
at a fraction of the reasoning cost.

**A fallback that is honest about itself.** The last successful answer is kept
in a small bounded LRU cache. When transaction-service cannot be reached, that
answer is served with `"stale": true`, its `computedAt` timestamp, and
`Cache-Control: no-store` so nothing downstream caches it again. Past the
tolerance (15 minutes, configurable) the cache stops answering and the request
gets a `503` with `Retry-After`.

Whether stale beats nothing is a product decision, not a technical one: for a
performance dashboard a clearly-labelled figure from ten minutes ago is more
useful than an error page; for anything that moves money it would not be. What
makes it defensible either way is that the staleness is always reported.

The cache does not make this service stateful in the sense that matters. It owns
nothing: every entry is reproducible from transaction-service, losing it costs
one recomputation, and no instance needs to agree with any other.

#### Failure translation

| Upstream did | analytics-service answers |
|---|---|
| timed out, refused, or 5xx — cached result available | `200` with `"stale": true` |
| timed out, refused, or 5xx — nothing usable cached | `503` + `Retry-After`, upstream's message not echoed |
| returned an empty ledger | `404` — a definite answer that there is nothing to measure |
| returned too few valuation points | `422` |

The first two rows are the distinction that matters: `503` says nothing is wrong
with *this* service, so retrying may work — unlike a `500`, which says it will
not. And `404` is never used for an outage; telling a caller their portfolio is
empty when the system of record is merely down is worse than any error.

Watch it degrade, with transaction-service stopped:

```
upstream up     200  Cache-Control: max-age=60  "stale": false
upstream down   200  Cache-Control: no-store    "stale": true   (same figures, computedAt from before)
unknown id      503  Retry-After: 30            problem+json, no cached result to fall back on
/actuator/health of analytics-service:  UP      — it is not the broken one
```

### Verified twice

`scripts/crosscheck-metrics.py` is a second, independent implementation of the
same formulas in Python, run against the same live data. The Java unit tests
prove the calculator does what its author intended; they cannot catch a
misunderstanding baked into both the code and its tests. Two implementations
agreeing is weak-but-real evidence. Disagreement is a definite bug in one of
them.

On the seeded demo portfolio the two implementations agree on every figure to
six decimal places, over 179 daily observations:

| | |
|---|---|
| Observations | 179 daily returns |
| Starting → ending value | 49,997 → 70,712 |
| **Total return (time-weighted)** | **+15.86%** |
| Annualised return | +23.03% |
| Annualised volatility | 13.81% |
| Maximum drawdown | −7.02% |
| Sharpe ratio (rf = 0) | 1.67 |

Both columns produced these; the Java figures come from
`GET /api/v1/portfolios/{id}/performance` with both services running, the Python
ones from the script reading the same two endpoints directly.

Note the first two rows together: the account grew 41% while the investments
returned 15.9%. The difference is contributions — which is exactly the
distinction the time-weighted method exists to draw.

## Testing

Four tiers, fastest first, each failing for one reason:

| Tier | What it proves | Cost |
|---|---|---|
| `TransactionTest` | the domain rules and the arithmetic | no Spring, milliseconds |
| `TransactionControllerTest` (`@WebMvcTest`) | routing, validation, status codes, error bodies | web layer only, service mocked |
| `TransactionRepositoryTest` (`@DataJpaTest`) | the SQL, the mapping, the DB constraints | real Postgres |
| `TransactionApiIntegrationTest` (`@SpringBootTest`) | that the three are wired together | full stack |
| `PerformanceCalculatorTest`, `ValuationSeriesTest` | the analytics maths and the cash-flow rules | no Spring, 34ms |
| `PerformanceApiWireMockTest` | the inter-service call, including its failure modes | WireMock stands in for transaction-service |

Every database test runs against a real PostgreSQL container, never H2. An
in-memory database that is not the production database only proves the code
compiles against JPA — it will happily accept SQL Postgres rejects, and reject
SQL Postgres accepts. The container is declared once as a bean in
`PostgresTestcontainerConfiguration` and reused across classes via Spring's test
context cache, instead of one container per test class.

One test writes raw SQL around the entity to prove the `CHECK` constraints hold
even when the domain model is bypassed.

The WireMock tests are not there for the happy path. They exist because a stub
can be made to hang for four seconds, return a 500, or drop the connection
mid-response on demand — the cases that decide whether this service degrades or
falls over, and which cannot be provoked reliably against a real upstream. One
of them asserts that a timeout is measured in seconds rather than in whatever
the operating system would have allowed.

Its fixtures are hand-written JSON, not the other module's DTO classes. Sharing
the real types would make these tests pass whenever both services were wrong in
the same way; a hand-written fixture is a statement about the contract as
published.

## Configuration

No environment-specific value is hard-coded. `application.yml` holds local
development defaults only; Spring Boot maps environment variables onto the same
keys (`SPRING_DATASOURCE_URL`, `LEDGERLENS_TRANSACTIONSERVICE_BASEURL`), which
is how Container Apps and Kubernetes inject them later.

## Deliberately out of scope

Kafka, service mesh, Terraform, Helm, Prometheus/Grafana, AKS. Each is a
separate rabbit hole; leaving them out is what makes the timeline real.
