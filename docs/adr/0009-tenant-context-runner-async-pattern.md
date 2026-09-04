# 0009 — TenantContextRunner for Async and Background Work

Date: 2026-06-09
Status: Accepted

## Context and Problem Statement

Code paths outside REST requests — Quartz timer jobs, @ObservesAsync CDI
event handlers, startup recovery — have no CDI request context and no
CurrentPrincipal. These paths need tenant-scoped store access to process
WorkItems correctly.

## Decision Drivers

* Must work with Quartz jobs (no CDI request scope)
* Must work with @ObservesAsync (fires outside originating request)
* Must be safe with virtual threads (Quarkus default)
* Must be nestable (job triggers event that triggers another job)

## Considered Options

* **TenantContextRunner** — programmatic CDI context activation + TenantHolder
* **Job context principal passing** — serialize CurrentPrincipal into job data
* **Thread-local propagation** — copy principal to child thread
* **Async CurrentPrincipal variants** — separate implementations per context type

## Decision Outcome

Chosen option: **TenantContextRunner**, because it provides a single
mechanism for all async/background paths and composes safely with CDI.

### Positive Consequences

* Single pattern for all non-REST tenant scoping
* Nestable with save/restore (see GE-20260609-a23a8b)
* Works with virtual threads — no thread-local assumptions

### Negative Consequences / Tradeoffs

* Requires explicit wrapping at every async entry point
* Easy to forget — no compile-time enforcement

## Pros and Cons of the Options

### Job context principal passing

* ✅ No CDI activation needed
* ❌ Breaks CDI injection in job handlers — stores can't resolve CurrentPrincipal
* ❌ Different mechanism per context type (Quartz vs CDI events)

### Thread-local propagation

* ✅ Transparent to callers
* ❌ Unsafe with virtual threads — carrier thread reuse corrupts state
* ❌ Unpredictable with Quarkus managed executor

### Async CurrentPrincipal variants

* ✅ Type-safe per context
* ❌ Proliferation — one implementation per context type
* ❌ CDI ambiguity when multiple variants are in scope

## Links

* #256 — Implementation issue
* PP-20260609-fb6563 — Async event tenant context protocol
* GE-20260609-a23a8b — Context nesting gotcha
