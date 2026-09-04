# Distributed HumanTask — CloudEvent Emitter and Lifecycle Consumer

**Issue:** casehubio/engine#972
**Date:** 2026-08-24
**Status:** Draft

## Problem

The engine dispatches human tasks and action gates via in-JVM calls that require `casehub-work-engine-adapter` to be co-located in the same Quarkus application. Two SPIs mediate this:

- **HumanTask outbound:** `HumanTaskScheduler` SPI (engine-common) — the co-located adapter implements it, creating WorkItems via `WorkItemCreator.create()`
- **ActionGate outbound:** `ActionGateScheduleRequest` on the Vert.x event bus — `ActionGateWorkItemHandler` consumes it via `@ConsumeEvent`
- **Inbound (both):** `WorkItemLifecycleAdapter` observes `@ObservesAsync WorkItemEvent` (CDI), parses `callerRef`, and routes terminal events to `PlanItemCompletionApplier` (PlanItem path) or `ActionGateCompletionApplier` (gate path)

In a distributed deployment — engine and work as separate services — none of these paths work. The work-engine-adapter is absent from the engine's classpath, and CDI/Vert.x events don't cross JVM boundaries.

casehubio/work#299 shipped the work-side inbound CloudEvent consumer (`io.casehub.work.workitem.create`). casehubio/work#273 shipped the outbound lifecycle CloudEvent adapter (`WorkCloudEventAdapter`). This issue completes the bridge from the engine side.

## Design

### Module: `casehub-engine-work-cloudevent`

New optional engine module. Directory: `work-cloudevent/` (per maven-submodule-folder-naming protocol). Activated by adding to consumer classpath. Mutually exclusive with `casehub-work-engine-adapter`.

**Compile dependencies:**
- `casehub-engine-common` (SPIs: `HumanTaskScheduler`, `ActionGateScheduler`, `PlanItemCompletionApplier`, `GateCompletionApplier`, `CallerRefParser`, `EventBusAddresses`)
- `casehub-engine-api` (types: `HumanTaskTarget`, `CaseDefinition`, `Binding`, `TaskStatus`)
- `casehub-engine-planning` (types: `CasePlanModel`, `PlanItem`)
- `casehub-work-api` (types: `WorkCloudEventTypes`, `WorkItemStatus`, `WorkItemRef`)
- `io.cloudevents:cloudevents-api` (CloudEvent SDK)
- `quarkus-arc`, `quarkus-vertx`

**Test dependencies:** full engine stack with `casehub-persistence-memory`.

### Part 1 — Outbound: HumanTask CloudEvent Emitter

`CloudEventHumanTaskScheduler` — `@ApplicationScoped`, implements `HumanTaskScheduler`.

On `schedule(HumanTaskScheduleRequest)`:

1. **PlanItem resolution:** `BlackboardRegistry.get(caseId)` → `CasePlanModel.getPlanItemByBindingName(bindingName)` → validate status is `DISPATCHING`
2. **CallerRef construction:** `"case:" + caseId + "/pi:" + planItemId`
3. **Field mapping:** Map `HumanTaskScheduleRequest` fields to CloudEvent data JSON:

   | Request field | CloudEvent data field |
   |---|---|
   | `target.title()` / `resolvedTitle` | `title` |
   | `resolvedCandidateGroups` | `candidateGroups` (CSV) |
   | `resolvedCandidateUsers` | `candidateUsers` (CSV) |
   | `inputData` | `payload` (JSON string) |
   | `resolvedScope` / `target.scope()` | `scope` |
   | `target.templateRef()` | `templateId` |
   | `payloadTypeName` | `payloadTypeName` |
   | `resolutionTypeName` | `resolutionTypeName` |
   | `candidateScores` | `candidateScores` (pre-serialized JSON) |
   | `experiences` | `routingExperiences` (pre-serialized JSON) |
   | computed callerRef | `callerRef` |
   | earliest of `expiresAtDeadline`, `caseBudgetDeadline`, `target.expiresIn()` | `expiresAt` (ISO-8601) |
   | `target.claimDeadlineHours()` | `claimDeadlineBusinessHours` |
   | `target.outcomes()` | `permittedOutcomes` (object array) |

