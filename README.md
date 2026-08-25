# ledger-lens-services

Portfolio analytics rebuilt as Spring Boot microservices, deployed to Azure and
described in Kubernetes manifests. The domain — transactions, holdings, return,
volatility, maximum drawdown, Sharpe ratio — is borrowed from
[Ledger Lens](https://github.com/) (Python/FastAPI); no code is shared, and the
original project is untouched.

| | |
|---|---|
| Java | 21 |
| Spring Boot | 3.5.16 |
| Build | Maven multi-module (wrapper committed) |
| Database | PostgreSQL 16 |
| Tests | JUnit 5, Testcontainers (real Postgres), WireMock |

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
./mvnw -pl transaction-service spring-boot:run
./mvnw -pl analytics-service  spring-boot:run
```

```bash
curl localhost:8081/actuator/health/readiness
curl localhost:8082/actuator/health/liveness
```

`/actuator/health/liveness` and `/actuator/health/readiness` exist from day one
because Kubernetes probes will point at them; liveness answers "is this process
wedged, restart it", readiness answers "may this instance take traffic yet".

## Configuration

No environment-specific value is hard-coded. `application.yml` holds local
development defaults only; Spring Boot maps environment variables onto the same
keys (`SPRING_DATASOURCE_URL`, `LEDGERLENS_TRANSACTIONSERVICE_BASEURL`), which
is how Container Apps and Kubernetes inject them later.

## Deliberately out of scope

Kafka, service mesh, Terraform, Helm, Prometheus/Grafana, AKS. Each is a
separate rabbit hole; leaving them out is what makes the timeline real.
