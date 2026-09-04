# XS/S Backlog Cleanup — Design Spec
**Date:** 2026-05-22  
**Branch:** issue-204-xs-s-backlog-cleanup  
**Issue:** casehubio/work#204  
**Covers:** #200, #202, #203, engine#187, plus two epic work-ends

---

## Scope

Four implementation items and two housekeeping items:

| Item | Issue | Scale | Description |
|------|-------|-------|-------------|
| A | engine#187 | XS | Close — already fixed in engine commit `2aac95e` |
| B | #203 | S | RoundRobinAssignmentStrategy ignores `"round-robin"` config |
| C | #202 | S | Cursor TTL — `last_accessed` column + scheduled GC job |
| D | #200 | S | Inbox filter params — wire all five declared-but-dead params |
| — | epic-exclusion-audit | XS | work-end (no code) |
| — | epic-output-schema | XS | work-end (no code) |

---

## Item A — engine#187 (close, no code)

Engine commit `2aac95e` ("fix: add missing excludedUsers arg to SelectionContext and WorkItemCreateRequest") already appended the `null` 8th arg at both call sites (`WorkOrchestrator.java` and `CaseContextChangedEventHandler.java`). Engine compiles cleanly. Close the issue.

---

## Item B — #203: RoundRobinAssignmentStrategy config bug

### Problem

`RoundRobinAssignmentStrategy`'s CDI constructor selects its internal `WorkerSelectionStrategy` (used to pick workers within each multi-instance batch) based on the routing config. The current check is binary:

```java
this.workerSelectionStrategy = "claim-first".equals(config.routing().strategy())
        ? claimFirst
        : leastLoaded;
```

When `casehub.work.routing.strategy=round-robin`, this silently selects `LeastLoadedStrategy` instead of `RoundRobinStrategy`. The `RoundRobinAssignmentStrategy` (multi-instance batch distributor) uses a different strategy internally than the system-level routing config advertises.

### Fix

Inject `RoundRobinStrategy` as a fourth constructor param and make the selection a three-way switch:

```java
@Inject
public RoundRobinAssignmentStrategy(
        WorkItemsConfig config,
        ClaimFirstStrategy claimFirst,
        LeastLoadedStrategy leastLoaded,
        RoundRobinStrategy roundRobin) {
    this.workerSelectionStrategy = switch (config.routing().strategy()) {
        case "claim-first" -> claimFirst;
        case "round-robin" -> roundRobin;
        default -> leastLoaded;
    };
}
```

The package-private test constructor `RoundRobinAssignmentStrategy(WorkerSelectionStrategy)` is unchanged.

### Protocol check (PP-20260521-903472 — three-place atomicity)

When adding a new built-in strategy, three places must be updated atomically. This is a bug fix on an existing strategy, not a new one. Verified all three places for `"round-robin"`:
- `WorkItemAssignmentService.activeStrategy()` switch — already handles `"round-robin"` ✓
- `@Alternative` exclusion filter in `activeStrategy()` — already excludes `RoundRobinStrategy` ✓  
- `WorkItemsConfig.RoutingConfig.strategy()` Javadoc — already documents `round-robin` ✓

Only `RoundRobinAssignmentStrategy`'s constructor needed fixing.

---

## Item C — #202: Cursor TTL and GC

### Problem

`routing_cursor` rows accumulate indefinitely. When candidate pools change (users added/removed, templates modified), old pool hash rows are never cleaned up. On long-running systems this creates unbounded table growth and stale index entries.

### Design

#### V30 Migration — add `last_accessed`

```sql
ALTER TABLE routing_cursor
    ADD COLUMN last_accessed TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now();
```

#### RoutingCursor entity

Add `lastAccessed` field (`Instant`, `@Column(name = "last_accessed")`). `JpaRoutingCursorStore.acquireNext()` stamps `cursor.lastAccessed = Instant.now()` inside the existing OCC update transaction — no extra query.

#### Config — `WorkItemsConfig.RoutingConfig`

