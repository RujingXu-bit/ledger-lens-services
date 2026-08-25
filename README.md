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
| Tests | JUnit 5, Testcontainers (real Postgres), WireMock, consumer-driven contracts |
| API errors | RFC 9457 `application/problem+json` |
| API docs | OpenAPI 3 via springdoc, spec committed to `docs/openapi/` |

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

Swagger UI: <http://localhost:8081/swagger-ui.html> and
<http://localhost:8082/swagger-ui.html>.

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

The four risk figures are all built on the time-weighted series, because that
is what feeds them — chain-link it for total return, take its standard deviation
for volatility, accumulate it for drawdown. A Sharpe ratio built on a
money-weighted figure would be meaningless: Sharpe needs a periodic return
series, and money-weighting produces a single scalar.

#### Both figures are reported, and neither is the headline

`moneyWeightedReturn` is the annualised internal rate of return of the
investor's own cash flows — XIRR, solved on ACT/365.

| | measures | strips out | use it to |
|---|---|---|---|
| Time-weighted | the **portfolio** | when money went in | compare against a benchmark or a manager |
| Money-weighted | the **investor** | nothing | say what this person actually earned |

They can disagree sharply, and the disagreement is information. Someone
contributing monthly into a market that fell and then recovered will show a
money-weighted return well above the time-weighted one, because most of their
money went in cheap. Reporting one figure without saying which is how
performance conversations go wrong, so `returnMethod` names the convention and
both numbers travel together.

**Solved by bisection, not Newton-Raphson.** Newton converges faster when it
converges, but an NPV curve with sign changes can send it to a nonsensical rate
or leave it oscillating — and a wrong performance figure that looks plausible is
the worst possible output. Bisection cannot diverge: given a sign change it
halves the interval every step. When the flows define no rate at all (money only
ever paid into a portfolio now worth nothing) the answer is `null`, not an
invented number.

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

**Reading all of the data, or none of it.** The ledger is read page by page
until it is exhausted. Asking for one page and using whatever came back is the
trap: transaction-service caps a page at 500, so a busier portfolio would have
been measured on a fragment of its own ledger with nothing in the response
saying so. Paging is safe because the ledger is ordered by
`(executedAt, id)` — the tiebreaker means a page boundary cannot skip or repeat
a row. Past a backstop of 50,000 transactions the request is refused with a
`422` rather than answered from part of the data.

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
| answered `4xx` — a definite "your request is wrong" | `502`, never retried, never served from cache |
| returned an empty ledger | `404` — a definite answer that there is nothing to measure |
| returned too few valuation points, or too large a ledger | `422` |

`502` and `503` are split on purpose, and the split is for whoever is on call:
`503` says the upstream is down, wait. `502` says the upstream is healthy and
this service asked it the wrong question — go and look at the integration,
because retrying will not help. A `4xx` is deterministic, so it is not retried
and never falls back on a cached figure: that would hide a bug rather than
survive an outage.

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
| Annualised return (time-weighted) | +23.03% |
| Annualised volatility | 13.81% |
| Maximum drawdown | −7.02% |
| Money-weighted return (XIRR) | +23.62% |
| Sharpe ratio (rf = 0) | 1.67 |

Both columns produced these; the Java figures come from
`GET /api/v1/portfolios/{id}/performance` with both services running, the Python
ones from the script reading the same two endpoints directly.

Note the first two rows together: the account grew 41% while the investments
returned 15.9%. The difference is contributions — which is exactly the
distinction the time-weighted method exists to draw.

## The API contract

Both services describe themselves in OpenAPI 3, generated by springdoc from the
code:

| | |
|---|---|
| Spec | `/v3/api-docs` (JSON), `/v3/api-docs.yaml` |
| Swagger UI | `/swagger-ui.html` |
| Committed copy | [`docs/openapi/`](docs/openapi/) |

Generated rather than hand-written, because a document maintained beside the
code is wrong the first time somebody changes a controller and forgets it. What
*is* written by hand is the part no annotation can infer — what the service is
for, and the conventions a consumer has to know before the endpoint list means
anything: that the ledger is append-only, that money is decimal and must be
parsed as such, that `cashAmount` is server-derived, that a 400 and a 422 mean
different things, that `stale` may be true.

### The spec is committed, and asserted

`scripts/export-openapi.sh` writes both specs into `docs/openapi/`. They are in
the repository so that an API change arrives as a reviewable diff rather than as
a surprise for whoever is calling.

Generation guarantees the document matches the code. It does not guarantee the
code still matches what consumers were promised — so the promises are tests:

