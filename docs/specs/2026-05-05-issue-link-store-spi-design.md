# IssueLinkStore SPI — Design Spec
**Date:** 2026-05-05
**Issue:** #161
**Epic:** #156 (GitHub-first experience)

---

## Goal

Extract the Panache static calls from `WebhookEventHandler` and `IssueLinkService` behind an injectable `IssueLinkStore` SPI, making both classes fully unit-testable without CDI or a database. Consistent with the `WorkItemStore` / `AuditEntryStore` pattern established in the platform.

---

## Context

`WebhookEventHandler.handle(WebhookEvent)` currently embeds two Panache static calls:
- `WorkItemIssueLink.findByTrackerRef(trackerType, externalRef)`
- `WorkItem.findById(workItemId)`

These prevent unit testing the public entry point without a running Quarkus container. `IssueLinkService` also calls Panache statics directly (`findByRef`, `findByWorkItemId`, `findById`, `persist()`, `delete()`), making it equally container-dependent.

The fix is the same as the existing `WorkItemStore` / `AuditEntryStore` pattern: introduce a SPI interface, provide a JPA default, and provide an in-memory implementation in the testing module.

---

## New Types

### `IssueLinkStore` (SPI)

**Location:** `casehub-work-issue-tracker/src/main/java/io/casehub/work/issuetracker/repository/IssueLinkStore.java`

```java
public interface IssueLinkStore {

    Optional<WorkItemIssueLink> findById(UUID id);

    List<WorkItemIssueLink> findByWorkItemId(UUID workItemId);

    Optional<WorkItemIssueLink> findByRef(UUID workItemId, String trackerType, String externalRef);

    List<WorkItemIssueLink> findByTrackerRef(String trackerType, String externalRef);

    WorkItemIssueLink save(WorkItemIssueLink link);   // persist or update

    void delete(WorkItemIssueLink link);
}
```

`findByRef` returns `Optional` rather than a nullable — consistent with `WorkItemStore.get()` and more idiomatic. `IssueLinkService` callers use `.orElse(null)` where they need nullable behaviour.

### `JpaIssueLinkStore`

**Location:** `casehub-work-issue-tracker/src/main/java/io/casehub/work/issuetracker/repository/jpa/JpaIssueLinkStore.java`

`@ApplicationScoped` default implementation. Delegates to the existing Panache static/instance methods on `WorkItemIssueLink`. No query logic lives here — it is a thin adapter.

### `InMemoryIssueLinkStore`

**Location:** `testing/src/main/java/io/casehub/work/testing/InMemoryIssueLinkStore.java`

`@ApplicationScoped @Alternative @Priority(1)`. Backed by a `ConcurrentHashMap<UUID, WorkItemIssueLink>`. Exposes `clear()` for `@BeforeEach` isolation. Activated automatically when `casehub-work-testing` is on the test classpath alongside `casehub-work-issue-tracker`.

`testing/pom.xml` gains `casehub-work-issue-tracker` as a compile dependency — same pattern as it already depends on `casehub-work` (the runtime artifact).

---

## Modified Classes

### `WebhookEventHandler`

**Before:** two Panache static calls in `handle(WebhookEvent)`.

**After:** inject `IssueLinkStore` and `WorkItemStore`:

```java
@Inject IssueLinkStore linkStore;
@Inject WorkItemStore workItemStore;
@Inject WorkItemService workItemService;

// Package-private test constructor
WebhookEventHandler(IssueLinkStore linkStore, WorkItemStore workItemStore, WorkItemService workItemService) { ... }
```

`handle(WebhookEvent)`:
```java
public void handle(final WebhookEvent event) {
    final var links = linkStore.findByTrackerRef(event.trackerType(), event.externalRef());
    if (links.isEmpty()) { LOG.debugf(...); return; }
    for (final var link : links) {
        workItemStore.get(link.workItemId)
            .ifPresent(wi -> handle(link.workItemId, wi, event));
    }
}
```

`WorkItem.findById` → `workItemStore.get()` returning `Optional` — null check is replaced by `ifPresent`, which is safer and more idiomatic.

### `IssueLinkService`

Inject `IssueLinkStore`. Replace Panache calls:

| Current (Panache) | Replacement |
|---|---|
| `WorkItemIssueLink.findByRef(...)` | `linkStore.findByRef(...).orElse(null)` |
| `WorkItemIssueLink.findByWorkItemId(...)` | `linkStore.findByWorkItemId(...)` |
| `WorkItemIssueLink.findById(linkId)` | `linkStore.findById(linkId).orElse(null)` |
| `link.persist()` | `linkStore.save(link)` |
| `link.delete()` | `linkStore.delete(link)` |
| `link.status = "closed"` (dirty-check) | `link.status = "closed"; linkStore.save(link)` |

The `status = "closed"` update in `onLifecycleEvent` previously relied on Panache dirty-checking to auto-flush. With the SPI, `linkStore.save(link)` makes the flush explicit — same behaviour, more transparent.

`WorkItemIssueLink` entity retains its Panache static methods — `JpaIssueLinkStore` delegates to them. Removing them would be an unnecessary breaking change.

---

## Testing Strategy

### Unit tests (Mockito, no CDI, no DB)