4. **CloudEvent envelope:** type=`WorkCloudEventTypes.CREATE`, source=`"/engine/cases/" + caseId`, id=UUID, `tenancyid` extension=`tenancyId`, datacontenttype=`application/json`
5. **Template vs inline:** If `target.isTemplateMode()` → include `templateId` in data. If inline → include `title` and all fields directly. Same branching as the co-located handler.
6. **Emit:** `cloudEventEmitter.fireAsync(cloudEvent)`
7. **PlanItem persistence:** `planItemStore.save(... DELEGATED ...)` and `item.markDelegated()`

**Error handling:** If PlanItem lookup or validation fails, `item.revertDispatching()` and return (same as co-located handler). CloudEvent emission failure is fire-and-forget at the CDI level — transport-level retry is the connector's responsibility.

### Shared Completion Infrastructure (engine-common)

Per review finding R1-02: completion logic is shared between co-located and distributed modes. Rather than duplicating ~481 lines of orchestration (PlanItem lookup, resolution validation, JQ output mapping, conflict resolution, CDI events, context-changed publishing), the engine provides shared appliers that both modes delegate to.

**`PlanItemCompletionApplier`** — `@ApplicationScoped` in `engine-common/spi/` (or `engine-planning/`). Encapsulates the full PlanItem completion flow:
- `apply(UUID caseId, String planItemId, WorkItemStatus status, String resolution, String resolutionTypeName)` → lookup PlanItem via `BlackboardRegistry`, validate resolution via `BridgeResolver`, apply status transition, evaluate output mapping (JQ), apply via `ConflictResolver`, fire `PlanItemStateChangedEvent`/`PlanItemObsoleteEvent`, publish `CONTEXT_CHANGED`
- `applySuspend(UUID caseId, String planItemId)` / `applyResume(UUID caseId, String planItemId)`

**`GateCompletionApplier`** — `@ApplicationScoped` in `engine-common/spi/`. Encapsulates ActionGate lifecycle routing:
- `apply(UUID caseId, long gateId, WorkItemStatus status, String resolution, String tenancyId)` → map status to event bus address (`ACTION_GATE_APPROVED` / `_REJECTED` / `_EXPIRED`), build event, publish on Vert.x event bus

**`CallerRefParser`** — utility in `engine-common/spi/`. Parses `case:{caseId}/pi:{planItemId}` and `case:{caseId}/gate:{gateId}` formats. Encoding methods for both patterns. Single implementation shared by all consumers.

These types use `WorkItemStatus` from `casehub-work-api` in their method signatures. `casehub-engine-common` gains a compile dependency on `casehub-work-api` (Tier 1, one-way — same direction as the existing `engine-api → casehub-worker-api` edge).

The co-located work-engine-adapter can adopt these shared appliers in a future work-repo PR, eliminating its own copies. Until then, both implementations coexist — the engine-side versions are authoritative.

### Part 2 — Outbound: ActionGate CloudEvent Emitter

**Prerequisite:** `ActionGateScheduler` SPI in `engine-common/spi/` (new, symmetric with `HumanTaskScheduler`).

```java
public interface ActionGateScheduler {
    void schedule(ActionGateScheduleRequest request);
}
```

`ActionGateScheduleRequest` — renamed and moved from `ActionGateScheduleRequest` (`engine-common/internal/event/` → `engine-common/spi/`). Same fields: `caseId`, `tenancyId`, `gateId`, `plannedAction`, `gateRequired`, `resolvedCandidateGroups`, `resolutionTypeName`. The old `ActionGateScheduleRequest` class is deleted — one type, no dead code.

