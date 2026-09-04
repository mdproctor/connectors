---
layout: post
title: "Every Query Is a Question of Trust"
date: 2026-06-09
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-work]
tags: [multi-tenancy, architecture, cdi]
---

I'd been putting off multi-tenancy. Not because it was hard to understand — add a
column, filter by it — but because "add a column" in a system with 20+ stores across
three persistence backends means touching everything. Every query, every event handler,
every background job. The question isn't what to add. It's what you'll miss.

The answer: 197 files, 28 commits, and a V35 Flyway migration that adds `tenancy_id`
to every entity in the system.

## The Decision That Shaped Everything

The first real decision was filtering strategy. Hibernate `@Filter` would have been
automatic — attach a filter to the session, queries filter implicitly. But casehub-work
runs on JPA, MongoDB, and InMemory backends. A session-level filter is a JPA concept.
MongoDB doesn't have one. InMemory certainly doesn't.

So: application-level filtering. Every store gets `CurrentPrincipal` injected, every
query includes `tenancyId` in its WHERE clause. This means every store method is
explicitly tenant-scoped. No magic. No implicit behaviour. And no safety net — miss a
query and data leaks silently across tenants.

I recorded this as ADR-0008. PostgreSQL Row-Level Security (#257) comes next as
defence-in-depth, but it supplements the app-level filtering rather than replacing it.

## The Async Problem

REST endpoints have a CDI request scope. `CurrentPrincipal` is available, stores
filter correctly. But Quartz timer jobs? `@ObservesAsync` event handlers? Startup
recovery scans? None of these have a request scope. `CurrentPrincipal` doesn't exist.

`TenantContextRunner` was the answer — a single utility that programmatically activates
a CDI request context, sets the tenant identity, runs the callback, tears it down.
Quartz jobs carry `tenancyId` in their `JobDataMap`. Event handlers extract it from the
source WorkItem. Startup recovery uses `@CrossTenant` stores to scan all tenants, then
wraps each individual timer re-schedule in a tenant context.

The subtlety we missed on the first pass: nesting. If a context is already active and
you set a new `tenancyId`, you've just clobbered the outer caller's tenant. The fix is
save-and-restore — capture the previous value before overwriting, restore it in
`finally`. The kind of bug that doesn't surface until two things happen to overlap, and
when it does, the symptom is "wrong tenant's data" with no error message.

## @CrossTenant — Bounding the Escape Hatch

System-level operations need unfiltered access. Timer recovery needs to find active
items across all tenants. The question is how to bound that access.

We used a CDI qualifier: `@CrossTenant`. Separate store interfaces, separate
implementations, separate producer that validates `isCrossTenantAdmin()` at injection
time. The type system does the enforcement — you physically cannot inject a cross-tenant
store without the annotation. No runtime flag to forget, no conditional to miss.

Each cross-tenant interface exposes only what its use case needs.
`CrossTenantWorkItemStore` has `findActiveWithDeadlines()`. That's it. No `put()`, no
`scanAll()`, no generic escape hatch. The attack surface is the method list, and the
method list is short.

## The Traps

Three things bit us that would bite anyone doing this:

`@CacheResult` doesn't know about tenants. The cache key is derived from method
parameters — if tenant-A and tenant-B call the same report with the same parameters,
the second call gets a cache hit from the wrong tenant. No error, no warning. We
stripped `@CacheResult` from every tenant-scoped method. A tenant-aware key generator
is possible but adds complexity for uncertain gain.

Static Panache calls bypass everything. `WorkItem.list("parentId", id)` looks correct
— it's the documented, idiomatic Panache API. But it's a static call. It doesn't go
through the store. It doesn't see `CurrentPrincipal`. It's an unscoped query in a
multi-tenant system. Every static Panache call in the codebase was a potential data leak.
We replaced them all with store methods.

`@CacheResult` and static Panache are both cases where the framework's documented
API does the wrong thing in a multi-tenant context without telling you. The API looks
identical. The behaviour is fundamentally different.

## Where It Stands

#256 is closed. #255 (persistence-memory review) and #251 (OutcomeCodecs quality) went
in alongside. The system now filters every query by `tenancyId`, scopes every SSE stream
to the caller's tenant, and recovers timers with tenant context intact after restart.

The three protocols — async event propagation, store stamping, and cross-tenant surface
area — are the rules for anyone adding a new store or event handler. The three ADRs
(0008–0010) record the why behind the strategy. The garden entries record the traps
for anyone building multi-tenancy on Quarkus.

PostgreSQL RLS (#257) is the obvious next hardening step — a database-level guarantee
that application bugs can't leak. But the interesting question isn't RLS. It's whether
the `TenantContextRunner` pattern generalises cleanly to the other casehub modules, or
whether each module will rediscover its own version of the nesting problem.