Two new sub-keys:
- `casehub.work.routing.cursor.ttl-days` (int, default `30`) — rows with `last_accessed` older than this many days are eligible for deletion
- `casehub.work.routing.cursor.cleanup-cron` (String, default `"0 2 * * *"`) — cron expression for the cleanup job; `"disabled"` skips scheduling

#### RoutingCursorCleanupJob

`@ApplicationScoped` bean in `runtime/`. Reads `cursorConfig.cleanupCron()` at startup. If `"disabled"` → no-op. Otherwise, runs a single `DELETE FROM routing_cursor WHERE last_accessed < :cutoff` (named param bound to `Instant.now().minus(ttlDays, DAYS)`). Logs count at INFO.

Scheduling uses Quarkus `@Scheduled(cron = "${casehub.work.routing.cursor.cleanup-cron:0 2 * * *}")` with `@ConfigProperty` injection for the ttl-days value.

#### InMemoryRoutingCursorStore

Add no-op `cleanup(Instant cutoff)` method (called in tests only if needed; not part of `RoutingCursorStore` SPI — the job is runtime-only).

### `RoutingCursorStore` SPI

No change to the SPI interface — `cleanup` is a runtime-only concern not expected from alternative backends. Alternative implementations that want GC can implement their own scheduler.

---

## Item D — #200: Inbox filter activation

### Problem

`GET /workitems/inbox` declares five query params that are never applied:
- `status` (`WorkItemStatus`) — declared, ignored
- `priority` (`WorkItemPriority`) — declared, ignored
- `category` (String) — declared, ignored
- `followUp` (Boolean) — declared, ignored
- `outcome` (String) — not yet declared

The API surface lies: callers passing these params receive unfiltered results.

### Design

All five are applied as Java stream post-filters on the `scanRoots` result. The `scanRoots` SPI signature is unchanged — visibility filtering (assignee + candidateGroups) stays in the store; attribute filtering stays in the REST layer. This keeps alternative backends (MongoDB, Redis) free of these concerns.

```java
Stream<WorkItemRootView> stream = workItemStore.scanRoots(assignee, candidateGroups).stream();
if (status != null)   stream = stream.filter(v -> v.workItem().status == status);
if (priority != null) stream = stream.filter(v -> v.workItem().priority == priority);
if (category != null) stream = stream.filter(v -> category.equals(v.workItem().category));
if (followUp != null) stream = stream.filter(v -> Boolean.TRUE.equals(followUp)
                                   ? v.workItem().followUpAt != null
                                   : v.workItem().followUpAt == null);
if (outcome != null)  stream = stream.filter(v -> outcome.equals(v.workItem().outcome));
return stream.map(WorkItemRootResponse::from).toList();
```

**followUp semantics:** `?followUp=true` — has a follow-up date set; `?followUp=false` — no follow-up date. Consistent with `InboxSummaryBuilder`.

**outcome semantics:** exact match on `workItem.outcome` (the resolved outcome name, e.g. `"approved"`). Only meaningful for terminal items (COMPLETED/REJECTED); non-terminal items have `null` outcome and won't match.

`outcome` is added as a new `@QueryParam` on the `inbox()` method signature.

### inboxSummary parity

`GET /workitems/inbox/summary` at line 114 already uses `WorkItemQuery.builder()` with `outcome` support. No change needed there.

---

## Testing

| Item | Test location | Coverage |
|------|--------------|----------|
| B (#203) | `runtime/` — `RoundRobinAssignmentStrategyTest` | Constructor selects correct strategy for each config value |
| C (#202) | `runtime/` — `RoutingCursorCleanupJobTest` | Deletes rows older than TTL; leaves newer rows; disabled cron = no deletion |
| D (#200) | `runtime/` — `WorkItemResourceTest` / `InboxFilterTest` | Each param filters correctly; unset params return all items |

---

## Migration Plan

| Step | Action |
|------|--------|
| 1 | Close engine#187 (no code) |
| 2 | Fix #203 (RoundRobinAssignmentStrategy, tests) |
| 3 | Fix #202 (V30 migration, entity, config, job, tests) |
| 4 | Fix #200 (inbox endpoint, tests) |
| 5 | work-end epic-exclusion-audit |
| 6 | work-end epic-output-schema |
