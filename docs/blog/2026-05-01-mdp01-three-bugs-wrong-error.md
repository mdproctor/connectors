---
layout: post
title: "Three bugs hiding behind the wrong error"
date: 2026-05-01
type: phase-update
entry_type: note
subtype: diary
projects: [quarkus-work]
tags: [testing, hibernate, occ, mutiny, wiremock, concurrency]
excerpt: "The BackPressureFailure filling the CI log is noise — the actual failure is an OptimisticLockException buried further down, caused by the M-of-N coordinator racing against itself during concurrent claim operations."
---

The tests were failing intermittently. `NotificationDeliveryTest` first — `Thread.sleep(500)` racing with `CompletableFuture.runAsync()` delivery, plus notification rules accumulating in H2 across test runs because nothing was cleaning up. WireMock dynamically allocates ports; if the OS reuses a port from a previous test, old rules in the database suddenly have a live endpoint to hit. Fixed with Awaitility and an `@AfterEach @Transactional` cleanup that deletes rules, audit entries, and work items in FK order.

`WorkItemNativeIT.reports_slaBreaches_e2e_breach_appears` was simpler once identified: the smoke test above it calls `/workitems/reports/sla-breaches` with no parameters, caches a 0-breach result at production TTL, and the e2e test hits the same cache key. Adding `?from=<timestamp>` creates a distinct `CompositeCacheKey`. CLAUDE.md documented this exact pattern from an earlier session — just hadn't applied it here.

## The error that wasn't the error

A CI run was failing, and the job log was full of this:

```
ERROR Exception in SSE server handling:
BackPressureFailure: Could not emit item downstream due to lack of requests
```

My first instinct was that this was the failure. I pulled Claude in to read the full log. Claude came back: the `BackPressureFailure` was noise — `BroadcastProcessor.onNext()` throws when no SSE subscriber is connected. "Lack of requests" means zero consumers, not a slow one. The actual failure was different, buried further down:

```
OptimisticLockException: Unexpected row count (expected row count 1 but was 0)
[update work_item_spawn_group set ... where id=? and version=?]
at WorkItemService.claim()
```

The OCC was on `work_item_spawn_group` — but `WorkItemService.claim()` doesn't write to the spawn group. It only reads it for the `allowSameAssignee` guard check. So why was Hibernate generating an UPDATE for it?

`persistAndFlush()` flushes the entire Hibernate session. Every tracked entity participates, not just the target. `WorkItemSpawnGroup` was loaded in the same transaction as a read-only check, tracked by Hibernate, and flushed alongside the `WorkItemEntity`. The `MultiInstanceCoordinator` concurrently updated the spawn group's `@Version` column. When the flush ran, it generated an UPDATE with the stale version, found 0 rows, and threw OCC.

The fix was one line:

```java
final WorkItemSpawnGroup group =
    WorkItemSpawnGroup.findMultiInstanceByParentId(item.parentId);
if (group != null) {
    em.detach(group);  // read-only — don't let flush race with coordinator
    ...
}
```

The `BackPressureFailure` got fixed too — catch and discard silently. That's the correct hot-stream behaviour when no SSE client is listening. It was never failing tests; just filling logs with a misleading ERROR on every lifecycle event.
