# quarkus-work / quarkus-work Separation — Design Spec
**Date:** 2026-04-22
**Status:** Approved for implementation

---

## Context and Motivation

`quarkus-work-api` was created as a shared SPI layer so CaseHub could depend on routing
contracts without pulling in the full WorkItems stack. That worked for types. The insight driving
this design is that routing *implementations* (`LeastLoadedStrategy`, `WorkAssignmentOrchestrator`)
and the reactive filter engine are also generic — they belong below the WorkItems inbox layer, not
inside it.

There is a secondary tension: CaseHub has a `TaskBroker` concept for routing work to workers.
`quarkus-work-core` resolves this by providing `WorkBroker` — a generic broker that CaseHub can
depend on directly. `Work` becomes the base concept; `WorkItems` (human inbox) and CaseHub Tasks
are specialisations.

**No existing installs.** Renaming artifacts and packages is unconstrained.

---

## Module Structure

Four modules after the refactor:

```
quarkus-work-api        (renamed from quarkus-work-api)
  groupId: io.quarkiverse.work
  Pure Java, zero dependencies.
  All SPI contracts — the shared language between systems.

quarkus-work-core       (new)
  groupId: io.quarkiverse.work
  Jandex-indexed library module (Quarkus CDI + Panache + REST deps, no deployment module).
  All generic implementations: WorkBroker, built-in strategies, filter engine.
  No WorkItem entity, no human-inbox specifics.

quarkus-work       (unchanged extension)
  groupId: io.quarkiverse.work
  WorkItem entity, lifecycle service, REST API, labels, queues, audit.
  Implements the SPIs defined in quarkus-work-api.
  Thin human-inbox layer on top of quarkus-work-core.

quarkus-work-filter-registry  →  deleted (absorbed into quarkus-work-core)
quarkus-work-api              →  deleted (renamed to quarkus-work-api)
```

Dependency graph (acyclic):

```
quarkus-work-api        ← pure Java, zero deps
       ↑                           ↑
quarkus-work-core           casehub-engine
  (Jandex library)            (work-api dep only, or work-core)
       ↑                           ↑
quarkus-work         quarkus-work-casehub (future)
  (extension)
       ↑
quarkus-work-ai, -ledger, -queues, -issue-tracker, -persistence-mongodb
  (unchanged dependencies on quarkus-work)
```

---

## `quarkus-work-api` Contents

Package root: `io.quarkiverse.work.api`

### Existing types (moved verbatim, package renamed)

| Type | Notes |
|---|---|
| `WorkerCandidate` | record: id, capabilities, activeWorkItemCount |
| `SelectionContext` | record: category, priority, requiredCapabilities, candidateGroups, candidateUsers |
| `AssignmentDecision` | record + factories: noChange(), assignTo(), narrowCandidates() |
| `AssignmentTrigger` | enum: CREATED, RELEASED, DELEGATED |
| `WorkerSelectionStrategy` | interface: select(SelectionContext, List\<WorkerCandidate\>) + triggers() |
| `WorkerRegistry` | interface: resolveGroup(String) → List\<WorkerCandidate\> |

### New types

**`WorkEventType` enum** — canonical event vocabulary shared across all systems:
```
CREATED, ASSIGNED, STARTED, COMPLETED, REJECTED,
DELEGATED, RELEASED, SUSPENDED, RESUMED, CANCELLED, EXPIRED, ESCALATED
```

**`WorkLifecycleEvent` abstract class** — the generic event that the filter engine and any
cross-system observer can listen to:
```java
public abstract class WorkLifecycleEvent {
    public abstract WorkEventType eventType();
    public abstract Map<String, Object> context(); // field map for JEXL condition evaluation
    public abstract Object source();               // underlying work unit; FilterAction impls downcast
}
```
CDI observers declared as `@Observes WorkLifecycleEvent` receive any subtype — including
`WorkItemLifecycleEvent` from quarkus-work, or a future CaseHub event type.

