# WorkCloudEventAdapter — Design Spec

**Issue:** casehubio/work#273
**Date:** 2026-06-23

## Problem

casehub-ras needs to observe WorkItem lifecycle events for situational awareness (e.g. "3 SLA breaches in 10 minutes across the same case type") without coupling to casehub-work types. The platform-standard decoupling mechanism is CloudEvents — every other foundation module (IoT, Qhorus, Connectors) already has a CloudEvent adapter. casehub-work does not.

A prerequisite gap blocks a clean `@ObservesAsync` adapter: 13 methods in `WorkItemService` fire lifecycle events with `fire()` only (no `fireAsync()`). Terminal transitions (COMPLETED, REJECTED, CANCELLED, FAULTED, OBSOLETE) already fire both `fire()` and `fireAsync()`, but non-terminal transitions (CREATED, ASSIGNED, STARTED, DELEGATED, etc.) fire sync only. An `@ObservesAsync` adapter would miss those 13 event types.

This gap also causes an existing bug: `QueueDashboard.onLifecycleEvent(@ObservesAsync)` misses all non-terminal events — the dashboard refreshes only on terminal transitions.

## Scope

Four changes, one issue:

1. **Extract `WorkItemLifecycleEmitter`** — structural enforcement of dual-channel firing
2. **Normalize event firing** — all lifecycle events populate both CDI channels via the emitter
3. **Add `WorkCloudEventAdapter`** — bridges both event types to `CloudEvent` via `@ObservesAsync`
4. **File parent evaluation issue** — evaluate dual-channel CDI firing as a platform-wide standard

## Change 1: WorkItemLifecycleEmitter

### Problem with convention-based dual-channel

Adding `fireAsync()` to the 13 fire()-only sites fixes the current gap but recreates the same fragility: the next developer who adds a lifecycle method must remember both calls. If they forget `fireAsync()`, the async channel silently loses events with no compile-time or test-time signal. This is exactly the class of bug we are fixing.

### Design

Extract a `WorkItemLifecycleEmitter` bean that makes dual-channel delivery structural:

```java
@ApplicationScoped
public class WorkItemLifecycleEmitter {
    private static final Logger LOG = Logger.getLogger(WorkItemLifecycleEmitter.class);
    @Inject Event<WorkItemLifecycleEvent> delegate;

    public void emit(WorkItemLifecycleEvent event) {
        delegate.fire(event);
        delegate.fireAsync(event)
            .exceptionally(ex -> {
                LOG.warnf(ex, "Async lifecycle observer failure for %s", event.type());
                return null;
            });
    }
}
```

A matching `WorkItemGroupLifecycleEmitter` wraps `Event<WorkItemGroupLifecycleEvent>`:

```java
@ApplicationScoped
public class WorkItemGroupLifecycleEmitter {
    private static final Logger LOG = Logger.getLogger(WorkItemGroupLifecycleEmitter.class);
    @Inject Event<WorkItemGroupLifecycleEvent> delegate;

    public void emit(WorkItemGroupLifecycleEvent event) {
        delegate.fire(event);
        delegate.fireAsync(event)
            .exceptionally(ex -> {
                LOG.warnf(ex, "Async group lifecycle observer failure for group %s", event.groupId());
                return null;
            });
    }
}
```

**Location:** `runtime/src/main/java/io/casehub/work/runtime/event/` — alongside `WorkItemLifecycleEvent`.

### Migration

Four classes change their injection from `Event<WorkItemLifecycleEvent>` to `WorkItemLifecycleEmitter`:
- `WorkItemService`
- `ExpiryLifecycleService`
- `WorkItemSpawnService`
- `QueueStateResource`

One class changes from `Event<WorkItemGroupLifecycleEvent>` to `WorkItemGroupLifecycleEmitter`:
- `MultiInstanceGroupPolicy`

Each call site simplifies from:

```java
lifecycleEvent.fire(evt);
lifecycleEvent.fireAsync(evt);
```

to:

```java
lifecycleEmitter.emit(evt);
```

This also eliminates the defensive `if (lifecycleEvent != null)` checks scattered across 16+ sites. CDI-injected beans are never null; the null check was defensive against test scenarios where CDI is absent. The emitter handles this cleanly — tests inject a mock emitter.

### Why this is the right design