**Engine runtime refactoring:** `WorkflowExecutionCompletedHandler.handleGate()` switches from `eventBus.publish(ACTION_GATE_SCHEDULE, event)` to `actionGateScheduler.get().schedule(request)` via `Instance<ActionGateScheduler>`. Same pattern as work#298's HumanTask refactoring. When `actionGateScheduler.isResolvable()` is false, logs a warning and returns (gate silently skipped — same behavior as HumanTask).

`CloudEventActionGateScheduler` — `@ApplicationScoped`, implements `ActionGateScheduler`.

On `schedule(ActionGateScheduleRequest)`:

1. **CallerRef construction:** `"case:" + caseId + "/gate:" + gateId`
2. **Field mapping:** title=`gateRequired.reason()`, candidateGroups=CSV of `resolvedCandidateGroups`, payload=JSON of `{description, actionType, reversible, context}`, expiresAt from `gateRequired.expiresIn()`, scope=`gateRequired.scope()`, resolutionTypeName, callerRef
3. **Quorum handling:** If `gateRequired.quorum()` is non-null, include quorum fields in a `multiInstance` data block. The work-side consumer (`WorkCloudEventInboundAdapter`) handles multi-instance creation from the same CloudEvent type.
4. **CloudEvent envelope:** Same as HumanTask — type=`WorkCloudEventTypes.CREATE`, source=`"/engine/cases/" + caseId + "/gates/" + gateId`
5. **Emit:** `cloudEventEmitter.fireAsync(cloudEvent)`

**Note on quorum:** The work-side `io.casehub.work.workitem.create` consumer currently handles single WorkItem creation. Multi-instance (M-of-N) creation via CloudEvent requires extending the work-side consumer or defining a separate CloudEvent type. If multi-instance CloudEvent creation is not yet supported on the work side, the gate emitter logs a warning and skips emission for quorum gates. Single-approver gates work immediately.

### Part 3 — Inbound: Lifecycle CloudEvent Consumer

`WorkItemLifecycleCloudEventConsumer` — `@ApplicationScoped`. Pure transport adapter — all completion logic delegates to the shared appliers.

```java
@ObservesAsync CloudEvent ce
```

**Behavior:**

1. **Type filter:** Match against `WorkCloudEventTypes` constants. Relevant types:
   - Terminal: `COMPLETED`, `REJECTED`, `FAULTED`, `CANCELLED`, `EXPIRED`, `ESCALATED`, `OBSOLETE`
   - Non-terminal: `SUSPENDED`, `RESUMED`
   - Ignore all others (`CREATED`, `ASSIGNED`, `STARTED`, etc.)

2. **Tenant context:** Extract `tenancyid` extension. Missing → log ERROR, return.

3. **Parse data:** Extract `callerRef` from CloudEvent data payload. Parse via `CallerRefParser`.

4. **Route by callerRef format:**

   **PlanItem path** (`case:{uuid}/pi:{planItemId}`):
   - Map CloudEvent type → `WorkItemStatus`
   - Extract `resolution` and `resolutionTypeName` from CloudEvent data
   - Delegate to `PlanItemCompletionApplier.apply(caseId, planItemId, status, resolution, resolutionTypeName)`
   - For SUSPENDED/RESUMED: delegate to `applySuspend()`/`applyResume()`

   **Gate path** (`case:{uuid}/gate:{gateId}`):
   - Map CloudEvent type → `WorkItemStatus`
   - Extract resolution data from CloudEvent data
   - Delegate to `GateCompletionApplier.apply(caseId, gateId, status, resolution, tenancyId)`

5. **Group lifecycle:** Observe `io.casehub.work.group.*` CloudEvents for M-of-N completion. Parse `callerRef` from data, route to PlanItem or gate path via the same shared appliers.

The consumer contains no completion logic itself — it is a CloudEvent-to-SPI adapter. The same shared appliers handle the co-located path (from CDI `WorkItemEvent` observer in the work-engine-adapter, once adopted).

### Mode Detection

No configuration property. CDI bean discovery determines the mode:

| Classpath | Mode | Behavior |
|---|---|---|
| `casehub-work-engine-adapter` present | Co-located | Adapter's beans implement SPIs, CDI observer handles lifecycle |
| `casehub-engine-work-cloudevent` present | Distributed | This module's beans implement SPIs, CloudEvent consumer handles lifecycle |
| Neither | No work integration | `Instance<>.isResolvable()` returns false, humanTask/gate bindings silently skipped |
| Both | Error | `@Observes @Priority(1) StartupEvent` throws `IllegalStateException`. Fail fast — don't let the deployment reach case execution with ambiguous routing. |

### CallerRef Encoding

The callerRef format is a shared convention between engine emitter and engine consumer:

- PlanItem: `case:{caseId}/pi:{planItemId}`
- Gate: `case:{caseId}/gate:{gateId}`

The encoding/parsing logic lives in `CallerRefParser` (engine-common/spi/) — the authoritative implementation. The work-engine-adapter has its own copies (`PlanItemRef`, `GateRef`, `CallerRef`), which it can migrate to the shared parser in a future work-repo PR.

### Error Handling

**Outbound (emitter):**

| Category | Action |
|---|---|
| PlanItem not found / not DISPATCHING | `revertDispatching()`, log WARN, return |
| Serialization failure | Log WARN, use null for affected field |
| CDI emission failure | Propagate — transport retry handles it |

**Inbound (consumer):**

| Category | Action |
|---|---|
| Missing `tenancyid` extension | Log ERROR, return |
| Unknown callerRef format | Log WARN, return |
| PlanItem/CaseInstance not found | Log WARN, return |
| Resolution validation failure | Write `workItemValidationFailed` signal, return |
| PlanItem already terminal | Log DEBUG, return (idempotent) |
| Output mapping failure | Log WARN, fire CONTEXT_CHANGED without output |

### Testing

**Unit tests:**

Outbound:
1. HumanTask inline → CloudEvent with correct data fields, PlanItem marked DELEGATED
2. HumanTask template → CloudEvent with `templateId` in data
3. HumanTask PlanItem not DISPATCHING → revert, no emission
4. ActionGate single-approver → CloudEvent with gate callerRef and payload
5. ActionGate quorum → warning log (if work-side multi-instance CloudEvent not supported)
6. Field mapping completeness for both paths

Inbound:
1. COMPLETED CloudEvent with PlanItem callerRef → PlanItem markCompleted, output mapping applied, CONTEXT_CHANGED fired
2. REJECTED → markRejected + PlanItemStateChangedEvent
3. FAULTED/EXPIRED/ESCALATED → markFaulted + PlanItemStateChangedEvent
4. SUSPENDED → markSuspended
5. RESUMED → markResumed (only from SUSPENDED)
6. Gate COMPLETED → ACTION_GATE_APPROVED published
7. Gate REJECTED → ACTION_GATE_REJECTED published
8. Gate EXPIRED → ACTION_GATE_EXPIRED published
9. Unknown callerRef → ignored
10. Missing tenancyid → ERROR logged, no processing
11. PlanItem already terminal → idempotent skip
12. Resolution validation failure → workItemValidationFailed signal

**Integration test:**

Round-trip test using CDI events (no external broker):
1. Define a CaseHub with a HumanTask binding
2. Start a case, trigger the binding
3. Assert: CloudEvent with type CREATE fired, callerRef in data, PlanItem DELEGATED
4. Simulate work-side response: fire CloudEvent with type COMPLETED, same callerRef, resolution data
5. Assert: PlanItem COMPLETED, output mapping applied, case context updated, CONTEXT_CHANGED fired
6. Same round-trip for ActionGate path

## Changes Summary