**`WorkloadProvider` interface** — data-access SPI for populating candidate workload before routing:
```java
public interface WorkloadProvider {
    int getActiveWorkCount(String workerId);
}
```
`WorkBroker` calls this to populate `WorkerCandidate.activeWorkItemCount`. Each system provides
its own implementation (`JpaWorkloadProvider` in quarkus-work, CaseHub's own impl).

**`EscalationPolicy` interface** — moved from quarkus-work runtime, genericised:
```java
public interface EscalationPolicy {
    void escalate(WorkLifecycleEvent event);
}
```
Implementations stay in quarkus-work and downcast `event.source()` to `WorkItemEntity`.

---

## `quarkus-work-core` Contents

Package root: `io.quarkiverse.work.core`

Jandex-indexed library module. Has Quarkus CDI, Panache, and REST dependencies. No deployment
module — the consuming extension (quarkus-work, CaseHub) provides the CDI container.

### Assignment routing

**`WorkBroker`** (`@ApplicationScoped`) — the generic work assignment orchestrator. Replaces/
generalises CaseHub's `TaskBroker` concept. Extracted from `WorkItemAssignmentService`:

```
WorkBroker.assign(SelectionContext, AssignmentTrigger, List<WorkerCandidate>):
  1. Resolve active strategy (CDI @Alternative present → use it; else read config)
  2. strategy.triggers() doesn't contain trigger → return noChange()
  3. Filter candidates by SelectionContext.requiredCapabilities
  4. strategy.select(context, filtered candidates) → AssignmentDecision
  5. Return AssignmentDecision
```

`WorkBroker` never touches any work-unit entity. Callers are responsible for:
- Building `SelectionContext` from their domain object
- Resolving candidates via `WorkerRegistry` and populating `activeWorkItemCount` via `WorkloadProvider`
- Applying the returned `AssignmentDecision` back to their domain object

**`LeastLoadedStrategy`** — moved verbatim from quarkus-work runtime. No changes (already
operates only on `SelectionContext + List<WorkerCandidate>`).

**`ClaimFirstStrategy`** — moved verbatim. Returns `noChange()`.

**`NoOpWorkerRegistry`** — moved verbatim. Returns `List.of()` for all groups.

### Filter engine

All content from `quarkus-work-filter-registry`, with one change:
`FilterRegistryEngine` changes its observer from `@Observes WorkItemLifecycleEvent` to
`@Observes WorkLifecycleEvent`. All other code moves verbatim.

| Type | Notes |
|---|---|
| `FilterAction` SPI | Moved from filter-registry |
| `FilterDefinition` | Moved from filter-registry |
| `FilterEvent` | Moved from filter-registry |
| `ActionDescriptor` | Moved from filter-registry |
| `FilterRegistryEngine` | Moved + observer type changed to WorkLifecycleEvent |
| `JexlConditionEvaluator` | Moved; `toMap(WorkItem)` method removed (moved to `WorkItemContextBuilder` in quarkus-work to avoid circular dep) |
| `PermanentFilterRegistry` | Moved verbatim |
| `DynamicFilterRegistry` | Moved verbatim |
| `FilterRule` entity | Moved verbatim |
| `FilterRuleResource` | Moved verbatim |

Flyway migration `V3001__filter_rules.sql` moves from `quarkus-work-filter-registry` to
`quarkus-work-core` resources. No delta migration needed (no installs).

---

## `quarkus-work` After the Split

### New types

**`WorkItemLifecycleEvent extends WorkLifecycleEvent`** — concrete event fired by `WorkItemService`.
Implements the three abstract methods:
- `eventType()` — maps audit event string to `WorkEventType`
- `context()` — calls `WorkItemContextBuilder.toMap(workItem)` and returns the result
- `source()` — returns the `WorkItemEntity` entity

**`WorkItemContextBuilder`** — new utility class in `quarkus-work`. Has the static
`toMap(WorkItem)` method currently on `JexlConditionEvaluator` in filter-registry. This avoids a
circular dependency: `JexlConditionEvaluator` in `quarkus-work-core` cannot reference `WorkItemEntity`
(which lives in `quarkus-work`), so the map-building logic stays in `quarkus-work`.
The drift-protection test (`toMap_containsAllPublicNonStaticWorkItemFields`) moves here too.

**`JpaWorkloadProvider implements WorkloadProvider`** (`@ApplicationScoped`) — counts active
(`ASSIGNED | IN_PROGRESS | SUSPENDED`) `WorkItemEntity` entities from the JPA store for a given worker ID.

### Modified types

**`WorkItemAssignmentService`** — retains the WorkItem-specific orchestration wrapper:
```
assign(WorkItem, AssignmentTrigger):
  1. Build SelectionContext from workItem fields
  2. Resolve candidateUsers → List<WorkerCandidate>
  3. For each group in candidateGroups: WorkerRegistry.resolveGroup()
  4. Populate activeWorkItemCount via JpaWorkloadProvider
  5. WorkBroker.assign(context, trigger, candidates) → AssignmentDecision
  6. Apply non-null AssignmentDecision fields back to workItem
```

**`EscalationPolicyProducer`** — unchanged; produces `EscalationPolicy` impls that now implement
the interface from `quarkus-work-api`.

**`NotifyEscalationPolicy`, `AutoRejectEscalationPolicy`, `ReassignEscalationPolicy`** — implement
`EscalationPolicy.escalate(WorkLifecycleEvent)` and downcast `event.source()` to `WorkItemEntity`.

### Filter actions (stay in quarkus-work)

`ApplyLabelAction`, `OverrideCandidateGroupsAction`, `SetPriorityAction` — implement
`FilterAction` from `quarkus-work-core`, downcast `event.source()` to `WorkItemEntity`.

### Dependency changes

| Change | Direction |
|---|---|
| `quarkus-work-api` dependency | removed |
| `quarkus-work-filter-registry` dependency | removed |
| `quarkus-work-core` dependency | added (compile) |

---

## Impact on Dependent Modules

| Module | Change |
|---|---|
| `quarkus-work-ai` | Import `FilterDefinition` from `io.quarkiverse.work.core.filter` instead of `io.quarkiverse.work.filterregistry.spi`. One import line. |
| `quarkus-work-ledger` | No change — observes `WorkItemLifecycleEvent` (concrete type), which still exists in quarkus-work. |
| `quarkus-work-queues` | No change. |
| `quarkus-work-persistence-mongodb` | No change. |
| `work-flow` | No change. |
| `quarkus-work-issue-tracker` | No change. |
| All example/dashboard modules | No change. |

---

## CaseHub Integration Path

After this ships, CaseHub can:
1. Depend on `quarkus-work-core` (gets `WorkBroker` + filter engine)
2. Implement `WorkloadProvider` against its own task store
3. Implement `WorkerRegistry` against its own worker directory
4. Extend `WorkLifecycleEvent` for its own task lifecycle events
5. Drop its own `TaskBroker` implementation and delegate to `WorkBroker`

The `quarkus-work-casehub` adapter (blocked, future) bridges remaining gaps.

---

## Out of Scope

- Renaming the repo or parent artifact ID (`quarkus-work-parent`) — deferred until CaseHub alignment is stable
- `RoundRobinStrategy` — remains deferred (stateful cursor, issue #117)
- Semantic embedding SPI — future Epic #100 feature; will land in `quarkus-work-api` when designed
- `quarkus-work-casehub` adapter — blocked on CaseHub stability