- Makes dual-channel delivery structural, not conventional — impossible to fire one without the other
- `.exceptionally()` on `fireAsync()` makes async observer failures visible — without it, exceptions from `@ObservesAsync` handlers propagate into CDI's managed executor and are silently swallowed (same failure mode the canonical adapter pattern addresses for `cloudEventBus.fireAsync()`)
- The migration is mechanical — 5 injection changes, ~20 call sites updated
- Breaking callers is the point — it forces every site through the emitter

## Change 2: Event Firing Normalization

### Current state

The firing pattern in `WorkItemService` is intentionally split by terminal vs non-terminal:

| Methods | `fire()` | `fireAsync()` |
|---------|----------|---------------|
| create, claim, start, delegate, acceptDelegation, declineDelegation, release, suspend, resume, progress, extend, addLabel, removeLabel | YES | NO |
| complete (both variants), reject (both variants), completeFromSystem, rejectFromSystem, cancel, cancelFromSystem, fault, faultFromSystem, obsolete, obsoleteFromSystem | YES | YES |

Zero methods are `fireAsync()`-only in `WorkItemService`. Every method that calls `fireAsync()` also calls `fire()` on the preceding line.

### Existing bug this fixes

| Observer | Annotation | Currently misses | Impact |
|----------|-----------|-----------------|--------|
| `QueueDashboard` | `@ObservesAsync` | CREATED, ASSIGNED, STARTED, DELEGATED, DELEGATION_ACCEPTED, DELEGATION_DECLINED, RELEASED, SUSPENDED, RESUMED, PROGRESS_UPDATE, DEADLINE_EXTENDED, LABEL_ADDED, LABEL_REMOVED | Dashboard refreshes only on terminal transitions — never shows new or reassigned work |

`LocalWorkItemEventBroadcaster` (`@Observes`), `WorkItemMetrics` (`@Observes`), and `FilterEvaluationObserver` (`@Observes`) are NOT affected — they receive all events correctly via `fire()`.

`MultiInstanceCoordinator` (`@ObservesAsync`) is safe — it filters on `child.status.isTerminal()`, and all terminal transitions already have `fireAsync()`.

### Sites migrated to emitter

All sites listed below migrate from direct `Event` injection to `WorkItemLifecycleEmitter.emit()`. The emitter handles both channels.

**WorkItemService** — 13 methods that currently call `fire()` only (gain `fireAsync()` via emitter):
- `create()` — CREATED
- `claim()` — ASSIGNED
- `start()` — STARTED
- `delegate()` — DELEGATED
- `acceptDelegation()` — DELEGATION_ACCEPTED
- `declineDelegation()` — DELEGATION_DECLINED
- `release()` — RELEASED
- `suspend()` — SUSPENDED
- `resume()` — RESUMED
- `progress()` — PROGRESS_UPDATE
- `extend()` — DEADLINE_EXTENDED
- `addLabel()` — LABEL_ADDED
- `removeLabel()` — LABEL_REMOVED

**WorkItemService** — 12 methods that already call both `fire()` and `fireAsync()` (simplified to single `emit()` call):
- `complete()` (both variants), `reject()` (both variants), `completeFromSystem()`, `rejectFromSystem()`, `cancel()`, `cancelFromSystem()`, `fault()`, `faultFromSystem()`, `obsolete()`, `obsoleteFromSystem()`

**Other classes** — currently call `fire()` only (gain `fireAsync()` via emitter):
- `ExpiryLifecycleService.fireLifecycleEvent()` — expiry/SLA events (EXPIRED, ESCALATED, CLAIM_EXPIRED, SLA_REASSIGNED, SLA_EXTENDED)
- `WorkItemSpawnService.spawn()` — SPAWNED
- `QueueStateResource.pickup()` — ASSIGNED

**WorkItemGroupLifecycleEvent** — currently calls `fireAsync()` only (gains `fire()` via emitter):
- `MultiInstanceGroupPolicy.fireEvent()` — group status events (IN_PROGRESS, COMPLETED, REJECTED)

This is the only `fireAsync()`-only site in the codebase. Adding `fire()` via the emitter enables future `@Observes` handlers for group events. No existing `@Observes WorkItemGroupLifecycleEvent` handlers exist today, so this is a capability expansion with no behaviour change.

### Alternative considered: `@Observes(during = TransactionPhase.AFTER_SUCCESS)`

Four existing observers in casehub-work use this pattern: `NotificationDispatcher`, `IssueLinkService`, `PostgresWorkItemEventBroadcaster`, `PostgresWorkItemQueueEventBroadcaster`.