| Location | Change |
|---|---|
| `engine-common/spi/` | New: `ActionGateScheduler` interface, `PlanItemCompletionApplier`, `GateCompletionApplier`, `CallerRefParser` |
| `engine-common/internal/event/` | Rename+move: `ActionGateScheduleRequest` → `ActionGateScheduleRequest` in `spi/` (delete old class) |
| `engine-common/pom.xml` | New dependency: `casehub-work-api` (for `WorkItemStatus` in applier signatures) |
| `engine/runtime/` | Refactor: `WorkflowExecutionCompletedHandler.handleGate()` uses `Instance<ActionGateScheduler>` instead of event bus |
| `engine/runtime/` | New: `NoOpActionGateScheduler` (`@DefaultBean`, no-op, symmetric with existing pattern) |
| `work-cloudevent/` (new module) | New: `CloudEventHumanTaskScheduler`, `CloudEventActionGateScheduler`, `WorkItemLifecycleCloudEventConsumer` (pure transport adapter), startup conflict detector |
| Root `pom.xml` | Add `work-cloudevent` to `<modules>` |

## Not In Scope

- **Transport configuration** — selecting Kafka, AMQP, or HTTP as the CloudEvent wire protocol. That's a deployment concern, configured via Quarkus messaging connectors.
- **Work-engine-adapter updates** — the adapter implementing `HumanTaskScheduler` (work#298) and `ActionGateScheduler` (follow-up) are work-repo PRs.
- **Multi-instance gate via CloudEvent** — quorum gates require work-side multi-instance CloudEvent support. Single-approver gates work immediately; quorum gates log a warning and skip emission until the work side adds support. Tracked as a follow-up work-repo issue.
- **Transactional outbox** — CDI fire-and-forget emission has a stuck-DELEGATED risk if the event is lost between PlanItem persistence and CDI delivery. The outbox pattern (persist event in the same transaction, relay via poller/CDC) eliminates this gap. Deferred for pre-release — the failure mode requires manual intervention but is detectable via PlanItem status monitoring.
- **Repo build order resolution** — tracked in engine#974.
- **Work-engine-adapter adoption of shared appliers** — the co-located adapter can migrate to `PlanItemCompletionApplier`, `GateCompletionApplier`, and `CallerRefParser` from engine-common. Separate work-repo PR.

## References

- `common/src/main/java/io/casehub/engine/common/spi/HumanTaskScheduler.java:25` — existing SPI
- `common/src/main/java/io/casehub/engine/common/spi/HumanTaskScheduleRequest.java:26` — request record
- `common/src/main/java/io/casehub/engine/common/internal/event/ActionGateScheduleEvent.java:24` — current gate event
- `common/src/main/java/io/casehub/engine/common/internal/event/EventBusAddresses.java:55-104` — gate lifecycle addresses
- `runtime/src/main/java/io/casehub/engine/internal/engine/handler/CaseContextChangedEventHandler.java:685-815` — publishHumanTaskSchedule
- `runtime/src/main/java/io/casehub/engine/internal/engine/handler/WorkflowExecutionCompletedHandler.java:729` — handleGate
- `io.casehub.work.engine.HumanTaskScheduleHandler` (work-engine-adapter JAR) — co-located handler, field mapping reference
- `io.casehub.work.engine.PlanItemCompletionApplier` (work-engine-adapter JAR) — completion logic reference
- `io.casehub.work.engine.WorkItemLifecycleAdapter` (work-engine-adapter JAR) — lifecycle routing reference
- `io.casehub.work.engine.CallerRef` (work-engine-adapter JAR) — callerRef parsing reference
- `io.casehub.work.api.WorkCloudEventTypes` (work-api JAR) — CloudEvent type constants
- `engine/docs/specs/issue-299-cloudevent-humantask-bridge/2026-08-23-cloudevent-create-workitem-design.md` — work-side CREATE consumer spec
- `engine/docs/specs/2026-07-04-cloudevent-workitem-bridge-design.md` — work-side REQUESTED consumer spec
- `engine/docs/protocols/casehub/virtual-thread-handler-convention.md` — `@ConsumeEvent` handlers use `@RunOnVirtualThread + void`
- GE-20260521-a0f5a6 — HumanTask PlanItem pre-marking gotcha
- GE-20260718-9eb2c0 — WorkItem creation failure silently swallowed
- casehubio/engine#974 — repo build order resolution (filed during this design)