```java
// transaction-service
theLedgerExposesNoWayToEditOrDeleteAnEntry()   // post, get — and nothing else
pricesAreWrittenWithPutRatherThanPost()        // reference data, so idempotent
documentsTheFourHundredVersusFourTwentyTwoDistinction()
keepsOperationalEndpointsOutOfThePublishedApi()

// analytics-service
documentsThatItsUpstreamCanBeUnavailable()     // 503 must stay in the contract
thePayloadTellsAConsumerWhetherTheFiguresAreLiveAndHowTheyWereMeasured()
```

The first one is the append-only design turned into something the build can
check: adding an update or a delete to the ledger fails it. The last one is
about degradation — a generated client with no `stale` field cannot check
whether the figures it received were cached, so the field's presence is part of
the contract rather than an implementation detail.

Only structure is asserted, never wording. A test that pins prose gets deleted
the first time it costs somebody a rephrase.

### The consumer-driven contract

`docs/contracts/analytics-service-expects.json` is owned by analytics-service
and lists exactly what it needs from transaction-service — five fields from the
ledger, three from the price feed, the query parameters it sends, and the five
enum values it maps. Nothing more, so the producer stays free to change
everything else without asking.

It is checked from both ends:

| Where | What it asserts | Why that end |
|---|---|---|
| transaction-service's build | the published spec still satisfies every expectation | **breaking a consumer fails the producer's build**, before the change ships |
| analytics-service's build | the client really does read exactly those fields | the declaration cannot rot into a wish list, in either direction |

The first row is the one that matters. analytics-service already has tests
proving it copes with what it receives — but those run in the consumer's build,
and by the time they fail the producer has deployed. Running the check in the
producer moves the failure to the only moment it can still prevent an outage.

The second test checks both directions on purpose. Over-declaring holds the
producer to promises nobody depends on, so harmless changes get blocked;
under-declaring lets the producer's build go green while removing a field this
client parses, which is the exact failure the exercise exists to prevent.

**Confirmed by breaking it.** A guard nobody has seen fail is a guard nobody
should trust, so each check was verified against a real mutation:

```
rename cashAmount -> cashImpact   ->  FAILURE: "/api/v1/transactions response must still carry 'cashAmount'"
require an absent enum value      ->  FAILURE: "/api/v1/transactions field 'type' must still accept the value 'SPLIT'"
```

**Honest limitation:** a real setup — Pact, or the spec published as a versioned
artifact — lets a producer verify against consumers it does not share a
repository with. This gets the same guarantee inside one repository without a
new dependency, and stops working the moment the services are split apart.

Writing this contract also found a genuine gap: neither service declared what it
`produces`, so every response was documented as `*/*`. The fix was to say so in
the controllers, not to loosen the test.

### One thing to decide before production

Swagger UI is served unauthenticated. For this project that is the point — a
reviewer can open the deployed URL and try the API. For a real service it would
be gated behind an environment profile or an authenticated route, because an
interactive client for every endpoint is a gift to anyone probing the service.

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
| `ConsumerContractTest` | that this service has not broken analytics-service | runs in the **producer's** build |
| `OpenApiContractTest` | the published API still makes the promises it made | spec assertions, structure only |

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

## Known limitations

Written down rather than left to be discovered. Each is a decision, not an
oversight — but none of them is defensible while it is invisible.

| | |
|---|---|
| **No authentication.** Both services are open. Anyone reachable can record transactions and restate prices. | Acceptable while it runs on a laptop; a decision has to be taken before it is deployed. |
| **Selling more than you hold is allowed**, and produces a negative holding. | Sufficient-quantity is an invariant across the whole ledger, not within one transaction, so enforcing it means reading aggregate state on every write. Not implemented; short positions are therefore representable. |
| **A cash-only portfolio cannot be measured.** With no securities there are no prices, and the valuation calendar is derived from price data, so there are no valuation points. | Fails loudly with a `422`, but the message says "not enough data" rather than naming the real cause. |
| **`GET /api/v1/prices` has no bound** on symbol count or date range, while transactions cap a page at 500. | The asymmetry is unintentional. |
| **Swagger UI is unauthenticated.** | Deliberate here — a reviewer can open the deployed URL and try the API. Gate it behind a profile for anything real. |
| **`PUT /api/v1/prices` returns an untyped map**, the one response in the API without a named schema. | A consumer cannot generate a typed client for it. |
| **Nothing enforces that `docs/openapi/` is current.** `scripts/export-openapi.sh` is run by hand. | The committed spec exists to make API changes reviewable; a stale one quietly does the opposite. A CI step is the natural home. |

## Deliberately out of scope

Kafka, service mesh, Terraform, Helm, Prometheus/Grafana, AKS. Each is a
separate rabbit hole; leaving them out is what makes the timeline real.
