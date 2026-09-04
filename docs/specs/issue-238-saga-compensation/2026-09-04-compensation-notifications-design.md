# Design: Compensation Notifications (#389)

**Issue:** casehubio/work#389
**Parent:** casehubio/work#238 (saga compensation)
**Date:** 2026-09-04
**Status:** Draft

---

## 1. Problem

The saga compensation design (§10 of the parent spec) calls for external notification of compensation events via Email/Slack/Teams/Webhook. No notification bridge exists for compensation events — neither work-level nor case-level compensation events reach the platform subscription engine.

---

## 2. Goals

1. WorkItem compensation events (`COMPENSATION_STARTED`, `COMPENSATION_COMPLETED`) flow through the platform subscription engine for external delivery
2. Case compensation events (`COMPENSATING`, `COMPENSATED`, `COMPENSATION_FAULTED`) flow through the platform subscription engine for external delivery
3. Default system subscriptions exist for all 5 compensation event types so notifications work out-of-box
4. No changes to `casehub-connectors` — delivery is already generic

## 3. Non-Goals

- Custom email/Slack templates for compensation — subscription templates are sufficient
- Engine-only deployments without casehub-work — compensation notifications require the engine-adapter
- Real-time push notifications (SSE) for compensation — WorkItemLifecycleEvent SSE already covers work events; case-level SSE is a separate concern

---

## 4. Existing Infrastructure (Exploration Findings)

### 4.1 WorkItem Compensation Events — Already Flowing

`WorkItemSubscriptionBridge` (work/runtime) observes ALL `WorkItemLifecycleEvent` CDI events at `@Observes(during = TransactionPhase.AFTER_SUCCESS)` and pushes them to the notification DataSource via `DataSourceRegistry.resolveSource(NOTIFICATION_DATASOURCE_PATH, PLATFORM_TENANT_ID)`.

`WorkItemLifecycleEvent` already implements `SubscribableEvent`. Its `type()` method returns CloudEvents-style strings like `io.casehub.work.workitem.compensation_started`.

**Gap:** `COMPENSATION_STARTED` and `COMPENSATION_COMPLETED` are missing from `EMITTED_EVENT_TYPES` — the set used for `EventTypeRegistry` registration. Events flow but the subscription engine doesn't know these event types exist for matching.

### 4.2 Case Compensation Events — No Bridge

`CaseStatusChangedHandler` (engine/runtime) handles all case status transitions. For compensation states it:
- Maps `COMPENSATING` → `CaseHubEventType.COMPENSATION_STARTED`
- Maps `COMPENSATED` → `CaseHubEventType.COMPENSATION_COMPLETED`
- Maps `COMPENSATION_FAULTED` → `CaseHubEventType.COMPENSATION_FAULTED`
- Fires `CaseLifecycleEvent` via CDI `fireAsync()` with `commandType` ("CompensateCase", "CompensationComplete", "CompensationFaulted") and `eventType` ("CaseCompensating", "CaseCompensated", "CaseCompensationFaulted")

`CaseLifecycleEvent` does NOT implement `SubscribableEvent`. No module observes it for notification purposes.

### 4.3 Connectors — Generic Delivery

`ConnectorNotificationDeliverer.deliver()` takes `NotificationInput` and sends via `Connector.send(ConnectorMessage(...))`. Title, body, category, severity all come from the subscription template. No event-type-specific logic. Once events reach the subscription engine, connectors deliver them automatically.

### 4.4 Notification Pipeline Pattern

The established pattern (from `casehub-qhorus/notification-bridge`):

1. **Event POJO** — record implementing `SubscribableEvent` with `type()` and `tenancyId()`
2. **Event notifier** — CDI observer that catches domain events, constructs the event POJO, pushes to `DataSourceRegistry` at `NOTIFICATION_DATASOURCE_PATH`
3. **Subscription bootstrap** — `@Observes StartupEvent` handler that registers default system subscriptions via `SubscriptionStore.store(SubscriptionInput)`

---

## 5. Changes

### 5.1 WorkItemSubscriptionBridge — Event Type Registration (work/runtime)

Add `COMPENSATION_STARTED` and `COMPENSATION_COMPLETED` to `EMITTED_EVENT_TYPES`:

```java
private static final Set<WorkEventType> EMITTED_EVENT_TYPES = Set.of(
    // ... existing 24 entries ...
    WorkEventType.COMPENSATION_STARTED,
    WorkEventType.COMPENSATION_COMPLETED
);
```

This registers the event types with `EventTypeRegistry` so the subscription engine can match against them. The events themselves already flow through `onWorkItemEvent()`.

### 5.2 CaseCompensationEvent — New SubscribableEvent (engine-adapter)

