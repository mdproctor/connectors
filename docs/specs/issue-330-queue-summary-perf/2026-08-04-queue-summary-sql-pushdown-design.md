# Queue Summary SQL Push-Down Design

**Issue:** casehubio/work#330 (epic), covers #305, #306
**Branch:** issue-330-queue-summary-perf
**Date:** 2026-08-04

## Problem

Both summary endpoints load ALL matching WorkItem entities into the JPA persistence context to compute 6 aggregate numbers:

- `GET /queues/{id}/summary` — via `QueueMembershipService.evaluateMembers()` → `WorkItemSummaryBuilder.build()`
- `GET /workitems/inbox/summary` — via `WorkItemStore.scan()` → `WorkItemSummaryBuilder.build()`

For large queues or inboxes, this hydrates potentially thousands of entities (with eager-loaded labels and types collections) just to produce counts. The `WorkItemSummaryBuilder` then streams them in Java for `groupingBy`, `count`, and `min` operations that SQL handles natively.

## Summary Type — Promoted to Top-Level

The existing `WorkItemSummaryBuilder.Summary` record is promoted to a top-level type `io.casehub.work.api.WorkItemSummary` in the `api` module, since it's now an SPI-visible contract returned by `WorkItemStore.summaryByQuery()`:

```java
public record WorkItemSummary(
    long total,
    Map<String, Long> byStatus,
    Map<String, Long> byPriority,
    long overdue,
    long claimDeadlineBreached,
    Instant oldestCreatedAt) {}
```

`WorkItemSummaryBuilder` is retained for the entity-loading fallback path (queues with `additionalConditions`) and updated to return `WorkItemSummary`.

## Approach: Shared SummaryQueryBuilder with Multiple Criteria API Queries

Push aggregation to SQL using a shared `SummaryQueryBuilder` in `runtime/` that encapsulates the 3-query aggregation pattern. Both the queue and inbox paths provide base predicates; the builder applies identical GROUP BY / COUNT / MIN logic. This eliminates semantic duplication and creates a single integration point for #306 (caching).

### SummaryQueryBuilder — Shared Aggregation Logic

New class `io.casehub.work.runtime.service.SummaryQueryBuilder` in `runtime/`. Takes an `EntityManager`, base predicates (as a `Function<CriteriaBuilder, Root, Predicate>`), an `Instant` for deadline checks, and a `boolean useDistinct` flag (queue path needs `COUNT(DISTINCT)` due to label join; inbox does not).

Runs 3 Criteria API queries:

**Query 1 — Status groups:**
```sql
SELECT wi.status, COUNT([DISTINCT] wi.id)
FROM work_item wi [JOIN ...caller-provided...]
WHERE [caller predicates]
GROUP BY wi.status
```
Produces `byStatus` map. `total` is the sum of all group counts.

**Query 2 — Priority groups:**
```sql
SELECT wi.priority, COUNT([DISTINCT] wi.id)
FROM work_item wi [JOIN ...caller-provided...]
WHERE [caller predicates] AND wi.priority IS NOT NULL
GROUP BY wi.priority
```
Produces `byPriority` map.

**Query 3 — Scalar aggregates:**
Three scalar values with additional status/deadline filters on top of the caller predicates:

- `overdue`: `COUNT([DISTINCT] wi.id)` WHERE status NOT IN (terminal statuses) AND `expires_at < :now`
- `claimDeadlineBreached`: `COUNT([DISTINCT] wi.id)` WHERE status = 'PENDING' AND `claim_deadline < :now`
- `oldestCreatedAt`: `MIN(created_at)` WHERE status NOT IN (terminal statuses)

Implemented as three individual scalar queries (simplest in Criteria API). Total query count per summary call: 5. All are O(1) aggregates over indexed columns — negligible compared to entity loading.

The non-terminal status filter uses `WorkItemStatus.TERMINAL_STATUSES` — the single source of truth.

Returns `WorkItemSummary`.

### Queue Summary Path — QueueMembershipService

`QueueMembershipService` gets a new `summarize(SubjectViewSpec, Instant)` method that owns the SQL-vs-fallback routing decision — consistent with how `countMembers()` already works:

```java
public WorkItemSummary summarize(SubjectViewSpec queue, Instant now) {
    if (queue.additionalConditions() != null
        && !queue.additionalConditions().isBlank()) {
        return WorkItemSummaryBuilder.build(evaluateMembers(queue), now);
    }
    return viewQuery.summarizeByView(queue, now);
}
```