**`InMemoryIssueLinkStoreTest`** — correctness of the in-memory implementation:
- `save` then `findById` → returns saved entity
- `findByWorkItemId` → filters correctly by workItemId
- `findByRef` → returns `Optional.empty()` when not found
- `findByTrackerRef` → returns all matching links (multiple WorkItems linked to same issue)
- `delete` → removed from store; subsequent `findById` returns empty
- `clear()` → store is empty after clear
- Robustness: `findByTrackerRef` with no matches → empty list, no exception
- Robustness: `findById` with unknown UUID → `Optional.empty()`

**`WebhookEventHandlerTest`** — 14 existing package-private tests remain; 5 new tests for `handle(WebhookEvent)`:
- Happy path: single link, WorkItem found → transition applied
- Empty links: `findByTrackerRef` returns empty → no-op, no service calls
- WorkItem not found: `workItemStore.get()` returns empty → skip gracefully
- Multiple links: two WorkItems linked to same tracker ref → both get the transition
- Exception per link swallowed: first WorkItem throws → second still processed

**`IssueLinkServiceTest`** — rewritten as plain unit tests with Mockito mocks:
- `linkExistingIssue` happy path: fetches from tracker, saves via `linkStore.save`
- `linkExistingIssue` idempotent: existing link found → returned without calling tracker
- `createAndLink`: creates issue via provider, saves link
- `listLinks`: delegates to `linkStore.findByWorkItemId`
- `removeLink` found: `linkStore.delete` called
- `removeLink` not found: returns false, no delete call
- `removeLink` wrong workItemId: returns false
- `syncLinks` partial failure: one provider throws → others still synced, count returned
- `onLifecycleEvent` sync: `syncToIssue` called for all linked issues
- `onLifecycleEvent` auto-close: COMPLETED + autoCloseOnComplete=true → `closeIssue` + `linkStore.save`
- `onLifecycleEvent` no provider: unknown trackerType → logged, skipped, no rethrow

### Integration tests (`@QuarkusTest`, H2)

- `GitHubWebhookResourceTest` (5 existing) — exercises full stack through `JpaIssueLinkStore`; no changes
- `IssueLinkResourceTest` (existing) — exercises `IssueLinkService` + `JpaIssueLinkStore` end-to-end

### Robustness tests (unit, in `InMemoryIssueLinkStoreTest`)

- `findByTrackerRef` with no matches → empty list
- `findById` with random UUID → `Optional.empty()`
- `save` with pre-set `id` and `linkedAt` → fields preserved
- `save` with null `id` → `UUID` assigned

---

## Documentation Updates

- **`docs/ARCHITECTURE.md`** — add `IssueLinkStore`, `JpaIssueLinkStore` to the issue-tracker module map; add `InMemoryIssueLinkStore` to the testing module description
- **`CLAUDE.md`** — update casehub-work-issue-tracker section with new `repository/` package; add `InMemoryIssueLinkStore` entry under testing module
- **`docs/DESIGN.md`** — update test counts
- **`docs/workitems-vs-issue-trackers.md`** — no changes needed (user-facing, not architecture)
- **Cross-reference check** — scan all docs for references to `WorkItemIssueLink` Panache statics; update where they describe the internal implementation

---

## File Map

**New files:**
- `casehub-work-issue-tracker/src/main/java/io/casehub/work/issuetracker/repository/IssueLinkStore.java`
- `casehub-work-issue-tracker/src/main/java/io/casehub/work/issuetracker/repository/jpa/JpaIssueLinkStore.java`
- `testing/src/main/java/io/casehub/work/testing/InMemoryIssueLinkStore.java`
- `testing/src/test/java/io/casehub/work/testing/InMemoryIssueLinkStoreTest.java`
- `casehub-work-issue-tracker/src/test/java/io/casehub/work/issuetracker/service/IssueLinkServiceTest.java` (rewritten)

**Modified files:**
- `casehub-work-issue-tracker/src/main/java/io/casehub/work/issuetracker/webhook/WebhookEventHandler.java`
- `casehub-work-issue-tracker/src/main/java/io/casehub/work/issuetracker/service/IssueLinkService.java`
- `casehub-work-issue-tracker/src/test/java/io/casehub/work/issuetracker/webhook/WebhookEventHandlerTest.java`
- `testing/pom.xml` — add `casehub-work-issue-tracker` compile dependency
- `docs/ARCHITECTURE.md`
- `CLAUDE.md`
- `docs/DESIGN.md`

---

## Commit Plan

All commits reference `#161` and parent epic `#156`.

1. `feat(issue-tracker): IssueLinkStore SPI + JpaIssueLinkStore` — interface + default impl
2. `feat(testing): InMemoryIssueLinkStore` — in-memory impl + pom dependency
3. `refactor(issue-tracker): WebhookEventHandler injects IssueLinkStore + WorkItemStore` — refactor + 5 new unit tests
4. `refactor(issue-tracker): IssueLinkService injects IssueLinkStore` — refactor + unit test rewrite
5. `docs: update ARCHITECTURE.md, CLAUDE.md, DESIGN.md for IssueLinkStore` — documentation sweep

---

## Prerequisites

None. This is a pure refactor with no database schema changes and no API changes.
