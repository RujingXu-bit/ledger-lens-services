# ledger-lens-services

[![Build Status](https://dev.azure.com/rujingxu-ledgerlens/ledger-lens-services/_apis/build/status/ledger-lens-services?branchName=main)](https://dev.azure.com/rujingxu-ledgerlens/ledger-lens-services/_build/latest?definitionId=1&branchName=main)
[![Live](https://img.shields.io/badge/live-analytics--service-0078D4)](https://analytics-service.icywater-fe129bae.francecentral.azurecontainerapps.io/swagger-ui.html)
[![License](https://img.shields.io/badge/licence-MIT-green)](LICENSE)

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
| Containers | multi-stage Dockerfiles, layered jars, non-root, Docker Compose |
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

## Running it

Prerequisites: Docker. (JDK 21 only if you want to run the services outside
containers.)

Everything, as containers:

```bash
docker compose up --build
```

Or just the database, with the services from an IDE or Maven:

```bash
docker compose up -d postgres   # Postgres on host port 5433
./mvnw verify                   # build + tests (Testcontainers needs Docker running)
./mvnw -pl transaction-service spring-boot:run   # :8081
./mvnw -pl analytics-service  spring-boot:run    # :8082
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

## Containers

One multi-stage Dockerfile per service, built from the repository root because
each needs the parent POM and the Maven wrapper that live above it.

```
build stage    eclipse-temurin:21-jdk-alpine  — Maven, the JDK, the source
runtime stage  eclipse-temurin:21-jre-alpine  — a JRE and four layers of application
```

| | transaction-service | analytics-service |
|---|---|---|
| Image | 401 MB | 341 MB |
| Incremental rebuild after a code change | ~6 s | ~6 s |

### What the multi-stage split buys

**The build toolchain never ships.** Maven, the compiler and the source tree
stay in the discarded stage. `which javac` in the running image finds nothing —
a build toolchain on a production container is attack surface that can never be
used for anything good.

**It runs as `ledgerlens`, not root.** `id` reports `uid=100`. If something ever
gets execution in there it lands unprivileged, with no package manager and
nothing writable outside `/tmp`.

### Layered jars, and why the layer order is not arbitrary

The fat jar is split by how often each part changes, using Spring Boot's own
`jarmode=tools` extractor, and copied in four separate `COPY` steps:

```
dependencies/           60.5 MB   third-party jars — change when a POM changes
spring-boot-loader/        696 kB   changes with the Boot version
snapshot-dependencies/     4.1 kB
application/               250 kB   this project's classes — change every commit
```

Copying the whole jar in one step would make every commit push all 60 MB. Split,
a code change ships **250 kB**. The same reasoning drives the POM-first copy in
the build stage: dependency download is its own cache layer, invalidated only by
a POM edit rather than by every source edit.

**Tests do not run in the image build.** They need a Docker daemon for
Testcontainers, so running them there would mean either Docker-in-Docker or
quietly weakening them into something that does not test Postgres. They belong
in the pipeline (day 11).

**`-XX:MaxRAMPercentage=75`, not `-Xmx`.** The JVM reads the container's memory
limit, so the heap follows whatever the orchestrator grants instead of being
duplicated in two places that drift apart. With `-XX:+ExitOnOutOfMemoryError` a
container that exhausts its heap dies and gets replaced rather than limping.

### Compose runs the real images

```bash
docker compose up --build
```

Three things in that file are worth reading rather than copying:

**`depends_on: condition: service_healthy`, not just `depends_on`.** Postgres
accepts a TCP connection seconds before it will accept a query, and Flyway runs
during startup — without the health condition the first boot is a race the
service usually loses.

**`jdbc:postgresql://postgres:5432/...`, not `localhost:5433`.** Inside the
network the service name is the hostname and the port is the container's own.
The host mapping is for humans.

**`LEDGERLENS_TRANSACTIONSERVICE_BASEURL: http://transaction-service:8081`.**
This is the same value that becomes an internal FQDN on Container Apps and a
Kubernetes Service name on day 12 — one environment variable, four
environments, no code change and no Spring profile. Spring Boot maps
`SPRING_DATASOURCE_URL` and friends onto the same keys the same way.

Verified end to end with nothing running on the host JVM: the containerised
analytics-service resolves `transaction-service` to `172.20.0.3`, produces the
same seven figures as the host run and the Python cross-check, and still
degrades to `"stale": true` when `docker compose stop transaction-service`
takes the upstream away.

**Honest note on size:** 341–401 MB is mostly the Temurin JRE base. A `jlink`ed
runtime or a distroless base would cut it substantially; both add moving parts
that are not what this fortnight is meant to demonstrate.

## Azure Container Registry

```
acrledgerlens79829b.azurecr.io/transaction-service:0.1.0-<git-sha>
acrledgerlens79829b.azurecr.io/analytics-service:0.1.0-<git-sha>
```

Basic tier, France Central, 147 MB of the 10 GB included. **$0.167/day** — the
only fixed cost in this project, and it is fixed: the meter runs on the registry
existing, not on how much is pushed through it.

### Images are tagged with the commit that built them, never `latest`

`latest` is a moving target, so "which build is in production" stops having an
answer and a rollback has nothing to roll back to. The tag is the short commit
SHA, `/actuator/info` reports the same version, and day 10 deploys a specific
one of those.

### Cross-building for a different architecture than the laptop

This is an Apple Silicon machine; Azure runs `linux/amd64`. Building the image
natively and pushing it would produce something Azure cannot start, and the
error arrives at deploy time rather than build time.

The fix is one directive:

```dockerfile
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk-alpine AS build
FROM eclipse-temurin:21-jre-alpine AS runtime          # follows --platform linux/amd64
```

The build stage runs on whatever is doing the building; only the runtime stage
is built for the target. That is safe **because the output is a jar** — Java
bytecode is architecture-independent, so nothing has to be recompiled. Without
it, `docker buildx --platform linux/amd64` would emulate an entire Maven build
under QEMU; with it, the two pushes took 24 s and 7 s.

It would not be safe for a dependency shipping native code. There is none here,
and that is worth checking rather than assuming.

### Authentication

`az acr login` uses the signed-in Entra ID identity. The registry is created
with `--admin-enabled false`, so there is no shared admin password to leak, put
in a pipeline variable, or forget to rotate. Day 10 gives Container Apps a
managed identity with `AcrPull` rather than credentials.

### Cost control on this subscription

The subscription is **Azure for Students**: $100, valid to 2027-08-25, no card.
Chosen over the standard free account deliberately — $200 over 30 days would
have taken the demo offline a month in, and a live URL that outlasts the job
search is most of the point.

`spendingLimit: On`, which is a hard stop rather than an alert: at $100 the
resources are disabled, not billed past the credit. That matters because
**Cost Management does not support this subscription type** — `az consumption`
returns 422, and budget alerts cannot be created. The only API that answers is
`Microsoft.Education/grants`, and it reports the cap and expiry, not the
balance. The balance lives in the portal's Education blade.

Which is survivable here only because the spend is deterministic: one registry
at a fixed daily rate, and Container Apps within its monthly free grant.

```bash
az group delete --name rg-ledgerlens --yes   # stops every meter in one command
```

### One thing this subscription does that the docs do not warn about

Azure for Students carries a policy limiting deployments to five regions:

```
belgiumcentral  francecentral  denmarkeast  spaincentral  germanywestcentral
```

`northeurope` — the obvious choice from Ireland — is refused with
`RequestDisallowedByAzure`. France Central was picked because it is the closest
of the five that has both ACR and Container Apps; Denmark East has ACR but not
Container Apps, which would have surfaced on day 10 instead of day 9.

## Running on Azure Container Apps

```
analytics-service     https://analytics-service.icywater-fe129bae.francecentral.azurecontainerapps.io
transaction-service   internal only — no public address at all
PostgreSQL            pg-ledgerlens79829b.postgres.database.azure.com
```

Everything in `rg-ledgerlens`, France Central, deployed from the ACR images
tagged with the commit that built them.

### The network shape is the point

`transaction-service` has **internal ingress**: it is reachable from inside the
Container Apps environment and from nowhere else on the internet. That is not a
detail — it means the ledger cannot be written to by anyone who has not already
got into the environment, which closes half of the "no authentication" gap in
the Known limitations without adding an auth layer this project has no time to
do properly.

`analytics-service` is the only public surface, and it can only read.

### What changed between environments: three environment variables

No code change, no Spring profile, no rebuild — the image running in Azure is
byte-for-byte the one that ran in Docker Compose.

| | Compose | Azure |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `//postgres:5432/ledgerlens` | `//pg-ledgerlens79829b.postgres.database.azure.com:5432/ledgerlens?sslmode=require` |
| `SPRING_DATASOURCE_PASSWORD` | plain text in the compose file | `secretref:db-password` — a Container Apps secret |
| `LEDGERLENS_TRANSACTIONSERVICE_BASEURL` | `http://transaction-service:8081` | `https://transaction-service.internal.<env>.azurecontainerapps.io` |

### Scale to zero, and the timeout that had to move because of it

Both apps run with `--min-replicas 0`.

```
Container Apps free grant   180,000 vCPU-seconds/month
one replica kept warm       0.5 vCPU x 2,592,000 s = 1,296,000  → 7.2x the grant, ~$27/month
scaled to zero, idle        ~0                                   → $0
```

A demo that is idle 99.9% of the time has no business holding a warm replica.
But scale-to-zero means the first request pays for a JVM start, and
analytics-service's 5-second read timeout — correct against a warm service — is
not a promise a cold one can keep.

The fix needed no code, because day 5 made the timeout configuration rather than
a constant: `LEDGERLENS_TRANSACTIONSERVICE_READTIMEOUT=30s` in Azure only. The
timeout belongs to the environment because the callee's promise belongs to the
environment.

### Probes: the bug that only appears in the cloud

The first deployment answered its first request in over two minutes. The system
log said why:

```
ProbeFailed x6 ... KEDAScalersStopped ... ContainerTerminated
```

Container Apps' default probe was killing the JVM before it finished starting,
in a loop. Nothing local reproduces this — Compose's healthcheck has a
`start-period`, and a laptop starts the app faster than the platform's patience.

The Actuator liveness and readiness endpoints have existed since day 1 for
Kubernetes on day 12; they turned out to be needed a fortnight early. Both apps
now declare:

| Probe | Endpoint | Tolerance |
|---|---|---|
| Startup | `/actuator/health/liveness` | 30 failures x 5s = 150s to boot |
| Readiness | `/actuator/health/readiness` | stops traffic before the app is ready to serve it |
| Liveness | `/actuator/health/liveness` | restarts a wedged process, not a slow one |

The startup probe is the one that matters: it tells the platform "do not judge
me by the liveness probe yet". A liveness probe with no startup probe in front
of it is how a slow-starting application gets restarted forever.

Measured, on a genuine cold start after both apps had scaled to zero:

| | first request | warm |
|---|---|---|
| Without probes | did not complete in 120 s (`ProbeFailed` x6 → `ContainerTerminated`) | — |
| With probes | **65.6 s, HTTP 200** | 0.5 s |

Two JVMs and two image pulls happen in that 65 seconds — analytics wakes, and
waking analytics is what wakes transaction-service.

### The cold start proved out the day 5 design

The analytics log from that request:

```
attempt 1/2 to fetch transactions page 0 failed: ... Request cancelled
```

The first attempt hit the 30-second read timeout — and that attempt is precisely
what woke the cold upstream. The bounded retry then succeeded, and the response
came back with `"stale": false`, meaning the fallback cache was never needed.

Three decisions from day 5 paid off at once, none of them written with this
situation in mind: a retry small enough to be safe but present, a timeout that
is configuration rather than a constant, and a cache that degrades only when
nothing else works.

### Loading data into a service with no public address

A real consequence of network isolation: the seed script on a laptop cannot
reach an internal service, and the Postgres firewall does not admit a home IP
either. The options were exec-and-wget for 389 requests, writing SQL straight
into the database and reimplementing the domain rules that compute
`cashAmount`, or briefly enabling external ingress.

The third, time-boxed to about two minutes and reverted immediately, was
chosen. Written down because it is a compromise: a production answer is a
migration job running inside the environment, which is where this would go if
it needed doing twice.

### Verified end to end

The public endpoint returns the same seven figures as the local run and the
independent Python implementation:

```
totalReturn 0.158612 · annualisedReturn 0.230306 · moneyWeightedReturn 0.236190
volatility 0.138144 · maxDrawdown -0.070159 · sharpe 1.667139 · 179 observations
```

### Cost

| | |
|---|---|
| PostgreSQL Flexible Server B1MS, 32 GB | **$0** — Azure for Students includes 750 h/month of B1MS for 12 months |
| Container Apps, scaled to zero | **$0** within the monthly free grant |
| Log Analytics | **$0** within 5 GB/month ingestion |
| ACR Basic | **$0.167/day** — the only meter running |

The database is inside the free allowance only while it stays exactly as
provisioned: `Standard_B1ms`, 32 GB with autogrow **off**, no high
availability, no geo-redundant backup. Any one of those changes starts billing,
which is why they are pinned explicitly rather than left to defaults.

```bash
az group delete --name rg-ledgerlens --yes   # stops everything
```

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

## Kubernetes, locally on kind

```bash
kind create cluster --config k8s/kind-cluster.yaml
./mvnw -DskipTests package
docker build -t ledgerlens/transaction-service:dev -f transaction-service/Dockerfile .
docker build -t ledgerlens/analytics-service:dev   -f analytics-service/Dockerfile .
kind load docker-image ledgerlens/transaction-service:dev ledgerlens/analytics-service:dev --name ledgerlens
kubectl apply -f k8s/
```

Three nodes, so a Deployment's replicas land on different machines rather than
all sharing one — a single-node cluster hides scheduling entirely. Images are
side-loaded with `kind load`; no registry is involved, which is also why every
container specifies `imagePullPolicy: IfNotPresent`.

The kind nodes are arm64 on this laptop and Azure runs amd64. Same Dockerfile
for both: `--platform=$BUILDPLATFORM` on the build stage means the jar is built
natively either way and only the runtime base follows the target.

### The fourth environment, and still one image

| | Compose | Container Apps | Kubernetes |
|---|---|---|---|
| Database host | `postgres:5432` | managed FQDN + `sslmode=require` | `postgres:5432` (Service DNS) |
| Password | plain text in the file | Container Apps secret | `Secret` → `secretKeyRef` |
| Upstream URL | `http://transaction-service:8081` | internal FQDN | `http://transaction-service:8081` |
| Not public | — | internal ingress | `ClusterIP`, no `nodePort` |

`ConfigMap` holds what is not secret and `Secret` holds what is, injected with
`envFrom` and `secretKeyRef`. No profile, no rebuild, no code aware of any of it.

`analytics-service` is a `NodePort` mapped by kind to `localhost:8090`. On a real
cluster it would be an Ingress; NodePort keeps the demonstration to one moving
part instead of also installing an ingress controller.

### Three probes, three different questions

| Probe | Question | Failing it means |
|---|---|---|
| **Startup** | has it finished booting? | nothing yet — the other two are not consulted |
| **Readiness** | should this pod get traffic? | removed from the Service, **not** restarted |
| **Liveness** | is this process wedged? | restarted |

The startup probe is the one whose absence let Container Apps kill the JVM
mid-boot in a loop on day 10. 30 × 5s buys 150 seconds, which a JVM running
Flyway against a database that may itself still be starting can need.

#### The readiness probe was decoration until it was measured

The manifest originally carried a comment claiming Spring's readiness group
turns DOWN when the datasource does. It does not. The default group is
`readinessState` alone, and the measurement was unambiguous:

```
postgres scaled to 0
t= 10s  READY: 1/1 1/1   endpoints ready: true true
...
t=120s  READY: 1/1 1/1   endpoints ready: true true
```

Two minutes of Kubernetes routing traffic to pods that could not serve a single
request, with a green probe. The fix is one config key —
`management.endpoint.health.group.readiness.include: readinessState,db` — and
the same experiment then gives:

```
t= 30s  READY: 0/1 1/1   restarts: 0 0   endpoints ready: false false
        -> readiness went DOWN, and the pods were NOT restarted
```

Then, when Postgres came back, recovery in about twenty seconds with the restart
count still zero. **A probe that cannot fail is decoration.**

#### analytics-service deliberately does the opposite

Its readiness does **not** include the upstream, and that asymmetry is the point.
Measured during the same outage:

```
analytics pods           READY 1/1   restarts=0
its own health           {"status":"UP"}
a request through it     HTTP/1.1 503  {"title":"Upstream unavailable", ...}
```

It could have reported itself not-ready when transaction-service was
unreachable. That would have removed it from its own Service and turned one
service's outage into two — the exact failure the timeouts and the fallback
cache exist to prevent. Readiness means "can I do my job", and this service's
job includes degrading well.

### Kubernetes has no `depends_on`

Everything starts at once. On a cold cluster the ledger came up before Postgres
was accepting connections:

```
Unable to obtain connection from database: Connection to postgres:5432 refused
...
transaction-service   1/1   Running   2 (20s ago)
```

It reached a working state on its own — that is what the restart policy is for —
but "eventually correct after two crashes" reads as a broken deployment in every
dashboard, and the backoff delays the rollout. No probe helps: the process exits
by itself rather than being killed, so there is nothing to be patient about.

An `initContainer` running `pg_isready` in a loop is the mechanism Kubernetes
offers, and it is the same guarantee Compose's `condition: service_healthy`
gives, arrived at differently. Restarts after: **0**.

### What `emptyDir` taught, unintentionally

Deleting the Postgres pod during the probe experiment destroyed its volume. The
replacement came up with an empty database, the still-running ledger pods simply
reconnected to it, and every write started failing:

```
tables in the database: 0
PUT /api/v1/prices -> 500
```

Flyway runs at application startup, so a database recreated underneath a running
application never gets its schema back. Restarting the deployment fixed it in
seconds.

The sharper lesson is about the probe: **readiness stayed green throughout**.
Spring's `db` indicator validates a connection, and a connection to an empty
database is perfectly valid. A connectivity check is not a correctness check,
and no probe here would have caught this.

### Rolling updates, and the request that got dropped

`maxUnavailable: 0` brings a new pod to readiness before taking an old one down.
Measured during a rolling restart of two replicas, polling throughout:

```
before:  31 x 200, 1 x connection failure   (32 requests)
```

Endpoint removal and `SIGTERM` are not ordered: kubelet stops the container at
the same moment the endpoint controller starts telling every node to stop
routing to it, and a node that has not caught up sends a request to a container
already shutting down. Two changes close it — an 8-second `preStop` sleep so
removal propagates, and `server.shutdown: graceful` so whatever is still in
flight completes, with `terminationGracePeriodSeconds: 45` to leave room for
both.

```
after:   56 x 200, 0 failures               (56 requests)
```

### Hardening

`runAsNonRoot` with `runAsUser: 100`, `allowPrivilegeEscalation: false`, all
capabilities dropped, `readOnlyRootFilesystem: true` with an `emptyDir` at
`/tmp` for the JVM's scratch space, and `seccompProfile: RuntimeDefault`. The
image already declares a non-root `USER`; the pod spec makes the cluster refuse
to run it if that ever changes rather than trusting it.

No CPU limit, on purpose: a JVM throttled by a CFS quota during startup takes
far longer to warm up, and the CPU request already guarantees a share. The
memory limit does matter — the image runs with `-XX:MaxRAMPercentage=75`, so the
heap sizes itself from it.

### The same seven figures, now from four places

```
local JVM    ┐
Python       ├─ 0.158612  0.230306  0.236190  0.138144  -0.070159  1.667139
Azure        │
Kubernetes   ┘
```

## CI/CD — Azure Pipelines

[`azure-pipelines.yml`](azure-pipelines.yml). Four stages, because they fail for
different reasons and which one went red is the first thing worth knowing:

```
Verify      ./mvnw verify — every test, nothing skipped
Package     build both images, tag them with the commit, push to ACR
Deploy      az containerapp update, main branch only
SmokeTest   ask the running system a question and check the answer
```

### Tests are not weakened to fit CI

The pipeline runs `./mvnw verify`, the same command as on a laptop. Testcontainers
starts a real PostgreSQL because the agent has a Docker daemon. A pipeline that
quietly skips the database tests proves less than it appears to, and the place
that discovery gets made is production.

Results are published with `PublishTestResults@2`, so a failure is a list of
test names in the UI rather than something to find in two thousand lines of log.
Dependencies are cached on a key derived from the POMs, so the download is
reused by every commit that does not change one.

### Pull requests build; only `main` deploys

```yaml
condition: and(succeeded(), eq(variables['Build.SourceBranch'], 'refs/heads/main'))
```

Without that line every pull request would deploy itself over production.

The deploy is a `deployment` job against an `environment`, not a plain job,
which makes Azure DevOps record what went out, when, and from which commit.

### The smoke test asks a question rather than trusting the exit code

"Deployed successfully" only means the platform accepted a new revision. Three
checks turn that into knowledge:

1. **Readiness**, with a long tolerance — both apps scale to zero, so the first
   request pays for two JVM starts.
2. **The running image tag equals this build's commit.** Without it a failed
   rollout is indistinguishable from a successful one, because the old revision
   answers perfectly well.
3. **A portfolio id that cannot exist must return `404`.** Only the whole chain
   produces that: analytics has to reach transaction-service over internal
   ingress and be told the ledger is empty. If the ledger were unreachable the
   answer would be `503`; if analytics itself were broken, a `5xx`.

That third check is deliberately not an assertion about seeded data. A smoke
test that fails whenever someone resets the database is a smoke test people
learn to ignore.

### Self-hosted agents, and why that is the default

The pipeline defaults to a self-hosted agent, and this is a decision rather than
a fallback.

| | Microsoft-hosted | Self-hosted |
|---|---|---|
| Free grant | requires linking the Azure DevOps organisation to an Azure subscription | **granted automatically** |
| Limits | 1 job, 60 min each, 1,800 min/month | 1 job, no time limit |
| Availability | immediate once linked | immediate |
| Runs when | always | only while the host machine is on |

Microsoft's own spending-limit documentation lists Azure DevOps Services among
the things that can exhaust a subscription's spending limit. The subscription
behind this project is a student grant with a hard cap and a live demo on it, so
the option that does not touch billing wins. `useMicrosoftHostedAgent: true`
switches it, and the `pool` and `JAVA_HOME` both follow from that one parameter.

Public Azure DevOps projects would once have given ten free hosted jobs with no
linkage at all. They can no longer be created, and existing ones convert to
private in 2027.

### What a green run looks like

```
Build and test               succeeded   62s    97 tests, 10 real Postgres containers
Build and push images        succeeded   12s    both images cross-built for amd64
Deploy to Container Apps     succeeded   41s
Prove the deployment works   succeeded   10s
```

The smoke test's own output, which is the part worth reading:

```
target: https://analytics-service.icywater-fe129bae.francecentral.azurecontainerapps.io
{"status":"UP"}
verifying the running revision came from this commit (expecting 0.1.0-927f60c)
running image: acrledgerlens79829b.azurecr.io/analytics-service:0.1.0-927f60c
matches this build
exercising the call chain with a portfolio that cannot exist
status: 404
{"type":".../not-found","title":"Portfolio not found",...}
smoke test passed
```

### The first run failed, and the failure was in the check rather than the deploy

Worth recording because the error message was actively misleading:

```
running image: ...analytics-service:0.1.0-97cc72a
deployed image is not this build
```

Both lines are about the same build. `stageDependencies` can only read stages
the current stage declares a dependency on, and `SmokeTest` said
`dependsOn: Deploy` while reading `stageDependencies.Verify...`. The variable
resolved to an empty string, the shell pattern degenerated to `*:0.1.0-`, and an
assertion that could never pass reported a deployment failure that had not
happened. `Deploy` was unaffected because it lists `Verify` explicitly.

The fix is two lines — `Verify` added to `dependsOn`, which changes no ordering
— plus a guard that fails the stage with the real cause if the expected tag is
ever empty again:

> A check that cannot tell "the wrong build was deployed" from "I could not work
> out which build to expect" is worse than no check, because it sends you to
> look in the wrong place.

Three earlier attempts never reached a run at all: the root `pool` context
rejects template expressions, both a `${{ if }}` block and a bare
`${{ parameters.x }}` reference. Selecting the agent from a parameter was worth
less than a file that runs.

### There are no service connections, and that is forced

An Azure Resource Manager service connection authenticates with a service
principal, which is an app registration. The Entra tenant this subscription
lives in sets:

```
allowedToCreateApps: false
```

for ordinary users, and the account holds no directory role that overrides it —
while being **Owner of the subscription**. It can create any resource, and no
identity to manage them with. That is a common university tenant configuration
and only a tenant administrator can change it.

So the pipeline uses none. The agent is self-hosted on a machine where
`az login` has already happened, and every Azure step runs as that signed-in
user. `az acr login` handles the registry; `az containerapp update` handles the
deploy.

**The cost, stated rather than buried:** the pipeline then acts with a human's
credentials, and that human is subscription Owner — far more authority than a
deploy needs. A service principal scoped to `rg-ledgerlens`, or workload
identity federation, which hands out no long-lived secret at all, is the right
answer; both are one tenant-admin approval away. It also fails when that login
expires, which is a worse failure mode than a secret rotating on a schedule.

One incidental benefit: nothing in the pipeline requires the Azure DevOps
organisation to live in the same tenant as the subscription.

### Running the agent as a service, and the one thing that breaks

The agent is installed with `svc.sh install`, so it runs under launchd rather
than in a terminal — which means it does **not** inherit the shell's
environment. A launchd agent on macOS starts with:

```
PATH=/usr/bin:/bin:/usr/sbin:/sbin
```

Homebrew, the JDK and the Docker CLI are all invisible to it. `runsvc.sh` reads
a `.path` file in the agent's root and exports it, so that file has to name
every directory the pipeline shells out to:

```
/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin
/opt/homebrew/bin
/Applications/Docker.app/Contents/Resources/bin   <- not under Homebrew
/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin
```

Note the third line: Docker Desktop puts its CLI inside the app bundle, not
where the rest of the tooling lives.

`.env` in the same directory carries everything else — `JAVA_HOME` here. It is
read by the agent process itself, not by `runsvc.sh`, which is why grepping
`runsvc.sh` for it finds nothing and why the launchd plist contains only
`VSTS_AGENT_SVC`. The pipeline's first step prints `PATH` and `JAVA_HOME` from
inside a real job, which is the only way to know any of this rather than infer
it — an earlier version of this section asserted `.env` was inert, and the run
log disproved it.

The agent inherits `HOME`, which is what makes the whole design work: `~/.azure`
holds the signed-in session, so `az` in a pipeline step is already
authenticated.

### What still has to be done by hand

Creating the Azure DevOps organisation and registering the self-hosted agent are
browser and terminal steps on the host machine; they authorise access and
cannot be scripted from here.

## Known limitations

Written down rather than left to be discovered. Each is a decision, not an
oversight — but none of them is defensible while it is invisible.

| | |
|---|---|
| **No authentication.** | Half-closed on Azure: `transaction-service` has internal ingress and no public address, so the ledger is not writable from the internet. `analytics-service` is public and read-only. Neither has an identity layer, so anything inside the environment is trusted. |
| **The database firewall allows all Azure services.** The rule is `0.0.0.0–0.0.0.0`, which despite the notation means "any resource in any Azure tenant", not "any IP" — the password is the only barrier. | A deliberate choice for simplicity. The tighter version was one command away: the Container Apps environment has a static outbound IP (`20.19.41.54`) and the firewall could name only that. Private networking with a VNet is the production answer and was out of scope. |
| **Selling more than you hold is allowed**, and produces a negative holding. | Sufficient-quantity is an invariant across the whole ledger, not within one transaction, so enforcing it means reading aggregate state on every write. Not implemented; short positions are therefore representable. |
| **A cash-only portfolio cannot be measured.** With no securities there are no prices, and the valuation calendar is derived from price data, so there are no valuation points. | Fails loudly with a `422`, but the message says "not enough data" rather than naming the real cause. |
| **`GET /api/v1/prices` has no bound** on symbol count or date range, while transactions cap a page at 500. | The asymmetry is unintentional. |
| **Swagger UI is unauthenticated.** | Deliberate here — a reviewer can open the deployed URL and try the API. Gate it behind a profile for anything real. |
| **`PUT /api/v1/prices` returns an untyped map**, the one response in the API without a named schema. | A consumer cannot generate a typed client for it. |
| ~~Nothing enforces that `docs/openapi/` is current.~~ | **Closed.** `OpenApiSpecIsCommittedTest` in both services compares the committed file against what the service publishes and fails the build on drift. It could only be an exact comparison once the `servers` block was declared in configuration instead of inferred from whichever request generated it — which also fixed a spec that claimed `localhost` about a service deployed to Azure. |

## Deliberately out of scope

Kafka, service mesh, Terraform, Helm, Prometheus/Grafana, AKS. Each is a
separate rabbit hole; leaving them out is what makes the timeline real.
