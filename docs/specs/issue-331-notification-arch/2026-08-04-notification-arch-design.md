# Notification Architecture — Observer Pattern and Subscription Engine Migration

**Issue:** #331 (covers #316, #315)
**Branch:** `issue-331-notification-arch`
**Date:** 2026-08-04

## Summary

Two changes to the WorkItem event/notification architecture:

1. **LabelChangeEvent** (#316) — a CDI event seam fired by `LabelRuleEngine` after label evaluation, enabling future observers to react to label additions/removals with in-transaction side-effects.
2. **Subscription bridge** (#315) — `WorkItemLifecycleEvent` implements `SubscribableEvent` and a bridge observer inserts events into the platform's notification DataSource. The `work-notifications` module is deleted entirely.

## Approach

Approach A: CDI event seam + inline subscription bridge.

- LabelChangeEvent as a lightweight CDI event in runtime — no SPI, no new module.
- Subscription bridge in runtime using `Instance<DataSourceRegistry>` for optional activation — no new module.
- Delete `work-notifications` and the `NotificationChannel` SPI from `work-api`.

---

## Part 1: LabelChangeEvent (#316)

### New types

**`LabelChangeEvent`** — record in `io.casehub.work.runtime.filter`:

```java
public record LabelChangeEvent(
    WorkItem workItem,
    List<LabelDelta> deltas
) {
    public record LabelDelta(String path, ChangeType changeType) {}

    public enum ChangeType { ADDED, REMOVED }

    public boolean hasAdditions() {
        return deltas.stream().anyMatch(d -> d.changeType == ChangeType.ADDED);
    }

    public boolean hasRemovals() {
        return deltas.stream().anyMatch(d -> d.changeType == ChangeType.REMOVED);
    }

    public List<LabelDelta> additions() {
        return deltas.stream().filter(d -> d.changeType == ChangeType.ADDED).toList();
    }

    public List<LabelDelta> removals() {
        return deltas.stream().filter(d -> d.changeType == ChangeType.REMOVED).toList();
    }
}
```

`LabelDelta` is deliberately minimal: `persistence` is always `INFERRED` (the engine only touches inferred labels) and `appliedBy` is unavailable for removals (the label object is already gone when the diff runs). If future use cases need richer deltas, the snapshot can be enriched then.

### Where it fires

Inside `LabelRuleEngine.evaluate()`, after the multi-pass evaluation loop completes but **before** the `finally` block clears the `RUNNING` ThreadLocal guard.

Modified flow in `evaluate()`:

1. Capture label snapshot before evaluation — collect current `(path, persistence)` pairs into a `Set`.
2. Strip INFERRED labels + multi-pass evaluation (unchanged logic).
3. Compute diff: compare before-snapshot to current labels → `List<LabelDelta>`.
4. If deltas is non-empty and `labelChangeEvent != null`, fire `labelChangeEvent.fire(new LabelChangeEvent(workItem, deltas))`.
5. `finally` block clears `RUNNING` (unchanged).

### Reentrancy safety

Firing inside the `try` block while `RUNNING.get() == true` means: if any observer triggers a lifecycle event → `FilterEvaluationObserver` → `LabelRuleEngine.evaluate()`, the guard returns immediately at the top of `evaluate()`. No infinite recursion.

Garden entry GE-20260421-cd3f95 documents this exact reentrancy pattern.

### Observer contract

- Observers use `@Observes LabelChangeEvent` — synchronous, same transaction.
- The `WorkItemEntity` entity has its post-evaluation label state (labels already mutated in-memory).
- The delta list shows exactly what changed.
- Observers may modify the WorkItem entity directly (e.g., set priority). The caller (`FilterEvaluationObserver`) persists via `workItemStore.put(wi)` after `evaluate()` returns, so observer mutations are included in the same persist.
- Observers must NOT call `WorkItemService` methods that fire lifecycle events — use direct entity mutation instead.

### Event injection

```java
@Inject
Event<LabelChangeEvent> labelChangeEvent;
```

Guard with `if (labelChangeEvent != null)` for unit test paths where CDI is not active (the existing test constructor doesn't inject CDI).

### Testing

Extend `LabelRuleEngineTest`:
- Verify event fires with correct deltas when labels change.
- Verify no event fires when evaluation produces zero diff (e.g., labels stabilize to same state).
- Verify reentrancy guard holds during observer execution — mock observer that calls `evaluate()` again, assert it returns without recursion.

---

## Part 2: Subscription Bridge (#315)

### Step 1 — WorkItemLifecycleEvent implements SubscribableEvent

One-line change to the class declaration:

```java
public final class WorkItemLifecycleEvent implements WorkItemEvent, SubscribableEvent {
```

`SubscribableEvent` (in `platform-api`) requires:
- `String type()` — already present, returns `"io.casehub.work.workitem.created"` etc.
- `String tenancyId()` — already present, returns the tenant ID.

No new dependencies — `platform-api` is already transitive via `work-api`.

**Why both `SubscribableEvent` and the DataSource bridge:** `SubscribableEvent` is a compile-time type contract — the subscription engine casts to it inside filter predicates to extract `type()` and `tenancyId()` for tenant isolation. It does NOT cause the subscription engine to receive events via CDI. The DataSource bridge is the delivery mechanism — calling `dataSource.add(event)` pushes the event into the alpha network where subscriptions match against it. Both are required; they serve different purposes.

### Step 2 — WorkItemSubscriptionBridge

New class in `io.casehub.work.runtime.event`:

```java
@ApplicationScoped
public class WorkItemSubscriptionBridge {

    private static final Logger LOG = Logger.getLogger(WorkItemSubscriptionBridge.class);

    @Inject
    Instance<DataSourceRegistry> dataSourceRegistryInstance;

    @Inject
    Instance<EventTypeRegistry> eventTypeRegistryInstance;

    void onStartup(@Observes StartupEvent event) {
        if (eventTypeRegistryInstance.isUnsatisfied()) return;

        EventTypeRegistry registry = eventTypeRegistryInstance.get();
        for (WorkEventType wet : EMITTED_EVENT_TYPES) {
            String eventType = WorkCloudEventTypes.PREFIX
                    + wet.name().toLowerCase(Locale.ROOT);
            registry.register(new EventTypeDescriptor(
                eventType,
                "WorkItem " + wet.name().toLowerCase(Locale.ROOT),
                null,
                workItemEventFields()));
        }
    }

    void onWorkItemEvent(
            @Observes(during = TransactionPhase.AFTER_SUCCESS)
            WorkItemLifecycleEvent event) {
        if (dataSourceRegistryInstance.isUnsatisfied()) return;

        try {
            dataSourceRegistryInstance.get()
                .resolveSource(NOTIFICATION_DATASOURCE_PATH, PLATFORM_TENANT_ID)
                .ifPresent(ds -> ds.add(event));
        } catch (Exception e) {
            LOG.warnf("Subscription bridge failed for %s: %s",
                    event.type(), e.getMessage());
        }
    }
}
```

**`EMITTED_EVENT_TYPES`** — a curated subset of `WorkEventType` containing only the event types that `WorkItemService` actually fires (CREATED, ASSIGNED, STARTED, COMPLETED, REJECTED, DELEGATED, ESCALATED, EXPIRED, CANCELLED, FAULTED, OBSOLETE, SPAWNED, PRIORITY_CHANGED, REASSIGNED). Excludes vocabulary-only types like `LABEL_ADDED`/`LABEL_REMOVED` that exist in the enum but are never fired as lifecycle events — registering them would create subscribable event types that silently never match.

Design points:
- **`Instance<DataSourceRegistry>`** — if no subscription engine is on the classpath, `isUnsatisfied()` returns true and the observer is a no-op. Zero cost when inactive.
- **`resolveSource(Path, String)`** — the actual `DataSourceRegistry` API. Uses `NOTIFICATION_DATASOURCE_PATH` (a `Path` constant from `SubscriptionConstants`) and `PLATFORM_TENANT_ID` (the notification DataSource is platform-global).
- **`AFTER_SUCCESS`** — only fires after the WorkItem transaction commits. Consistent with protocol PP-20260609-fb6563.
- **Error isolation** — try-catch wraps the entire bridge call. The existing `NotificationDispatcher` uses `@Transactional(NOT_SUPPORTED)`, `CompletableFuture.runAsync()`, and per-channel try-catch — three layers of defense. The new bridge matches that resilience: an exception from `ds.add()` is caught and logged, never propagated to sibling `AFTER_SUCCESS` observers. This prevents a subscription engine failure from killing the ledger, SSE broadcaster, or other observers.
- **`Locale.ROOT`** — consistent with `WorkItemLifecycleEvent.of()` which uses `Locale.ROOT` for lowercasing event names.
- **No tenant context propagation needed** — `AFTER_SUCCESS` runs on the originating thread. The event carries `tenancyId()`. The subscription engine handles tenant isolation internally via the `SubscribableEvent` contract.
- **Event type registration at startup** — registers only actively-emitted `WorkEventType` values with filterable field descriptors (status, assigneeId, types, outcome, candidateGroups) so the platform subscription REST API can discover available work event types and their constrainable fields.

### Step 3 — Delete work-notifications module

**Files deleted from `notifications/`:**

| File | What it is |
|------|-----------|
| `NotificationDispatcher` | CDI observer + rule matching + async dispatch |
| `NotificationRuleStore` / `JpaNotificationRuleStore` | Rule persistence |
| `WorkItemNotificationRule` | JPA entity |
| `NotificationRuleResource` | REST API for CRUD on rules |
| `SlackNotificationChannel` | Slack delivery |
| `TeamsNotificationChannel` | Teams delivery |
| `HttpWebhookChannel` | Webhook delivery |
| `NotificationsRlsPolicyApplicator` | Row-level security |
| All tests | Unit + integration |

**Files deleted from `api/`:**

| File | What it is |
|------|-----------|
| `NotificationChannel` | SPI interface |
| `NotificationPayload` | Event wrapper |

**Parent pom.xml:** Remove `<module>notifications</module>`.

**Flyway migration:** Existing migrations that created `work_item_notification_rules` stay in place (Flyway requires monotonic history). A new migration drops the table:

```sql
DROP TABLE IF EXISTS work_item_notification_rules;
```

**Integration tests:** Remove notification-related test dependencies and test classes.

**Deployment module:** Remove any notification-related build step registrations.

### Step 4 — Verify no external references to deleted types

- `NotificationChannel` — only implemented and injected within `notifications/`. No external references.
- `NotificationPayload` — only constructed and consumed within `notifications/`. No external references.
- Clean deletion with no ripple outside the module.

---

## Dependency changes

| Module | Change | Why |
|--------|--------|-----|
| `runtime` | `WorkItemLifecycleEvent implements SubscribableEvent` | No new compile dep — transitive via `work-api` → `platform-api` |
| `runtime` | New `WorkItemSubscriptionBridge` | Uses `Instance<DataSourceRegistry>` — same transitive dep |
| `runtime` | New `LabelChangeEvent` + changes to `LabelRuleEngine` | Internal only |
| `api` | Delete `NotificationChannel`, `NotificationPayload` | Pre-release, no external consumers |
| `notifications` | Delete entire module | Replaced by platform subscription engine |
| parent `pom.xml` | Remove `<module>notifications</module>` | Module gone |
| `integration-tests` | Remove notification test deps/classes | No longer applicable |
| `deployment` | Remove notification processor registration | Extension build cleanup |

## Testing

| New code | Test approach |
|----------|--------------|
| `LabelChangeEvent` in `LabelRuleEngine` | Extend `LabelRuleEngineTest` — verify event fires with correct deltas, no event on zero-diff, reentrancy guard holds |
| `WorkItemSubscriptionBridge` | Unit test with mock `DataSourceRegistry` — verify `add()` called, verify no-op when `Instance` unsatisfied |

## Out of scope

- **No data migration** from `WorkItemNotificationRule` → platform `Subscription`. Pre-release, zero production data.
- **No `LabelChangeEvent` → subscription engine integration.** Label changes are in-transaction side-effects; the subscription engine is post-commit. If someone wants "notify on label X applied," they use `WorkItemLifecycleEvent` subscriptions — the label is already applied by then.
- **No changes to existing lifecycle event observers** — `WorkCloudEventAdapter`, `LocalWorkItemEventBroadcaster`, `PostgresWorkItemEventBroadcaster`, `LedgerEventCapture`, `IssueLinkService`, `WorkItemLifecycleAdapter` are all unaffected.

## References

- Platform notification architecture: `casehub-parent/docs/platform/notifications.md`
- Platform subscription engine: `casehub-platform/subscriptions/`
- `SubscribableEvent`: `casehub-platform/platform-api/.../subscription/SubscribableEvent.java`
- Reentrancy guard pattern: garden entry GE-20260421-cd3f95
- Async tenant context protocol: PP-20260609-fb6563
- Overlap risk (notification duplication): `casehub-parent/docs/platform/overlap-risks.md` issue #4
