# Queue Summary REST Endpoint

**Issue:** casehubio/work#288
**Date:** 2026-07-14
**Status:** Approved

## Problem

The blocks-ui queue board component (casehubio/blocks-ui#7) computes queue summary
metrics client-side from the full item list returned by `GET /queues/{id}`. This
works for typical queue sizes but won't scale past ~500 items. A dedicated summary
endpoint returning pre-computed aggregates avoids sending the full list.

### Requirements mapping (from #288)

| Issue requirement | Summary field(s) | Notes |
|---|---|---|
| total items | `total` | Direct |
| priority breakdown | `byPriority` | Direct |
| SLA breach count | `overdue` + `claimDeadlineBreached` | Two distinct SLA metrics — expiry deadline and claim deadline. Provides strictly more information than a single combined count; the client can sum them if needed. |
| oldest item age | `oldestCreatedAt` | Raw instant; client computes age. Follows `QueueHealthReport.oldestUnclaimedCreatedAt` convention. |

## Design

### 1. Rename InboxSummaryBuilder → WorkItemSummaryBuilder

`InboxSummaryBuilder` (in `runtime/service/`) is a pure static function on
`List<WorkItem>` with no inbox-specific logic. Its name reflects its first consumer,
not its actual scope. Rename:

- `InboxSummaryBuilder` → `WorkItemSummaryBuilder`
- `InboxSummary` → `Summary` (nested record)

The nested record is renamed to `Summary` rather than `WorkItemSummary` to avoid
a name collision with `ActorStateResponse.WorkItemSummary` in the `actor-state`
module — a per-item projection with completely different semantics. Since the nested
record is always accessed qualified as `WorkItemSummaryBuilder.Summary`, the name
is unambiguous.

Add `oldestCreatedAt` field — the `min(createdAt)` across non-terminal items.
Nullable (null when no non-terminal items exist). Follows the
`QueueHealthReport.oldestUnclaimedCreatedAt` convention of returning raw instants.

The `createdAt` field is always non-null for persisted WorkItems (`@PrePersist`
sets it to `Instant.now()`), but the min-computation filters nulls defensively —
consistent with the existing null-priority handling in `byPriority`.

```java
public record Summary(
        long total,
        Map<String, Long> byStatus,
        Map<String, Long> byPriority,
        long overdue,
        long claimDeadlineBreached,
        Instant oldestCreatedAt) {}
```

Use IDE rename to propagate to all callers:
- `WorkItemResource` (inbox summary endpoint in `rest/`)
- `InboxSummaryBuilderTest` → `WorkItemSummaryBuilderTest`
- `InboxSummaryTest` (REST integration test)

### 2. New endpoint: GET /queues/{id}/summary

Location: `QueueResource.java` in the `queues` module, alongside the existing
`query()`, `trend()`, and `streamQueueEvents()` methods.

Implementation:
1. Look up `QueueView` by id → 404 if not found
2. `QueueMembershipService.evaluateMembers(queue)` → `List<WorkItem>`
3. `WorkItemSummaryBuilder.build(items, Instant.now())` → `Summary`
4. Return `Summary` as 200 response body

Annotations: `@GET @Path("/{id}/summary") @Transactional`

Error cases: 404 (queue view not found) — same pattern as `query()` and `trend()`.

Response example:
```json
{
  "total": 42,
  "byStatus": {"PENDING": 20, "ASSIGNED": 15, "IN_PROGRESS": 7},
  "byPriority": {"LOW": 10, "MEDIUM": 20, "HIGH": 10, "URGENT": 2},
  "overdue": 3,
  "claimDeadlineBreached": 1,
  "oldestCreatedAt": "2026-07-01T10:30:00Z"
}
```

#### Performance characteristics

This endpoint eliminates serialization and network transfer of the full item list
(the bandwidth cost that makes client-side aggregation impractical above ~500 items).
Server memory cost is unchanged — `evaluateMembers(queue)` loads all matching
WorkItems into the JPA persistence context, iterates them for counts, and discards
them. For a 5,000-item queue, 5,000 entities are loaded server-side.

Database-level aggregation (SQL GROUP BY / COUNT) is the path to reducing server
memory cost. The building blocks partially exist: `QueueMembershipService.countMembers()`
already uses `workItemStore.countByQuery()` (SQL COUNT) for simple queues without
`additionalConditions`. Extending this to status/priority breakdowns and deadline
checks is tracked in #305.

### 3. Testing

**Unit tests (runtime module):**
- Rename `InboxSummaryBuilderTest` → `WorkItemSummaryBuilderTest`
- New test: multiple items with different `createdAt` → oldest non-terminal selected
- New test: all items terminal → `oldestCreatedAt` is null
- New test: empty list → `oldestCreatedAt` is null
- New test: item with null `createdAt` → excluded from min-computation (documents `@PrePersist` invariant)

**REST test (queues module):**
- New `QueueSummaryResourceTest` (follows `QueueResourceTest` pattern)
- Happy path: queue with items at various statuses/priorities → verify all fields
- Empty queue: `total: 0`, empty maps, `oldestCreatedAt: null`
- Queue not found: 404
- Mixed terminal/non-terminal: `oldestCreatedAt` reflects only non-terminal

**Existing inbox test:**
- `InboxSummaryTest` — update for renamed types, verify `oldestCreatedAt` in response

### 4. API reference

Add `GET /queues/{id}/summary` to the Queues section of `docs/api-reference.md`.

Update the existing `GET /workitems/inbox/summary` documentation:
- Change body type from `InboxSummary` to `WorkItemSummaryBuilder.Summary`
- Add `oldestCreatedAt` field (`Instant`, nullable) to the response field table

## Out of Scope

- **Database-level aggregation** — SQL push-down for status/priority GROUP BY and
  deadline COUNT to avoid loading all entities server-side. Tracked in #305.
- **Caching or materialised views** — premature until scale demands it. Tracked in #306.
- No Flyway migration needed — this is a read-only aggregation over existing data
