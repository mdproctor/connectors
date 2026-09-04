# Design: Migrate work-queues to platform-view subject view toolkit

**Issue:** casehubio/work#312
**Date:** 2026-07-19
**Branch:** issue-312-migrate-queues-platform-view

## Context

casehub-platform now provides a generic subject view toolkit (`platform-view`, `platform-view-inmem`, `platform-view-jpa`) that generalises the filtered-view pattern originally built in casehub-work-queues. This migration replaces work-queues' view infrastructure with the platform toolkit, keeping only WorkItem-specific concerns.

Engine (casehubio/engine#730) will use the same platform toolkit for case queues — the usage pattern is structurally identical: domain-specific label mutation → `SubjectViewOrchestrator.evaluateAndTrack()` → domain-specific event handling.

## Delete — replaced entirely by platform

| work-queues type | Platform replacement |
|---|---|
| `QueueView` entity | `SubjectViewSpec` record (platform manages persistence via `SubjectViewEntity`) |
| `QueueViewStore` + `JpaQueueViewStore` | `SubjectViewStore` + `JpaSubjectViewStore` |
| `QueueMembershipTracker` | `ViewMembershipTracker` |
| `QueueMembershipStore` + `JpaQueueMembershipStore` | `JpaViewMembershipTracker` (implements `ViewMembershipTracker`) |
| `QueueMembershipContext` | `SubjectViewEvaluator.computeEvents()` |
| `WorkItemQueueMembership` entity | `ViewMembershipEntity` (platform-view-jpa) |
| `CrossTenantQueueViewStore` + `JpaCrossTenantQueueViewStore` | `CrossTenantSubjectViewStore` + `JpaCrossTenantSubjectViewStore` (platform#186) |

## Refactor — stays in work-queues, delegates to platform

### FilterEvaluationObserver

120 lines → ~20 lines. Two concerns separated:

1. **INFERRED labels** — `filterEngine.evaluate(wi)` stays. WorkItem-specific multi-pass label evaluation is not a view concern.
2. **Membership diff** — replaced by `SubjectViewOrchestrator.evaluateAndTrack(wi.id, wi.tenancyId, labelPaths)`.

The observer translates `SubjectViewEvent` → `WorkItemQueueEvent` before firing on the CDI event bus. Translation is by enum name — both have ADDED, REMOVED, CHANGED with identical semantics.

```java
@ApplicationScoped
public class FilterEvaluationObserver {
    @Inject FilterEngine filterEngine;
    @Inject WorkItemStore workItemStore;
    @Inject SubjectViewOrchestrator views;
    @Inject Event<WorkItemQueueEvent> queueEventBus;

    @Transactional
    public void onLifecycleEvent(@Observes WorkItemLifecycleEvent event) {
        workItemStore.get(event.workItemId()).ifPresent(wi -> {
            filterEngine.evaluate(wi);

            Set<String> labelPaths = wi.labels.stream()
                .map(l -> l.path).collect(Collectors.toSet());
            views.evaluateAndTrack(wi.id, wi.tenancyId, labelPaths)
                .forEach(ve -> queueEventBus.fire(
                    new WorkItemQueueEvent(ve.subjectId(), ve.viewId(),
                        ve.viewName(),
                        QueueEventType.valueOf(ve.type().name()),
                        ve.tenancyId())));
        });
    }
}
```

### QueueMembershipService

Refactored to use `SubjectViewQuery<WorkItem>` for label-pattern listing, with JEXL post-filtering preserved.

- `evaluateMembers(QueueView)` → `evaluateMembers(SubjectViewSpec)`: delegates to `SubjectViewQuery<WorkItem>.findByView()` for the label-pattern query, then applies JEXL `additionalConditions` post-filter using `FilterEvaluatorRegistry.find("jexl")`.
- `countMembers(QueueView)` → `countMembers(SubjectViewSpec)`: when no `additionalConditions`, uses `SubjectViewQuery<WorkItem>.countByView()` directly (database push-down). When JEXL conditions exist, falls back to `evaluateMembers().size()`.

### WorkItemViewQuery — new SubjectViewQuery<WorkItem> implementation

JPA backend: extends `JpaLabelPatternQuerySupport<WorkItem, WorkItemLabel>` with WorkItem metamodel attributes (labels collection, path attribute, tenancyId attribute).

In-memory backend (test scope): extends `InMemorySubjectViewQuerySupport<WorkItem>` with label extractor, tenancy extractor, and sort field resolver for WorkItem fields.

### QueueResource

REST endpoints unchanged. Internal wiring:

- `list()` — `SubjectViewStore.findByTenancy(tenancyId)` replaces `QueueViewStore.scanAll()`
- `create()` — builds `SubjectViewSpec` record, calls `SubjectViewOrchestrator.saveView()`
- `query()` — `SubjectViewStore.findById()` + `QueueMembershipService.evaluateMembers(spec)`
- `delete()` — `SubjectViewOrchestrator.deleteView(id)` returns boolean for 404 check
- `summary()` / `trend()` — same pattern, use `SubjectViewSpec` instead of `QueueView`
- `streamQueueEvents()` — unchanged (consumes `WorkItemQueueEvent`, not affected)

### QueueSnapshotJob

- `CrossTenantSubjectViewStore.findDistinctTenancyIds()` replaces `CrossTenantQueueViewStore`
- `SubjectViewStore.findByTenancy(tenancyId)` replaces `QueueViewStore.scanAll()`
- `QueueSnapshot.queueViewId` field semantically becomes a `subject_view.id` reference
- `snapshotQueue()` accepts `SubjectViewSpec` instead of `QueueView`

## Events — domain wrapper preserved

`WorkItemQueueEvent` and `QueueEventType` stay as the published domain events. All consumers (broadcasters, SSE streams, external observers) continue to work unchanged. The translation from platform's `SubjectViewEvent`/`ViewEventType` is a one-liner in the observer.

## Stays untouched

- `FilterEngine`/`FilterEngineImpl` — INFERRED label evaluation (WorkItem-specific)
- `WorkItemFilter`, `FilterChain`, `FilterChainStore` — saved filter entities and inverse index
- `JexlConditionEvaluator`, `JqConditionEvaluator`, `FilterEvaluatorRegistry` — expression evaluators
- `WorkItemQueueEventBroadcaster` (Local + Postgres) — event fan-out SPIs
- `QueueSnapshot`, `QueueSnapshotStore`, `JpaQueueSnapshotStore` — trend data (entity stays, FK repointed)
- `WorkItemQueueState` — relinquishable flag (independent of view system)
- `LabelVocabularyService.matchesPattern()` — stays in runtime module (callers outside queues use it)
- `QueueStateResource` — unchanged
- `QueueTrendResponse` — unchanged

## Database migration

### Dependencies (classpath additions)

Add `classpath:db/view/migration` to Flyway locations. This brings platform's:
- V5000: `subject_view` + `view_membership` tables
- V5001: `additional_conditions` column on `subject_view`

### V5003 — data migration and cleanup

```sql
-- 1. Migrate queue_view → subject_view (preserve UUIDs for snapshot FK)
INSERT INTO subject_view (id, name, tenancy_id, label_pattern, scope,
    sort_field, sort_direction, created_at, additional_conditions)
SELECT id, name, tenancy_id, label_pattern, scope,
    sort_field, sort_direction, created_at, additional_conditions
FROM queue_view;

-- 2. Migrate work_item_queue_membership → view_membership
INSERT INTO view_membership (subject_id, view_id, view_name)
SELECT work_item_id, queue_view_id, queue_name
FROM work_item_queue_membership;

-- 3. Repoint queue_snapshot FK
ALTER TABLE queue_snapshot DROP CONSTRAINT fk_queue_snapshot_queue_view;
ALTER TABLE queue_snapshot ADD CONSTRAINT fk_queue_snapshot_subject_view
    FOREIGN KEY (queue_view_id) REFERENCES subject_view(id) ON DELETE CASCADE;

-- 4. Drop old tables
DROP TABLE work_item_queue_membership;
DROP TABLE queue_view;
```

Note: `queue_view` has tenancy_id (V2003) but `subject_view` also has tenancy_id (V5000) — 1:1 mapping. The membership table drops the surrogate `id` and `tenancy_id` columns (platform's `view_membership` uses composite PK `(subject_id, view_id)` only).

### Flyway location configuration

The `application.properties` for queues needs `db/view/migration` added alongside the existing `db/work/migration` location.

## Maven dependencies

Add to `queues/pom.xml`:

| Dependency | Scope | Purpose |
|---|---|---|
| `casehub-platform-view` | compile | `SubjectViewOrchestrator`, `SubjectViewEvaluator` |
| `casehub-platform-view-jpa` | compile | `JpaSubjectViewStore`, `JpaViewMembershipTracker`, `JpaLabelPatternQuerySupport`, Flyway migrations |
| `casehub-platform-view-inmem` | test | `InMemorySubjectViewStore`, `InMemoryViewMembershipTracker`, `InMemorySubjectViewQuerySupport` |

## Protocol compliance

- **queue-filter-scope-management-only** — scope on `SubjectViewSpec` remains management metadata, not an execution predicate. `SubjectViewStore.findByTenancy()` returns all views regardless of scope; `SubjectViewEvaluator` handles scope filtering at evaluation time (when the scope-aware overload is used). No protocol violation.
- **store-tenancy-stamping-on-insert** — platform's `JpaSubjectViewStore.save()` handles tenancy stamping. Work-queues passes `tenancyId` explicitly via `SubjectViewSpec` record construction.

## Future work (not in scope)

- **Platform #185** — proactive membership cleanup on view deletion. Adopt when it lands.
- **Platform #187** — `Labellable` + `LabelRuleEvaluator`. Work's `FilterEngine` can migrate to this in a follow-on issue. Not part of #312.
- **Expression language for additionalConditions** — currently hardcoded to JEXL. Could become language-aware (store language alongside expression). Separate concern.

## Risks

- **Flyway ordering** — platform's V5000-V5001 must run before work's V5003. Flyway runs all migrations in V-number order regardless of classpath location, so V5000 < V5003 guarantees correct ordering. The risk is ensuring both `db/view/migration` and `db/work/migration` locations are registered in `application.properties`.
- **ID preservation** — UUIDs must be preserved during data migration to keep `queue_snapshot` FK intact. The INSERT-SELECT preserves them.