`WorkItemViewQuery.summarizeByView()` extracts the base predicate (label-pattern join + tenancy) and delegates to `SummaryQueryBuilder` with `useDistinct=true`.

**Predicate extraction:** The existing `findByView()` and `countByView()` inline-build their predicates. Extract a shared helper for the base `Predicate` (label pattern + tenancy). All methods use it.

**QueueResource.summary()** stays thin — delegates entirely to the service:

```java
return Response.ok(membershipService.summarize(spec, Instant.now())).build();
```

### Inbox Summary Path — WorkItemStore + JpaWorkItemStore

**WorkItemStore interface:** Add `summaryByQuery(WorkItemQuery, Instant)` as a default method:

```java
default WorkItemSummary summaryByQuery(WorkItemQuery query, Instant now) {
    return WorkItemSummaryBuilder.build(scan(query), now);
}
```

**JpaWorkItemStore** overrides with an optimised path: extracts `WorkItemQuery` predicates and delegates to `SummaryQueryBuilder` with `useDistinct=false` (no label join, no deduplication needed).

**Predicate extraction:** `JpaWorkItemStore.scan()` already builds predicates from `WorkItemQuery` fields (assignee, candidateGroups, candidateUser, status, priority, type). Extract the predicate builder so `summaryByQuery()` reuses it.

InMemory and MongoDB stores inherit the entity-loading fallback. MongoDB (Tier 2, production) stays on the slow path — acceptable for now since #306 (caching) would mask the performance gap. If MongoDB needs native aggregate pipeline optimization, file a follow-up issue.

**WorkItemResource.inboxSummary() changes:**

```java
// Before
return WorkItemSummaryBuilder.build(workItemStore.scan(qb.build()), Instant.now());

// After
return workItemStore.summaryByQuery(qb.build(), Instant.now());
```

### Transactional Consistency

The 5 queries per summary call execute within the existing `@Transactional` boundary on the REST endpoint. Under READ COMMITTED isolation, concurrent writes between queries can produce slightly inconsistent snapshots (e.g., total from Query 1 may not exactly match the sum of overdue + non-overdue). This is acceptable for a read-only summary endpoint — users expect approximate dashboard counts, not transactionally consistent snapshots. The entity-loading fallback inherently produces a consistent snapshot (single query), so the two paths may differ slightly under concurrent writes.

## Testing Strategy

**Existing unit tests — no changes:** `WorkItemSummaryBuilderTest` validates the in-memory aggregation logic (fallback path).

**New unit tests — `SummaryQueryBuilderTest`:** Pure unit test for the shared builder (if testable without DB) or `@QuarkusTest` in `runtime`. Tests edge cases: empty result set, all-terminal items, null priorities, overdue/breached boundary conditions.

**New unit tests — `WorkItemViewQuerySummaryTest`:** `@QuarkusTest` in the `queues` module:
- Creates WorkItems with known statuses, priorities, deadlines, and labels
- Calls `summarizeByView()` and asserts WorkItemSummary matches expected aggregates
- Edge cases: empty queue, all-terminal items (overdue=0, oldestCreatedAt=null), null priorities excluded, multi-label WorkItems counted once

**New unit tests — `JpaWorkItemStoreSummaryTest`:** `@QuarkusTest` in the `runtime` module:
- Creates WorkItems matching various `WorkItemQuery` filters
- Calls `summaryByQuery()` and asserts correct counts
- Tests assignee/candidateGroup/status/priority/type filter combinations

**Parity test:** At least one `@QuarkusTest` creates a known set of WorkItems and asserts that the SQL path (`summaryByQuery()` or `summarizeByView()`) produces an identical `WorkItemSummary` to the entity-loading fallback (`WorkItemSummaryBuilder.build()`). This is the most direct regression test — if the two paths ever diverge, this catches it.

**Existing integration tests — unchanged:** `QueueSummaryResourceTest` and `SummaryTest` already assert the REST response shape. They validate the optimisation doesn't regress behaviour.

## Scope Boundaries

**In scope (#305):**
- SQL push-down for queue summary (WorkItemViewQuery)
- SQL push-down for inbox summary (JpaWorkItemStore)
- Predicate extraction for reuse
- Fallback path for queues with additionalConditions

**Out of scope (#306):**
- Caching or materialised views — separate issue, depends on #305