```java
package io.casehub.work.engine;

public record CaseCompensationEvent(
    Kind kind,
    String tenancyId,
    UUID caseId,
    String caseDefinitionName,
    String caseStatus,
    String actorId
) implements SubscribableEvent {

    public enum Kind { STARTED, COMPLETED, FAULTED }

    private static final String TYPE_PREFIX = "io.casehub.engine.case.compensation.";

    @Override
    public String type() {
        return TYPE_PREFIX + kind.name().toLowerCase();
    }
}
```

Fields chosen to support notification templates:
- `caseId` — for entity references and action URLs
- `caseDefinitionName` — for human-readable context ("Clinical Trial IRB case")
- `caseStatus` — the current CaseStatus name
- `actorId` — who triggered compensation (for EVENT_FIELD target resolution)

### 5.3 CaseCompensationNotifier — CDI Observer (engine-adapter)

```java
package io.casehub.work.engine;

@ApplicationScoped
public class CaseCompensationNotifier {

    private static final Set<String> COMPENSATION_EVENT_TYPES = Set.of(
        "CaseCompensating", "CaseCompensated", "CaseCompensationFaulted"
    );

    @Inject Instance<DataSourceRegistry> dataSourceRegistryInstance;

    void onCaseLifecycle(@ObservesAsync CaseLifecycleEvent event) {
        if (!COMPENSATION_EVENT_TYPES.contains(event.eventType())) {
            return;
        }
        if (dataSourceRegistryInstance.isUnsatisfied()) {
            return;
        }
        Kind kind = switch (event.eventType()) {
            case "CaseCompensating" -> Kind.STARTED;
            case "CaseCompensated" -> Kind.COMPLETED;
            case "CaseCompensationFaulted" -> Kind.FAULTED;
            default -> null;
        };
        fire(new CaseCompensationEvent(
            kind, event.tenancyId(), event.caseId(),
            event.caseDefinitionName(), event.caseStatus(),
            event.actorId()));
    }

    private void fire(CaseCompensationEvent event) {
        // Same pattern as CommitmentEventNotifier:
        // resolveSource(NOTIFICATION_DATASOURCE_PATH, PLATFORM_TENANT_ID)
        // → source.add(event)
    }
}
```

### 5.4 CompensationSubscriptionBootstrap — Default Subscriptions (engine-adapter)

Registers 5 default system subscriptions at startup:

| Event Type | Target | Title Pattern | Severity |
|---|---|---|---|
| `io.casehub.work.workitem.compensation_started` | EVENT_FIELD: `assigneeId` | "WorkItem compensation started" | WARNING |
| `io.casehub.work.workitem.compensation_completed` | EVENT_FIELD: `assigneeId` | "WorkItem compensation completed" | INFO |
| `io.casehub.engine.case.compensation.started` | EVENT_FIELD: `actorId` | "Case compensation started: {caseDefinitionName}" | URGENT |
| `io.casehub.engine.case.compensation.completed` | EVENT_FIELD: `actorId` | "Case compensation completed: {caseDefinitionName}" | INFO |
| `io.casehub.engine.case.compensation.faulted` | EVENT_FIELD: `actorId` | "Case compensation FAULTED: {caseDefinitionName}" | URGENT |

Pattern follows `QhorusSubscriptionBootstrap`:
- Idempotent: checks existing subscriptions before registering
- `SubscriptionScope.SYSTEM` — platform-owned, not user-configurable
- `OWNER_ID = "system:compensation"`

Case compensation events target `actorId` (who triggered compensation — typically an operator or system). Work compensation events target `assigneeId` (who was assigned to the compensated WorkItem — they need to know their completed work is being reversed).

### 5.5 EventTypeDescriptor Registration (engine-adapter)

Register the 3 case compensation event types with `EventTypeRegistry` at startup (alongside subscription bootstrap):

```java
registry.register(new EventTypeDescriptor(
    "io.casehub.engine.case.compensation.started",
    "Case compensation started",
    null,
    caseCompensationEventFields()));
```

Fields: `caseId`, `caseDefinitionName`, `caseStatus`, `actorId`.

---

## 6. Event Flow

