# Design: Global Queue Health and Enriched List Endpoints

**Branch:** issue-357-global-queue-health-enriched-list
**Issue:** #357
**Date:** 2026-08-17

---

## Problem

The scaffold console's Queues tab references two endpoints that don't exist:

1. `GET /queues` returns only queue metadata (`id`, `name`, `labelPattern`, `scope`) — the UI data table expects per-queue counts (`pendingCount`, `activeCount`, `completedCount`).
2. `GET /queues/health` doesn't exist — the KPI metric row expects an array of health metrics.

Per-queue endpoints work (`GET /queues/{id}/summary`, `/trend`). The gap is global aggregates and enriched list fields.

---

## Change 1: Enrich `GET /queues` List Response

### Current

`QueueResource.list()` returns `List<Map<String, Object>>` with 4 fields per queue:

```java
Map.of("id", q.id(), "name", q.name(), "labelPattern", q.labelPattern(),
       "scope", q.scope() != null ? q.scope().value() : "/")
```

### Design

For each queue in the list, call `QueueMembershipService.summarize(queue, now)` and nest the full `WorkItemSummary` under a `summary` field. The response becomes:

```json
[
  {
    "id": "uuid",
    "name": "Legal Review",
    "labelPattern": "legal/**",
    "scope": "/",
    "summary": {
      "total": 15,
      "byStatus": {"PENDING": 8, "IN_PROGRESS": 5, "COMPLETED": 2},
      "byPriority": {"HIGH": 3, "MEDIUM": 10, "LOW": 2},
      "overdue": 1,
      "claimDeadlineBreached": 0,
      "oldestCreatedAt": "2026-08-10T09:00:00Z"
    }
  }
]

```

**Why full `WorkItemSummary`:** The UI already consumes this shape from `GET /queues/{id}/summary`. Reusing it avoids a second DTO and provides `overdue`, `claimDeadlineBreached`, `byPriority`, and `oldestCreatedAt` for tooltips and column sorting without a future API change.

**Performance:** `summarize()` is cached (`@CacheResult(cacheName = "queue-summary")`) and invalidated on WorkItem lifecycle events. For N queues, this is N in-memory cache lookups — not N database queries.

### Implementation

In `QueueResource.list()`:

1. Inject `Instant now = Instant.now()` once at method entry
2. For each `SubjectViewSpec`, call `membershipService.summarize(spec, now)`
3. Build the response map with the existing 4 fields plus `"summary"` → `WorkItemSummary`

The return type stays `List<Map<String, Object>>` for now. A dedicated response record can be introduced later if the map becomes unwieldy.

---

## Change 2: Add `GET /queues/health`

### Design

New endpoint on `QueueResource` that aggregates all per-queue summaries into a KPI metrics array matching the `blocks-kpi-metric-row` UI component format.

**Endpoint:** `GET /queues/health`
**Response:** `200 OK`

```json
[
  {"key": "total",    "value": 42, "label": "Total",    "status": "neutral"},
  {"key": "pending",  "value": 18, "label": "Pending",  "status": "warning"},
  {"key": "active",   "value": 12, "label": "Active",   "status": "neutral"},
  {"key": "overdue",  "value": 3,  "label": "Overdue",  "status": "critical"},
  {"key": "breached", "value": 1,  "label": "Claim SLA","status": "critical"}
]
```

### Aggregation Logic

1. Load all tenant queues via `viewStore.findByTenancy(tenancyId)`
2. For each queue, call `membershipService.summarize(queue, now)` (cached)
3. Sum across all summaries:
   - `total` = sum of `summary.total`
   - `pending` = sum of `summary.byStatus.get("PENDING")`
   - `active` = sum of `summary.byStatus.get("IN_PROGRESS")` + `summary.byStatus.get("ASSIGNED")`
   - `overdue` = sum of `summary.overdue`
   - `breached` = sum of `summary.claimDeadlineBreached`
4. Assign `status` thresholds:
   - `overdue > 0` → `"critical"`
   - `breached > 0` → `"critical"`
   - `pending > 0` → `"warning"`
   - Otherwise → `"neutral"`

### Response Record

```java
public record QueueHealthMetric(String key, long value, String label, String status) {}
```

Defined as a static inner record in `QueueResource` (or standalone in the `api` package if reused elsewhere). The method returns `List<QueueHealthMetric>`.

### Deduplication

A WorkItem can appear in multiple queues (overlapping label patterns). The health endpoint sums per-queue summaries, so the same WorkItem may be counted twice. This is intentional — the KPI reflects *queue load*, not unique WorkItem count. Each queue's health matters independently. If unique counts are needed later, a separate endpoint can be added.

---

## What's NOT Included

- No new database tables or migrations
- No changes to `WorkItemSummary` or `WorkItemSummaryBuilder`
- No new module — both endpoints are additions to `casehub-work-queues`
- No caching changes — existing `queue-summary` cache is reused
- No changes to per-queue endpoints (`/queues/{id}/summary`, `/queues/{id}/trend`)

---

## Testing

- **Unit test:** `QueueResource.list()` returns `summary` field per queue with correct counts
- **Unit test:** `GET /queues/health` returns correct aggregated KPI array
- **Unit test:** Health status thresholds (`warning`, `critical`, `neutral`) are correct
- **Integration test:** End-to-end with real queues and WorkItems verifying both endpoints

---

## References

- `QueueResource.java:61` — current list() implementation
- `QueueMembershipService.java:33` — cached summarize()
- `WorkItemSummary.java` — summary record shape
- `WorkItemSummaryBuilder.java` — in-memory summary builder
- `PP-20260616-4896da` — queue-filter-scope-management-only protocol (scope is management metadata, not execution filter)
- Issue #357 body — KPI format specification (`blocks-kpi-metric-row`)