If the adapter used `@Observes(during = AFTER_SUCCESS)` for `WorkItemLifecycleEvent`, it would catch all events via `fire()` (which every method already calls) and run after the transaction commits. This would eliminate the normalization for `WorkItemLifecycleEvent`.

**Why `@ObservesAsync` is preferred despite this:**
1. **Normalization is needed anyway** — `QueueDashboard` (`@ObservesAsync`) is broken without it. The normalization fixes an existing bug independent of the adapter.
2. **Full decoupling** — `@ObservesAsync` runs on a managed executor thread, adding zero latency to the caller. `AFTER_SUCCESS` runs on the caller's thread after commit.
3. **Symmetric adapter** — both `onWorkItemLifecycle()` and `onGroupLifecycle()` use `@ObservesAsync`. The `AFTER_SUCCESS` approach would be asymmetric (`AFTER_SUCCESS` for one, `@ObservesAsync` for the other) because `WorkItemGroupLifecycleEvent` is `fireAsync()`-only.
4. **Canonical pattern consistency** — the GE-20260621-629712 pattern uses `@ObservesAsync`. All three existing adapters (IoT, Qhorus, Connectors) use `@ObservesAsync`. The garden entry was derived from repos that are async-only, but the principle (decoupled, post-commit CloudEvent emission) applies regardless of the source repo's firing pattern.

### Why dual-channel (not async-only)

casehub-work has sync observers with legitimate transactional consistency requirements:
- `FilterEvaluationObserver` (`@Observes`) — queue membership updates atomic with the WorkItem mutation
- `LocalWorkItemEventBroadcaster` (`@Observes`) — ordered SSE delivery within transaction boundary
- `WorkItemMetrics` (`@Observes`) — counters incremented within transaction boundary

Plus four `@Observes(during = AFTER_SUCCESS)` observers that depend on the sync channel:
- `NotificationDispatcher`, `IssueLinkService`, `PostgresWorkItemEventBroadcaster`, `PostgresWorkItemQueueEventBroadcaster`

Eliminating `fire()` would break all of these. The correct approach is to populate both channels so each observer can choose the delivery semantics it needs.

## Change 3: WorkCloudEventAdapter

### Location

`runtime/src/main/java/io/casehub/work/runtime/event/WorkCloudEventAdapter.java`

In `runtime/` — consistent with QhorusCloudEventAdapter placement. No new module; `cloudevents-core` is already transitive via `casehub-platform-api`.

### Structure

Single `@ApplicationScoped` class. Two `@ObservesAsync` methods. Follows the canonical 6-rule pattern (GE-20260621-629712).

```
@ApplicationScoped
WorkCloudEventAdapter
  - Event<CloudEvent> cloudEventBus  (injected)
  - ObjectMapper objectMapper        (injected — Rule 1)
  + onWorkItemLifecycle(@ObservesAsync WorkItemLifecycleEvent)
  + onGroupLifecycle(@ObservesAsync WorkItemGroupLifecycleEvent)
```

### Canonical pattern compliance

| Rule | Implementation |
|------|---------------|
| ObjectMapper injected | Constructor injection — never static |
| tenancyId null-safe | Guard before `withExtension("tenancyid", ...)` |
| fireAsync `.exceptionally()` | `LOG.warnf(ex, ...)`, return null |
| Serialisation error | Catch `JsonProcessingException` at WARN, fire with `new byte[0]` |
| Type from stable semantic field | WorkItem: reuse `event.type()`. Group: derive from `groupStatus()` |
| WARN severity | All degraded paths |
| datacontenttype | `application/json` |

### CloudEvent field mapping

**WorkItemLifecycleEvent → CloudEvent:**

| CloudEvent field | Source |
|-----------------|--------|
| `specversion` | `1.0` (via `CloudEventBuilder.v1()`) |
| `id` | `UUID.randomUUID().toString()` |
| `type` | `event.type()` — already `io.casehub.work.workitem.<event>` |
| `source` | `URI.create(event.sourceUri())` — already `/workitems/{id}` |
| `subject` | `event.subject()` — WorkItem UUID string |
| `time` | `event.occurredAt().atOffset(ZoneOffset.UTC)` |
| `datacontenttype` | `application/json` |
| `data` | `objectMapper.writeValueAsBytes(event)` — 11 JSON-visible fields (see below) |
| `tenancyid` ext | `event.tenancyId()` — null-safe |

