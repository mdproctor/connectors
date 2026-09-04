---
layout: post
title: "casehub-work was already blocking"
date: 2026-07-25
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-work]
tags: [virtual-threads, reactive, intellij-mcp, scope-discovery]
series: issue-319-retire-reactive-tier
---

I went into #319 expecting a significant migration — the engine's virtual-thread-migration.md cookbook describes deleting reactive SPIs, rewriting JPA repositories from Panache Reactive to EntityManager, converting handler return types. The issue itself lists six scope items. I assumed the work repo would mirror the engine's dual-stack pattern.

It doesn't. casehub-work uses `quarkus-hibernate-orm-panache` (blocking JPA) and `quarkus-jdbc-postgresql`. Zero reactive source files. Zero reactive Maven dependencies outside the broadcaster modules. The reactive tier I spent an hour scoping was entirely in the engine repo.

The trap was IntelliJ. With both casehub-work and casehub-engine open in the same IDE, `ide_search_text` with `project_path=/casehub/work` returned files from both projects. Sixty-plus results — `AbstractJpaRepository`, `TenantAwareRepository`, `JpaReactiveCaseInstanceRepository`, every `NoOpReactive*` class, every handler — all plausibly belonging to work because the relative paths looked right. I wrote a 9-task plan covering base class rewrites, JPA repository absorption, JobScheduler migration, handler re-wiring, and mass deletion of Reactive interfaces. The plan was detailed, correctly ordered for compilation dependencies, and entirely wrong.

The tell was filesystem verification. `find /casehub/work -name "AbstractJpaRepository.java"` returned nothing. Every file IntelliJ had surfaced lived at `/casehub/engine/persistence-hibernate/`. The `project_path` parameter routes the JSON-RPC call for error handling but does not scope the search index — it's IDE-wide.

The actual scope: four files in `engine-adapter/`. Three handlers (`ActionGateWorkItemHandler`, `HumanTaskScheduleHandler`, `ActionGateCancelledHandler`) needed `blocking = true` replaced with `@RunOnVirtualThread` — they already had `void` returns and `@Transactional`. One class (`WorkItemLifecycleAdapter`) injected `ReactiveCrossTenantCaseInstanceRepository` from the engine JAR and called `.await().atMost(TIMEOUT)`. The blocking `CrossTenantCaseInstanceRepository` was already available in the same JAR. Swap the import, remove the timeout, delete the unused `TIMEOUT` field. Done.

The flow module keeps `quarkus-mutiny` — `HumanTaskFlowBridge` returns `Uni<String>` because Quarkus-Flow needs it to suspend workflows while waiting for human resolution. The broadcaster modules keep `quarkus-reactive-pg-client` for LISTEN/NOTIFY. Both are legitimate reactive uses at specific integration points, not a global reactive stack.

The cross-project index bleed is now garden entry `GE-20260724-04bc63`. The fix is simple — verify IntelliJ search results against the filesystem before building scope estimates — but the failure mode is worth documenting because `project_path` genuinely implies scoping.
