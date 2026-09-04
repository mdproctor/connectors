# 0010 — Cross-Tenant Access via Type-Safe CDI Qualifier

Date: 2026-06-09
Status: Accepted

## Context and Problem Statement

System-level operations (timer recovery at startup, cross-tenant reporting)
need to query stores without tenant filtering. This access must be bounded,
auditable, and impossible to use accidentally from normal request paths.

## Decision Drivers

* Cross-tenant access must be explicit and auditable
* Accidental injection in request-scoped code must fail at compile/startup time
* Each cross-tenant store must expose only what its use case requires

## Considered Options

* **@CrossTenant CDI qualifier** — separate store interfaces + producer validation
* **Runtime flag on existing stores** — `store.withCrossTenant(true).scanAll()`
* **Separate API layer** — dedicated cross-tenant service classes
* **AOP interceptor** — annotation-driven tenant bypass

## Decision Outcome

Chosen option: **@CrossTenant CDI qualifier**, because the type system
enforces least-privilege and makes cross-tenant access visible at the
injection point.

### Positive Consequences

* Type system prevents accidental cross-tenant injection
* Producer validates isCrossTenantAdmin() at startup
* Each interface is bounded — auditable surface area
* IDE navigation shows all cross-tenant usage via qualifier search

### Negative Consequences / Tradeoffs

* More interfaces to maintain (one per cross-tenant use case)
* Producer pattern adds indirection

## Pros and Cons of the Options

### Runtime flag on existing stores

* ✅ No new interfaces
* ❌ Easy to forget the flag — defaults to cross-tenant silently or vice versa
* ❌ No compile-time enforcement

### Separate API layer

* ✅ Clear separation
* ❌ Too much ceremony — separate service, separate tests, separate wiring
* ❌ Duplicates store logic

### AOP interceptor

* ✅ Clean annotation model
* ❌ Hides the access pattern from code readers
* ❌ Harder to reason about in debugging

## Links

* #256 — Implementation issue
* PP-20260609-2144e0 — Cross-tenant store minimal surface protocol
