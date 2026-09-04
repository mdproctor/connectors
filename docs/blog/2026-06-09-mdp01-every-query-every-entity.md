---
title: "Every Query, Every Entity"
date: 2026-06-09
author: mdp
tags: [casehub-work, multi-tenancy, architecture, CDI, quartz]
---

casehub-work had zero tenant scoping. Every WorkItem, every template, every audit entry — visible to every tenant. The platform foundation was ready (CurrentPrincipal.tenancyId() has existed since casehub-engine's multi-tenancy work), but casehub-work hadn't started.

The scope was larger than it looked. A tenancy_id column on WorkItem is the obvious part. The non-obvious part: casehub-work has 12 runtime entities, 9 optional-module entities, 4 CDI event types, 2 SSE broadcaster SPIs, 4 background schedulers, and dozens of static Panache calls scattered across resources, services, policies, and observers — all bypassing any data access layer where filtering could be enforced.

The spec went through six review rounds. Each caught real gaps: SSE streams leaking events across tenants (the broadcaster filtered by workItemId and type, not tenant), WorkItemEventPayload wire DTO missing tenancyId for server-to-server relay, static Panache calls in optional modules that weren't enumerated, a Flyway version collision between the ledger and queues modules sharing the same migration path, and the @CacheResult cache key silently omitting tenancyId because it's injected via CDI rather than passed as a method parameter — a data leak through the performance layer.

The deepest question was cross-tenant access. The first design used isCrossTenantAdmin() as a flag on CurrentPrincipal — stores would check it and conditionally omit the tenant predicate. The review caught this: conditional filtering is exactly what the no-conditional-tenancy-filtering protocol prohibits. The fix was the engine's pattern: separate CrossTenant* store interfaces with narrowly scoped methods, a @CrossTenant CDI qualifier, and a CrossTenantProducer that validates the system principal at startup. The type system bounds the blast radius — you can't accidentally inject a cross-tenant store without the qualifier.

The scheduler rearchitecture came from a first-principles question: why are we polling? ExpiryCleanupJob scans every WorkItem every 30 seconds looking for expired deadlines. The system knows at creation time exactly when each item expires. Polling is O(all active items) every interval, introduces up-to-interval latency, and creates a cross-tenant scanning problem. Per-item Quartz timers carry {workItemId, tenancyId} as job data — exact-time processing, self-contained tenant context, no scanning. The state transition matrix has 18 rows: every lifecycle method that touches expiresAt or claimDeadline gets a corresponding timer schedule/cancel/reschedule call.

TenantScopedPrincipal was the CDI puzzle. It's @RequestScoped (not @Alternative, not @DefaultBean) — which means it wins CDI resolution over MockCurrentPrincipal (@DefaultBean @ApplicationScoped) in any request context. This is correct: TenantContextRunner activates a request context and sets the TenantHolder, and TenantScopedPrincipal delegates to it. But it also wins in REST request contexts where nobody touches TenantHolder — so its defaults must match MockCurrentPrincipal's behaviour exactly, or every existing test breaks silently.

The CI debugging session was instructive in a different way. casehub-ledger published a SNAPSHOT with tenancy-aware method signatures on LedgerEntryRepository. casehub-work's CI resolved the new SNAPSHOT and broke. Local builds passed because .m2 had a locally-installed version with the old signatures. The mismatch between local mvn install and GitHub Packages SNAPSHOT resolution is invisible until CI runs — _remote.repositories shows the local jar has no remote origin, while CI always resolves from the published registry.

Twenty-five store implementations. Sixteen new tenant-scoped, six existing extended, three cross-tenant. Every entity access routed through a store that injects CurrentPrincipal and filters unconditionally. Zero static Panache calls remaining in production code outside store implementations. One protocol captured, three out-of-scope issues filed, one upstream issue opened.

The claim the spec makes — "zero static Panache calls in production code" — is now a verifiable property of the codebase, not an aspiration.