```
Engine: CaseStatus → COMPENSATING
    │
    ▼
CaseStatusChangedHandler
    │ fires CaseLifecycleEvent(eventType="CaseCompensating") via CDI async
    │
    ├──► CaseCompensationNotifier (@ObservesAsync CaseLifecycleEvent)
    │        │ filters: eventType ∈ {CaseCompensating, CaseCompensated, CaseCompensationFaulted}
    │        │ constructs CaseCompensationEvent
    │        ▼
    │    DataSourceRegistry.resolveSource(NOTIFICATION_DATASOURCE_PATH)
    │        │
    │        ▼
    │    SubscriptionEngine (pattern matching)
    │        │ matches default subscription "io.casehub.engine.case.compensation.started"
    │        ▼
    │    SubscriptionMatched → NotificationDispatcher → ConnectorNotificationDeliverer
    │        │
    │        ▼
    │    Email / Slack / Teams / Webhook
    │
    └──► [existing: WorkItemLifecycleAdapter, PlanItemCompletionApplier, etc.]


Work: compensating WorkItem created → COMPENSATION_STARTED event
    │
    ▼
WorkItemSubscriptionBridge (@Observes WorkItemLifecycleEvent)
    │ already forwards ALL events to DataSource
    ▼
DataSourceRegistry → SubscriptionEngine → NotificationDispatcher → Connectors
```

---

## 7. Module Placement Rationale

**engine-adapter** hosts all new code (except the 2-line fix in WorkItemSubscriptionBridge) because:

1. **Dependencies satisfied:** Already depends on `casehub-engine` (CaseLifecycleEvent), `casehub-platform` (DataSourceRegistry, SubscriptionStore), and `casehub-work-api` (SubscribableEvent transitively)
2. **Semantic fit:** The adapter bridges engine↔work; notification bridging is the same pattern — translating engine events into platform-consumable form
3. **No new module:** Avoids build complexity, integration-test wiring, and deployment configuration
4. **Cohesive registration:** All compensation subscriptions (case + work) are registered together since compensation is an engine-driven feature

**Connectors requires no changes** — `ConnectorNotificationDeliverer` is fully generic.

---

## 8. Testing Strategy

### 8.1 Unit Tests

**CaseCompensationNotifierTest:**
- Observes `CaseLifecycleEvent` with `eventType="CaseCompensating"` → fires `CaseCompensationEvent(Kind.STARTED)`
- Observes `CaseLifecycleEvent` with `eventType="CaseCompensated"` → fires `CaseCompensationEvent(Kind.COMPLETED)`
- Observes `CaseLifecycleEvent` with `eventType="CaseCompensationFaulted"` → fires `CaseCompensationEvent(Kind.FAULTED)`
- Ignores non-compensation eventTypes ("CaseCompleted", "CaseFaulted")
- Handles missing DataSourceRegistry gracefully (Instance.isUnsatisfied)
- Handles DataSource resolution failure (resolveSource returns empty)

**CaseCompensationEventTest:**
- `type()` returns correct event type string for each Kind
- `tenancyId()` returns the tenancy from construction

**CompensationSubscriptionBootstrapTest:**
- Registers all 5 subscriptions on startup
- Idempotent — skips already-registered subscriptions
- Handles SubscriptionStore failure gracefully

### 8.2 WorkItemSubscriptionBridge Update

**WorkItemSubscriptionBridgeTest (existing):**
- Verify `EMITTED_EVENT_TYPES` contains `COMPENSATION_STARTED` and `COMPENSATION_COMPLETED`
- Verify event type registration includes compensation types

---

## 9. Implementation Sequence

1. **CaseCompensationEvent** — new record, no dependencies
2. **CaseCompensationNotifier** — depends on CaseCompensationEvent
3. **CompensationSubscriptionBootstrap** — depends on CaseCompensationEvent (for event type strings)
4. **WorkItemSubscriptionBridge update** — independent, 2-line change
5. **Tests** — TDD: tests written before or alongside each component

---

## 10. References

- `WorkItemSubscriptionBridge.java` (work/runtime) — existing event bridge, gap in EMITTED_EVENT_TYPES
- `WorkItemLifecycleEvent.java` (work/api) — already implements SubscribableEvent
- `WorkEventType.java` (work/api) — COMPENSATION_STARTED/COMPLETED already defined
- `CaseStatusChangedHandler.java` (engine/runtime) — fires CaseLifecycleEvent for compensation states
- `CaseLifecycleEvent.java` (engine-common) — CDI event carrying case lifecycle transitions
- `CommitmentEventNotifier.java` (qhorus/notification-bridge) — pattern for DataSource event firing
- `QhorusSubscriptionBootstrap.java` (qhorus/notification-bridge) — pattern for subscription registration
- `QhorusObligationEvent.java` (qhorus/notification-bridge) — pattern for SubscribableEvent record
- `SubscribableEvent.java` (platform-api) — interface contract for notification events
- `notifications.md` (parent/docs/platform) — notification pipeline architecture
- `boundary-rules.md` (parent/docs/platform) — "Do not duplicate notification infrastructure"
- Decisions D16-D19 in `specs/issue-238-saga-compensation/decisions.md`