**`time` uses the event's semantic timestamp** (`event.occurredAt()`, set at event construction during the service method), not the adapter's processing time. This differs from the Qhorus reference adapter which uses `OffsetDateTime.now(ZoneOffset.UTC)`. The event timestamp is correct — it records when the transition occurred, not when the CloudEvent was built. Do not "fix" this to match the Qhorus adapter.

**Data payload fields (11):** `type`, `sourceUri`, `subject`, `workItemId`, `status`, `occurredAt`, `actor`, `detail`, `rationale`, `planRef`, `outcome`. Five accessors are `@JsonIgnore` and excluded: `workItem` (entity), `tenancyId` (carried as CloudEvent extension instead), `eventType()`, `context()`, `source()`.

**WorkItemGroupLifecycleEvent → CloudEvent:**

| CloudEvent field | Source |
|-----------------|--------|
| `specversion` | `1.0` (via `CloudEventBuilder.v1()`) |
| `id` | `UUID.randomUUID().toString()` |
| `type` | `"io.casehub.work.group." + event.groupStatus().name().toLowerCase(Locale.ROOT)` |
| `source` | `URI.create("/workitems/groups/" + event.groupId())` |
| `subject` | `event.groupId().toString()` |
| `time` | `event.occurredAt().atOffset(ZoneOffset.UTC)` |
| `datacontenttype` | `application/json` |
| `data` | `objectMapper.writeValueAsBytes(event)` — all fields serializable |
| `tenancyid` ext | `event.tenancyId()` — null-safe |

**Note:** `WorkItemGroupLifecycleEvent` has no `@JsonIgnore` annotations, so `tenancyId` appears in both the data payload and the CloudEvent extension. This is redundant but not harmful — the extension is the canonical location for routing; the data payload provides it for consumers that parse the data without inspecting extensions. This differs from `WorkItemLifecycleEvent` where `tenancyId` is `@JsonIgnore` and only appears in the extension.

### Rolled-back transaction semantics

`@ObservesAsync` dispatches to the managed executor before the transaction commits. A rolled-back transaction may still produce a CloudEvent. This is inherent to `@ObservesAsync` and shared with `MultiInstanceCoordinator` and `QueueDashboard`. CloudEvent consumers (casehub-ras) must be idempotent and tolerate spurious events from rolled-back transactions.

### CloudEvent type catalogue

CloudEvent `type` is derived from the event name passed to `WorkItemLifecycleEvent.of()`, not from `WorkItemStatus`. Two status values (PENDING, IN_PROGRESS) are never event names — transitions into those statuses fire events named after the action (CREATED, RELEASED, STARTED, etc.).

**WorkItem lifecycle — 24 types** (traced from every production `WorkItemLifecycleEvent.of()` call):

| CloudEvent `type` | Source method(s) |
|---|---|
| `io.casehub.work.workitem.created` | `WorkItemService.create()` |
| `io.casehub.work.workitem.assigned` | `WorkItemService.claim()`, `QueueStateResource.pickup()` |
| `io.casehub.work.workitem.started` | `WorkItemService.start()` |
| `io.casehub.work.workitem.completed` | `WorkItemService.complete()` (both), `completeFromSystem()` |
| `io.casehub.work.workitem.rejected` | `WorkItemService.reject()` (both), `rejectFromSystem()` |
| `io.casehub.work.workitem.delegated` | `WorkItemService.delegate()` |
| `io.casehub.work.workitem.delegation_accepted` | `WorkItemService.acceptDelegation()` |
| `io.casehub.work.workitem.delegation_declined` | `WorkItemService.declineDelegation()` |
| `io.casehub.work.workitem.released` | `WorkItemService.release()` |
| `io.casehub.work.workitem.suspended` | `WorkItemService.suspend()` |
| `io.casehub.work.workitem.resumed` | `WorkItemService.resume()` |
| `io.casehub.work.workitem.cancelled` | `WorkItemService.cancel()`, `cancelFromSystem()` |
| `io.casehub.work.workitem.faulted` | `WorkItemService.fault()`, `faultFromSystem()` |
| `io.casehub.work.workitem.obsolete` | `WorkItemService.obsolete()`, `obsoleteFromSystem()` |
| `io.casehub.work.workitem.spawned` | `WorkItemSpawnService.spawn()` |
| `io.casehub.work.workitem.expired` | `ExpiryLifecycleService.executeFail()` |
| `io.casehub.work.workitem.escalated` | `ExpiryLifecycleService.executeExhausted()` |
| `io.casehub.work.workitem.progress_update` | `WorkItemService.progress()` |
| `io.casehub.work.workitem.deadline_extended` | `WorkItemService.extend()` |
| `io.casehub.work.workitem.label_added` | `WorkItemService.addLabel()` |
| `io.casehub.work.workitem.label_removed` | `WorkItemService.removeLabel()` |
| `io.casehub.work.workitem.claim_expired` | `ExpiryLifecycleService.checkClaimDeadlines()`, `processClaimDeadline()` |
| `io.casehub.work.workitem.sla_reassigned` | `ExpiryLifecycleService.executeEscalateTo()` |
| `io.casehub.work.workitem.sla_extended` | `ExpiryLifecycleService.executeExtend()` |

