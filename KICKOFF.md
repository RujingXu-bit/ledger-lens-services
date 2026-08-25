I want to build a portfolio project over about two weeks. The goal is to close a specific,
recurring gap in my job applications: Java Spring Boot microservices, Azure, CI/CD and
Kubernetes. The full plan is written up at
`~/Desktop/Job repo/ai-job-search/upskill/2026-08-24_java-cloud-k8s-plan.md` — please read
that first, it has the day-by-day breakdown and the reasoning.

Short version of what I'm building: `ledger-lens-services`, a slice of an existing project of
mine rebuilt as microservices.

- **transaction-service** — owns transactions and holdings, Spring Data JPA over Postgres
- **analytics-service** — computes return, volatility, max drawdown and Sharpe ratio, calls
  transaction-service over REST
- optionally a Spring Cloud Gateway in front

Then: multi-stage Dockerfiles, docker-compose, Azure Container Registry, deploy to Azure
Container Apps, an Azure DevOps CI/CD pipeline, and Kubernetes manifests (deployment, service,
configmap, secret, probes) running locally on `kind`.

**About me, so you pitch it right:**
- I'm finishing a Higher Diploma in Software Development (First Class Honours, Sept 2026).
  Before that, economics — equity research and financial controlling.
- Strong: Python (FastAPI), TypeScript/React, PostgreSQL, Docker, testing. I wrote 174+ backend
  tests plus end-to-end browser tests on the original Ledger Lens.
- Java and Spring Boot: I've studied them, but I have not built anything substantial with them.
- Azure, Kubernetes, microservices architecture: all new to me.
- I learn fast by building — I taught myself pgvector, OCR and Playwright from scratch to ship a
  United Nations Hackathon Finalist project in weeks.

**How I'd like you to work with me:**
- Teach as we go. I want to understand *why* the architecture is the way it is, not just get
  working files — I'll be asked about this in interviews.
- Keep me honest on scope. Explicitly out of scope: Kafka, service mesh, Terraform, Helm,
  Prometheus/Grafana, AKS. Push back if I start wandering.
- Warn me before anything costs money on Azure. I want to set a spending cap on day one and keep
  total spend near zero.
- Testing is the thing I'm best at — use JUnit 5 with Testcontainers for real Postgres, and
  WireMock for the inter-service call. That's what will make this look different from a tutorial.

Let's start with day 1: the Maven multi-module skeleton and Spring Boot setup. Before we write
code, walk me through how the two services should be split and why.
