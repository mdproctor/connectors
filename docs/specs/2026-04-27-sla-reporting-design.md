# SLA Compliance Reporting — Design Spec

**Date:** 2026-04-27
**Epic:** [#104](https://github.com/casehubio/work/issues/104)
**Status:** approved

---

## Goal

Add four REST reporting endpoints to `quarkus-work` that give dashboards and ops tooling
business-level visibility into SLA compliance, actor performance, throughput trends, and
current queue health — without requiring a data warehouse or custom SQL.

---

## Module Design — `quarkus-work-reports`

Reporting is an **optional module**, not part of the core `runtime`. Users who only need
WorkItem lifecycle management pay nothing — no extra classpath, no extra CDI beans, no extra
REST surface, no Caffeine on the native image.

This follows the same pattern as `quarkus-work-notifications`, `quarkus-work-queues`,
`quarkus-work-ledger`, and `quarkus-work-ai` — all optional, all separate JARs.

`quarkus-work-reports` is a **Jandex library module** (not a full Quarkus extension):
- Depends on `quarkus-work` (runtime) for entity and repository access
- Declares `@Path` and `@ApplicationScoped` CDI beans discovered via Jandex
- Uses `io.smallrye:jandex-maven-plugin` so beans are discovered when consumed as a JAR
- No deployment module needed

The existing scaffold (`ReportResource.java`, test stubs in `runtime/`) moves into this
new module. The files do not stay in `runtime/`.

---

## Scope

| Endpoint | Description | State |
|---|---|---|
| `GET /workitems/reports/sla-breaches` | WorkItems that missed their `expiresAt` deadline | scaffold → move to new module |
| `GET /workitems/reports/actors/{actorId}` | Performance summary for one actor | scaffold → move to new module |
| `GET /workitems/reports/throughput` | Created/completed counts over time, grouped by day/week/month | new |
| `GET /workitems/reports/queue-health` | Current overdue count, avg PENDING age, oldest unclaimed | new |

All four endpoints live in `ReportResource` (`/workitems/reports`) inside `quarkus-work-reports`.

---

## Definitions

### SLA Breach

A WorkItem breaches its SLA when:
- It is **still active** (`PENDING`, `ASSIGNED`, `IN_PROGRESS`, `SUSPENDED`, `ESCALATED`, `EXPIRED`) and `expiresAt` is in the past, **or**
- It reached a **terminal status** (`COMPLETED`, `REJECTED`, `CANCELLED`) and `completedAt > expiresAt`.

WorkItems with no `expiresAt` are excluded from SLA calculations — they carry no SLA commitment.

`completedAt == expiresAt` is **on-time** (breach is strictly `completedAt > expiresAt`).

### Actor Performance

Counts are derived from `AuditEntry` records where the actor matches.
`avgCompletionMinutes` is the average of `(completedAt − assignedAt)` across WorkItems
completed by this actor. Returns `null` when the actor has no completions in range.

### Throughput

- **Created count:** WorkItems whose `createdAt` falls in the bucket period
- **Completed count:** WorkItems in a terminal status whose `completedAt` falls in the bucket period
- These are independent per-bucket counts — a WorkItem created in week 1 and completed in week 3 contributes to both buckets separately

### Queue Health

Point-in-time snapshot at request time. Overdue = active status + `expiresAt` < now.

---

## Endpoint Contracts

### `GET /workitems/reports/sla-breaches`

Query params: `from` (ISO 8601, inclusive), `to` (ISO 8601, inclusive), `category`, `priority`

Response:
```json
{
  "items": [
    {
      "workItemId": "uuid",
      "category": "onboarding",
      "priority": "HIGH",
      "expiresAt": "2026-04-25T09:00:00Z",
      "completedAt": "2026-04-25T11:32:00Z",
      "status": "COMPLETED",
      "breachDurationMinutes": 152
    }
  ],
  "summary": {
    "totalBreached": 3,
    "avgBreachDurationMinutes": 94.5,
    "byCategory": { "onboarding": 2, "review": 1 }
  }
}
```

`breachDurationMinutes` is `max(0, completedAt − expiresAt)` for terminal items,
or `max(0, now − expiresAt)` for still-active items.

### `GET /workitems/reports/actors/{actorId}`

Query params: `from` (ISO 8601), `to` (ISO 8601), `category`

Response:
```json
{
  "actorId": "alice",
  "totalAssigned": 12,
  "totalCompleted": 9,
  "totalRejected": 1,
  "avgCompletionMinutes": 47.3,
  "byCategory": { "onboarding": 5, "review": 4 }
}
```

### `GET /workitems/reports/throughput`

Query params: `from` (ISO 8601, **required**), `to` (ISO 8601, **required**), `groupBy` (`day` | `week` | `month`, default `day`)

Returns HTTP 400 if `from` or `to` is absent — an unbounded throughput scan is not permitted.

Response:
```json
{
  "from": "2026-04-01T00:00:00Z",
  "to": "2026-04-27T23:59:59Z",
  "groupBy": "week",
  "buckets": [
    { "period": "2026-W14", "created": 12, "completed": 8 },
    { "period": "2026-W15", "created": 18, "completed": 15 },
    { "period": "2026-W16", "created": 9,  "completed": 11 }
  ]
}
```

Period label format: `yyyy-MM-dd` (day), `yyyy-'W'ww` (ISO week), `yyyy-MM` (month).

### `GET /workitems/reports/queue-health`

Query params: `category` (optional), `priority` (optional)

Response:
```json
{
  "timestamp": "2026-04-27T17:00:00Z",
  "overdueCount": 5,
  "pendingCount": 23,
  "avgPendingAgeSeconds": 7200,
  "oldestUnclaimedCreatedAt": "2026-04-25T10:00:00Z",
  "criticalOverdueCount": 2
}
```

`oldestUnclaimedCreatedAt` is `null` when there are no PENDING items.

---

## Architecture

### Module structure

```
quarkus-work-reports/
├── pom.xml                                    — depends on quarkus-work (runtime); quarkus-cache; jandex plugin
└── src/
    ├── main/java/io/quarkiverse/work/reports/
    │   ├── api/
    │   │   └── ReportResource.java            — thin HTTP adapter; injects ReportService
    │   └── service/
    │       ├── ReportService.java             — all aggregate queries; @ApplicationScoped
    │       ├── SlaBreachReport.java           — response record (items + summary)
    │       ├── SlaBreachItem.java             — per-item breach record
    │       ├── SlaSummary.java                — totalBreached, avgBreachDurationMinutes, byCategory
    │       ├── ActorReport.java               — response record
    │       ├── ThroughputReport.java          — from, to, groupBy, buckets list
    │       ├── ThroughputBucket.java          — period label + created + completed counts
    │       ├── ThroughputBucketAggregator.java — pure Java day→week/month rollup logic
    │       └── QueueHealthReport.java         — timestamp + overdue/pending counts + ages
    └── test/java/io/quarkiverse/work/reports/
        ├── service/
        │   └── ThroughputBucketAggregatorTest.java  — pure unit tests, no DB
        └── api/
            ├── SlaBreachReportTest.java
            ├── ActorPerformanceReportTest.java
            ├── ThroughputReportTest.java
            └── QueueHealthReportTest.java
```

`ReportResource` is a thin HTTP adapter — no business logic. `ReportService` owns all
queries and assembly. `ThroughputBucketAggregator` is pure Java with no dependencies —
fully unit-testable without Quarkus or a DB.

### Relocation of existing scaffold

The files currently in `runtime/` must move to `quarkus-work-reports/`:
- `runtime/src/main/java/.../api/ReportResource.java` → `quarkus-work-reports/...`
- `runtime/src/test/.../api/SlaBreachReportTest.java` → `quarkus-work-reports/...`
- `runtime/src/test/.../api/ActorPerformanceReportTest.java` → `quarkus-work-reports/...`

The scaffold code also needs the N+1 bug fixed and epic refs corrected (see below).

### Bugs fixed in the existing scaffold

1. **N+1 in `computeByCategory`** — loads WorkItem entities in a loop via `em.find()`.
   Replace with a JPQL `GROUP BY w.category, COUNT(w)` projection.
2. **Wrong epic references** — Javadoc cites `#99` and `#110`/`#111`. Update to `#104`
   and the correct child issue numbers once created.

---

## Query Strategy

### Throughput — HQL `date_trunc` + Java rollup

Query the DB at **day granularity** always:

```jpql
SELECT date_trunc('day', w.createdAt), COUNT(w)
FROM WorkItem w
WHERE w.createdAt >= :from AND w.createdAt <= :to
GROUP BY date_trunc('day', w.createdAt)
ORDER BY date_trunc('day', w.createdAt)
```

Hibernate 6 translates `date_trunc` per dialect — H2 2.x and PostgreSQL both supported
at `'day'` granularity. A second query does the same for `completedAt` on terminal items.

`ThroughputBucketAggregator` then merges day buckets in Java:
- **Week:** ISO week (`yyyy-'W'ww` via `IsoFields.WEEK_OF_WEEK_BASED_YEAR`)
- **Month:** calendar month (`yyyy-MM`)

This avoids dialect inconsistency in week semantics (PostgreSQL ISO week vs MySQL calendar
week). The DB still does all the grouping work; Java only merges the already-small day
result set (≤ 365 rows per year).

### Queue health — JPQL aggregates

Pure JPQL `COUNT` and aggregate queries; no entity loading:

```jpql
SELECT COUNT(w) FROM WorkItem w
WHERE w.status IN :activeStatuses AND w.expiresAt < :now

SELECT COUNT(w), MIN(w.createdAt) FROM WorkItem w
WHERE w.status = io.quarkiverse.work.runtime.model.WorkItemStatus.PENDING
```

### Actor byCategory — JPQL GROUP BY (replacing N+1)

```jpql
SELECT w.category, COUNT(w) FROM WorkItem w
WHERE w.assigneeId = :actorId
  AND w.status = io.quarkiverse.work.runtime.model.WorkItemStatus.COMPLETED
  AND w.completedAt >= :from AND w.completedAt <= :to
GROUP BY w.category
```

---

## Caching

All four endpoints are read-heavy and results change slowly. `quarkus-cache` is a
dependency of `quarkus-work-reports` only — not of `runtime`. Users who do not add
`quarkus-work-reports` get no Caffeine on their classpath.

Annotate service methods with `@CacheResult(cacheName = "reports")`.

Cache TTL: 5 minutes (configurable via `quarkus.cache.caffeine.reports.expire-after-write=5M`).

Cache is invalidated on application restart; no active invalidation needed — stale-by-up-to-5min
is acceptable for reporting.

### Query timeout

Set `jakarta.persistence.query.timeout=30000` (30s) on all report queries as a safety valve
against pathologically wide date ranges.

---

## Testing Strategy

### Unit tests — `ThroughputBucketAggregatorTest`

Pure Java, no DB, no Quarkus:
- Day grouping: items on the same day land in the same bucket
- Week grouping: items Mon–Sun of the same ISO week merge correctly
- Month grouping: items across weeks in the same month merge correctly
- Boundary: item at midnight UTC lands in the correct bucket
- Empty input: returns empty bucket list
- Mixed: some days have created but no completed, and vice versa

### Integration tests — `@QuarkusTest` (H2)

One test class per endpoint. The module's `@QuarkusTest` suite runs against H2 in-memory
with the full `quarkus-work` + `quarkus-work-reports` stack.

**`SlaBreachReportTest`** (scaffold exists, fix and extend):
- Happy path: item completed after `expiresAt` appears in results
- Happy path: item completed before `expiresAt` excluded
- Open item past deadline included
- Boundary: `completedAt == expiresAt` → on-time, not breached
- No `expiresAt` → never appears in breach report
- Filter `from`/`to` by `expiresAt` window — inside included, outside excluded
- Filter `category` — only matching category returned
- Filter `priority` — only matching priority returned
- Summary: `totalBreached` matches `items.size()`
- Summary: `avgBreachDurationMinutes` > 0 when breaches exist
- Summary: `byCategory` groups correctly across categories
- E2E: 2 breaches + 1 on-time — both breach items present, on-time item absent

**`ActorPerformanceReportTest`** (scaffold exists, fix and extend):
- Zero counts for actor with no activity
- Unknown actor → 200 with all zero counts, no 404
- `totalAssigned` increments per claim
- `totalCompleted` increments per completion
- `totalRejected` increments per rejection
- `avgCompletionMinutes` is null when no completions
- `avgCompletionMinutes` is `assignedAt → completedAt`, not `createdAt → completedAt`
- `avgCompletionMinutes` ≥ 0 when completions exist
- `byCategory` counts per category
- `from`/`to` filter scopes counts; far-future `from` returns zeros
- `category` filter scopes to that category only
- E2E: 2 completed + 1 rejected + 1 in-flight — verify all counts

**`ThroughputReportTest`** (new):
- Happy path: items created/completed in range appear in correct day bucket
- Items outside range excluded
- `groupBy=day`: one bucket per day with activity
- `groupBy=week`: items spanning same ISO week land in one bucket
- `groupBy=month`: items spanning same month land in one bucket
- No items in range: empty `buckets` list
- Created and completed counts are independent (in-flight item appears in created only)
- Boundary: item at exactly `from`/`to` boundary is included
- Missing `groupBy`: defaults to `day`
- Missing `from` or `to`: HTTP 400
- Response echoes `from`, `to`, `groupBy`

**`QueueHealthReportTest`** (new):
- `pendingCount` reflects current PENDING items
- `overdueCount` reflects active items past `expiresAt`
- `criticalOverdueCount` is subset of `overdueCount`
- `avgPendingAgeSeconds` ≥ 0
- `oldestUnclaimedCreatedAt` is null when no PENDING items
- `oldestUnclaimedCreatedAt` present when PENDING items exist
- Completed items excluded from all counts
- `category` filter scopes all counts
- `priority` filter scopes all counts
- All items completed: all zero counts, null `oldestUnclaimedCreatedAt`
- E2E: PENDING + overdue CRITICAL + completed — verify all fields correct

### Robustness tests (within integration suite)

- Invalid `priority` enum value → 400
- Invalid ISO 8601 date string → 400
- Extremely wide date range (2000–2099) → 200 within timeout

### E2E — `@QuarkusIntegrationTest`

Add `WorkItemReportNativeIT` to `integration-tests/` (add `quarkus-work-reports` as test
dependency in that module):
- Smoke all four endpoints: 200 + correct JSON structure
- One full SLA breach scenario end-to-end

### Testcontainers PostgreSQL — dialect validation

Add a `PostgresTestProfile` in `quarkus-work-reports/src/test/` using `quarkus-jdbc-postgresql`
+ Testcontainers. Run the throughput report with `groupBy=day`, `groupBy=week`,
`groupBy=month` against a real PostgreSQL instance. This is the drift guard for Hibernate 6
HQL `date_trunc` dialect translation — H2 will not catch a PostgreSQL-specific regression.

---

## Documentation Updates

- **`docs/DESIGN.md`** — add reporting section: module, endpoints, query strategy, caching
- **`CLAUDE.md` project structure** — add `quarkus-work-reports/` module with its classes
- **Javadoc** — correct all `@see` references from `#99` → `#104` and child issue numbers
- **`docs/api-reference.md`** — add report endpoints with request/response examples

---

## Issues to Create Under Epic #104

| # | Title |
|---|---|
| A | Scaffold `quarkus-work-reports` module; relocate and fix existing SLA/actor scaffold |
| B | Add throughput time-series endpoint with full TDD |
| C | Add queue-health endpoint with full TDD |
| D | Add E2E and Testcontainers PostgreSQL dialect tests for report endpoints |