**Group lifecycle — 3 types:**

| CloudEvent `type` | Source |
|---|---|
| `io.casehub.work.group.in_progress` | `MultiInstanceGroupPolicy.fireEvent()` |
| `io.casehub.work.group.completed` | `MultiInstanceGroupPolicy.fireEvent()` |
| `io.casehub.work.group.rejected` | `MultiInstanceGroupPolicy.fireEvent()` |

### Dependencies

No new Maven dependencies. `cloudevents-core` 4.0.1 is already on the classpath transitively via `casehub-platform-api` → `casehub-work-api` → `casehub-work` (runtime). Note: the canonical pattern garden entry (GE-20260621-629712) was verified against `cloudevents-core` 4.1.2; the API surface used by the adapter is stable across both versions.

## Change 4: Platform evaluation issue

File on `casehubio/parent`: **"evaluate: dual-channel CDI event firing as platform standard"**

Content:
- casehub-work normalized to dual-channel (`fire()` + `fireAsync()`) in work#273 via a `WorkItemLifecycleEmitter` bean that structurally enforces both calls
- All other repos currently fire async-only — this works because they have no sync observers
- casehub-work's sync observers exist because: `FilterEvaluationObserver` needs queue membership updates atomic with the WorkItem mutation; `LocalWorkItemEventBroadcaster` needs ordered SSE delivery; `WorkItemMetrics` needs counters within transaction boundary; four `AFTER_SUCCESS` observers need post-commit-but-same-thread delivery
- The evaluation question for each repo: "does any current or foreseeable observer need to run inside the producer's transaction?" — look for observers that update derived views, aggregate state, need ordering guarantees, or use `@Observes(during = TransactionPhase.AFTER_SUCCESS)`
- Firing both costs nothing when a channel has no observers — CDI no-ops the empty channel
- This is an evaluation, not an adoption mandate

## Testing

### Emitter unit tests (pure JUnit 5)

- `WorkItemLifecycleEmitter.emit()` calls both `fire()` and `fireAsync()` on the delegate
- `WorkItemGroupLifecycleEmitter.emit()` calls both `fire()` and `fireAsync()` on the delegate

### Adapter unit tests (pure JUnit 5, no container)

- `onWorkItemLifecycle` builds correct CloudEvent fields from a `WorkItemLifecycleEvent` (specversion, id, type, source, subject, time, datacontenttype, data)
- `onGroupLifecycle` builds correct CloudEvent fields from a `WorkItemGroupLifecycleEvent`
- tenancyId null → extension omitted (not serialised as literal `"null"`)
- tenancyId present → extension set
- Serialisation failure → WARN logged, CloudEvent fired with empty data
- CloudEvent type derivation: WorkItem uses `event.type()`, Group uses `groupStatus().name().toLowerCase()`
- CloudEvent `time` uses `event.occurredAt()`, not adapter processing time

### Firing normalization tests

- Verify `@ObservesAsync` observer receives events that were previously fire()-only (CREATED, ASSIGNED, STARTED, DELEGATED, DELEGATION_ACCEPTED, DELEGATION_DECLINED, RELEASED, SUSPENDED, RESUMED, PROGRESS_UPDATE, DEADLINE_EXTENDED, LABEL_ADDED, LABEL_REMOVED)
- Verify `QueueDashboard` (`@ObservesAsync`) now receives non-terminal events

### Integration test

- End-to-end: create → assign → complete a WorkItem, verify `@ObservesAsync CloudEvent` observer receives all three events with correct type, source, subject, tenancyId

## Platform doc updates

- **PLATFORM.md** Capability Ownership table: add WorkItem → CloudEvent adapter row
- **ARC42STORIES.MD**: update with adapter delivery
