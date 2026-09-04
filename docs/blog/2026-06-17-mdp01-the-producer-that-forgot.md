---
layout: post
title: "The Producer That Forgot Its Own Interfaces"
date: 2026-06-17
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-work]
tags: [mongodb, cdi, multi-tenancy, cross-tenant]
---

When I filed #267 during the MongoDB completion work, the shape of the bug was already clear: `CrossTenantProducer` hardcodes three JPA concrete types. The cross-tenant path — cleanup jobs, timer recovery — was silently querying an empty JPA store while live data sat in MongoDB. No errors, no exceptions. The cleanup job ran, found nothing, reported success.

The original multi-tenancy spec had it right. `docs/specs/2026-06-08-multi-tenancy-design.md` describes the producer with interface-typed fields. The implementation drifted to concrete JPA types without anyone noticing, because until MongoDB became a real backend, the distinction didn't matter. The JPA impl was the only impl. Interface or concrete — CDI resolved to the same bean.

The fix is three lines in the producer (swap JPA imports for interface imports) plus three new Mongo cross-tenant store classes. Each one is small — `MongoCrossTenantWorkItemStore` is 40 lines, the routing cursor cleanup is a single Panache `delete()` call. The cross-tenant stores don't need tenant filtering because that's the whole point: they operate across all tenants. In MongoDB, unlike PostgreSQL's RLS bypass via `SET LOCAL ROLE casehub_crosstenancy`, cross-tenant access means omitting the `tenancyId` predicate. The security gate stays in the producer's `validateSystemPrincipal()`, not in the stores.

One thing the spec review caught: I'd written "each injects MongoClient" which was wrong. The existing tenant-scoped Mongo stores split into two camps — Panache for standard CRUD, raw `MongoClient` only for operations Panache can't express (atomic `findOneAndUpdate` with `$inc`). All three cross-tenant operations are simple finds and deletes. Panache handles them directly.

The review also pushed for a CDI wiring test — one test that injects via `@CrossTenant CrossTenantWorkItemStore` and verifies the full resolution chain through the producer to the Mongo alternative. The persistence-mongodb test CDI container has everything needed: `SystemCurrentPrincipal` from runtime (always `isCrossTenantAdmin() = true`), the producer itself, and H2 satisfying JPA beans that never get instantiated because the Mongo alternatives win. One assertion catches the deployment scenario the entire issue exists to fix.

The derived `TERMINAL_STATUSES` was a small quality improvement — instead of hardcoding five status names, deriving them from `WorkItemStatus.isTerminal()` keeps the constant in sync with the enum. A maintenance time bomb removed before it could detonate.
