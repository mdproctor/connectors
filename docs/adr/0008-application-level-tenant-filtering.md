# 0008 — Application-Level Tenant Filtering

Date: 2026-06-09
Status: Accepted

## Context and Problem Statement

CaseHub Work needs multi-tenancy support. WorkItems, templates, and all
supporting entities must be isolated by tenant. The filtering strategy must
work across all persistence backends (JPA/PostgreSQL, MongoDB, InMemory)
and be testable without database-specific infrastructure.

## Decision Drivers

* Must work identically across JPA, MongoDB, and InMemory backends
* Must be testable in @QuarkusTest without PostgreSQL
* PostgreSQL RLS planned as defence-in-depth (#257), not a replacement

## Considered Options

* **Application-level store filtering** — WHERE clauses via CurrentPrincipal.tenancyId()
* **Hibernate @Filter/@FilterDef** — session-level automatic filtering
* **Database-per-tenant** — separate database per tenant
* **Schema-per-tenant** — separate schema per tenant
* **PostgreSQL Row-Level Security only** — database-enforced filtering

## Decision Outcome

Chosen option: **Application-level store filtering**, because it is
backend-agnostic, testable without database infrastructure, and composable
with RLS as a second enforcement layer.

### Positive Consequences

* Works identically across JPA, MongoDB, and InMemory
* Testable in isolation without PostgreSQL
* RLS can be layered on top for Postgres deployments (#257)

### Negative Consequences / Tradeoffs

* Every store method must include tenancyId — easy to miss
* Static Panache calls bypass filtering (see GE-20260609-49bd08)

## Pros and Cons of the Options

### Hibernate @Filter/@FilterDef

* ✅ Automatic — no per-query changes needed
* ❌ Session lifecycle complexity — filter must be activated per request
* ❌ Does not apply to MongoDB or InMemory backends

### Database-per-tenant

* ✅ Strongest isolation
* ❌ Operational overhead — N databases to manage, migrate, back up
* ❌ Cross-tenant queries require connection switching

### Schema-per-tenant

* ✅ Good isolation with shared infrastructure
* ❌ Flyway migration complexity — N schemas to migrate
* ❌ No MongoDB equivalent

### PostgreSQL RLS only

* ✅ Enforcement at database level — application bugs can't leak
* ❌ PostgreSQL-specific — no MongoDB or InMemory support
* ❌ Not testable without PostgreSQL running

## Links

* #256 — Implementation issue
* #257 — PostgreSQL RLS hardening (follow-up)
* PP-20260609-bdac7e — Store tenancy stamping protocol
