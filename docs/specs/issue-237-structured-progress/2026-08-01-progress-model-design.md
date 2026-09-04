# Progress Model — Platform-Level Observation and Reporting

**Date:** 2026-08-01
**Status:** Draft (revision of 2026-07-17 spec)
**Related issues:** casehubio/work#237 (ideas capture), casehubio/engine#84 (milestone alignment), casehubio/blocks#49 (topic-aware conversation), casehubio/blocks#62 (progress overlay consumer)
**Deferred issues:** casehubio/work#307 (arbitrary JSON schema shape), casehubio/work#308 (rollback control), casehubio/work#309 (visualisation modes), casehubio/work#310 (progress event retention policy)

## Changes from 2026-07-17 Spec

1. Enriched `step` shape with DAG dependencies, optional flag, JQ conditions
2. Step-level REST convenience endpoints
3. Modules inside casehub-work instead of separate repo (`casehubio/progress`)
4. Simplified work integration (co-deployed)
5. Step DAG test cases

---

## Problem

Progress tracking in the CaseHub platform is fragmented:

- casehub-work has flat `percentComplete`/`statusNote` fields on WorkItem — no structure, no hierarchy, no schema
- casehub-engine has a rich Milestone model (expression-evaluated, lifecycle-managed, SLA-tracked) but no continuous progress tracking
- No shared progress primitive exists across work, engine, blocks, or qhorus
- work#237 captured aspirational ideas but mixed observation/reporting with control, over-specified tree structure, and assumed CMMN concepts the engine is moving away from

## Core Insight

Progress is a **choreography observer** — it records and reports what happened without controlling execution. Structural validation, retry decisions, failure handling, and orchestration all belong to the systems doing the work (engine, Flow, work spawning). The progress model faithfully represents what those systems are doing.

Milestones are complementary, not competing. Progress tracks continuous state. Milestones are binary waypoints. They compose — progress events feed milestone criteria evaluation.

## Design Principles

- **Orthogonal, regular, recursive** — same primitives at every level, complexity doesn't leak to simple use cases
- **Observation, not orchestration** — records and derives state; never initiates work, retries, or scheduling. Orchestrators (engine, Flow, work spawning) make execution decisions; the progress model faithfully represents what they did.
- **Shape conformance is structural integrity** — the progress model validates that state conforms to its declared shape. This is structural integrity, not control, because the validation is against the shape's own definition — not against external workflow policy. Two categories of validation, same principle:
  - **Data validation:** percentage in [0, 100], count `current <= total`, non-negative values. The value is nonsensical regardless of context.
  - **Definition conformance:** DAG dependency ordering (can't activate step B while prerequisite A is pending), cycle detection, at least one required step. The step definition declares that B depends on A — recording B as active with A pending means the state is incoherent with its own definition. The orchestrator decides *when* to start step B; the progress model validates that the start is legal per the definition the orchestrator itself declared. This is validation of declared invariants, not imposition of workflow policy.
- **Derived completion, not lifecycle control** — the step shape derives instance completion when all required steps are done (`isDone()` — COMPLETED or SKIPPED). This is a logical invariant of the shape definition, not an execution decision. But the progress model does NOT auto-fail instances when a step fails — failure handling and retry decisions belong to the orchestrator. This asymmetry is intentional: completion is deterministic (all required steps done = done), while step failure requires judgment (retry? skip? fail the instance?).
- **Typed union now, extensible later** — small set of built-in shapes; arbitrary JSON schema can be added as a future shape type (work#307)
- **Pay for complexity only when needed** — a flat percentage on a standalone WorkItem uses the same type as a 4-level fleet deployment tree
- **Hierarchy is always declared via parentProgressId** — orchestrators set it explicitly. For case-embedded progress, the engine constructs the progress tree by mirroring PlanItem containment into parentProgressId relationships. The progress module has no knowledge of PlanItem trees.

---

## 1. Core Types

### ProgressInstance

The atomic unit. Same shape at every level of any tree.

```java
public record ProgressInstance(
    UUID id,
    String tenancyId,          // multi-tenancy scoping — required, from request context
    String scopeType,          // "workitem", "planitem", "node", anything
    String scopeId,            // the anchor's ID
    UUID parentProgressId,     // null for root instances
    UUID rootProgressId,       // self for roots; inherited from parent chain on creation
    String shapeType,          // discriminator for the typed union
    JsonNode definition,       // shape-specific definition (e.g. step DAG); null for simple shapes
    JsonNode state,            // current state conforming to the shape
    ProgressStatus status,     // PENDING, ACTIVE, COMPLETED, FAILED
    String rollupStrategyId,   // null = no rollup (leaf), or NamedStrategy id
    Instant createdAt,
    Instant updatedAt
)

public enum ProgressStatus {
    PENDING, ACTIVE, COMPLETED, FAILED;

    public boolean isTerminal() {
        return false; // No progress status is truly terminal — reactivation is a valid lifecycle transition
    }

    public boolean isQuiescent() {
        return this == COMPLETED || this == FAILED;
    }

    public boolean isActive() {
        return this == ACTIVE;
    }
}
```

**LIFECYCLE.md registration:** `ProgressStatus` will be registered in LIFECYCLE.md before the first commit that uses it, per platform protocol Rule 4. Consumer code must use `isQuiescent()` / `isActive()` rather than enumerating states. `ProgressStatus` has **no terminal states** — `isTerminal()` returns false for all values because both COMPLETED and FAILED have valid outbound transitions (reactivation to ACTIVE). This distinguishes progress from WorkItem/PlanItem state machines where terminal is irreversible. `isQuiescent()` captures "not currently progressing" — the consumer-relevant check for progress instances. LIFECYCLE.md will be updated to document `isQuiescent()` as a standard method for state machines with reactivation semantics.

### Status Lifecycle

New instances start in `PENDING`. Valid transitions:

| From | To | Trigger |
|------|-----|---------|
| PENDING | ACTIVE | First state update (automatic) |
| PENDING | COMPLETED | Direct completion — pre-resolved state |
| PENDING | FAILED | Direct failure — known-broken before progress starts |
| ACTIVE | COMPLETED | Normal completion via `/complete` |
| ACTIVE | FAILED | Failure via `/fail` |
| COMPLETED | ACTIVE | Reactivation — retry scenario |
| FAILED | ACTIVE | Reactivation — retry scenario |

**Not allowed:**
- Any -> PENDING — PENDING means "no activity yet"; once left, cannot be re-entered
- COMPLETED <-> FAILED — quiescent-to-quiescent must go through ACTIVE (reactivate, then transition)

Invalid transitions produce HTTP 409 via REST, `IllegalStateException` via `ProgressService`.

### Typed Union Shapes

The `shapeType` field discriminates the `state` structure:

| shapeType | State structure | Example |
|-----------|----------------|---------|
| `percentage` | `{value: 42}` | Simple % complete |
| `count` | `{current: 23, total: 50, unit: "files"}` | N-of-M with unit |
| `step` | See Step Shape below | Named step DAG with per-step state |

Three shapes cover the real cases. Extensible to `custom` with an arbitrary JSON schema reference in the future (work#307).

**Validation constraints per shape:**

- **Percentage:** `value` is an integer in range [0, 100]. Rejects negatives, values > 100, and non-integer values. Fractional progress is modeled via `count` shape with fine-grained units (e.g. `{current: 425, total: 1000, unit: "basis-points"}`).
- **Count:** `current` and `total` are non-negative integers. `current <= total` is enforced. `total` is mutable — emergent trees may increase it as new items appear. `unit` is an optional freeform string (descriptive, no controlled vocabulary).
- **Step:** See Step Shape below for DAG-specific validation rules.

The `state` field is `JsonNode` at the storage layer. Shape conformance validation happens in `casehub-work` progress-core on creation and update — this is structural integrity, enforced uniformly for all callers (REST and programmatic).

### Step Shape

The `step` shape is richer than `percentage` and `count` — it models a forward-only DAG of named steps with lifecycle, dependencies, and conditions.

**Step definition** (stored in the `definition` field, declared at creation time):

```java
public record StepDefinition(
    String name,
    boolean optional,           // false = required for ProgressInstance completion
    List<String> dependsOn,     // DAG edges — empty = root step (can activate immediately)
    String condition            // JQ expression against step state; null = always eligible
)
```

Example definition:
```json
[
  {"name": "unpack", "optional": false, "dependsOn": []},
  {"name": "assembly", "optional": false, "dependsOn": ["unpack"]},
  {"name": "wiring", "optional": false, "dependsOn": ["unpack"]},
  {"name": "calibration", "optional": false, "dependsOn": ["assembly", "wiring"]},
  {"name": "testing", "optional": true, "dependsOn": ["calibration"],
   "condition": ".steps.calibration.data.passedChecks > 5"}
]
```

**Step state** (the `state` JsonNode):
```json
{
  "steps": {
    "unpack":      {"status": "completed", "data": {"notes": "all parts present"}},
    "assembly":    {"status": "active",    "data": {"progress": 60}},
    "wiring":      {"status": "active"},
    "calibration": {"status": "pending"},
    "testing":     {"status": "pending"}
  }
}
```

**Step status lifecycle:**

```java
public enum StepStatus {
    PENDING, ACTIVE, COMPLETED, SKIPPED, FAILED;

    public boolean isTerminal() {
        return this == SKIPPED; // Only SKIPPED is truly irreversible — COMPLETED/FAILED can be reactivated
    }

    public boolean isDone() {
        return this == COMPLETED || this == SKIPPED; // Satisfies completion requirement for required steps
    }

    public boolean isQuiescent() {
        return this == COMPLETED || this == SKIPPED || this == FAILED;
    }

    public boolean isActive() {
        return this == ACTIVE;
    }
}
```

Valid transitions:

| From | To | Trigger |
|------|-----|---------|
| PENDING | ACTIVE | Step started via `/steps/{stepName}/start` |
| ACTIVE | COMPLETED | Step completed |
| ACTIVE | SKIPPED | Step skipped (optional steps only) |
| ACTIVE | FAILED | Step failed |
| COMPLETED | ACTIVE | Step reactivated (retry) |
| FAILED | ACTIVE | Step reactivated (retry after failure) |

**Not allowed:** `PENDING -> COMPLETED/SKIPPED/FAILED` (must activate first), `SKIPPED -> any` (skipped is terminal — unskipping requires orchestrator to create a new ProgressInstance).

Step reactivation (`COMPLETED -> ACTIVE`, `FAILED -> ACTIVE`) mirrors instance-level reactivation. The orchestrator decides to retry a step; the progress model records it. The instance must be in `ACTIVE` status to accept step state changes — if derived completion already fired, the orchestrator must reactivate the instance first (`POST /progress/{id}/reactivate`), then reactivate the step.

`StepStatus` lives in `progress-api/` and is registered in LIFECYCLE.md alongside `ProgressStatus`. Only SKIPPED is terminal (irreversible — unskipping requires a new ProgressInstance). COMPLETED and FAILED are quiescent but reactivatable, mirroring instance-level reactivation semantics. `isDone()` captures "this step satisfies its completion requirement" (COMPLETED or SKIPPED) — used by the derived completion check. The JSON step state serializes `StepStatus` as lowercase strings for readability; validation in `progress-core` deserializes through the enum.

**Definition immutability:** The `definition` field is set at creation time and is immutable thereafter. `ProgressService` mutation methods only modify `state` — no API or service method exposes definition updates. This preserves DAG validation invariants: completed steps always reference the dependency graph that was validated at creation.

**Validation rules (shape conformance / data integrity):**
- Can't activate a step whose `dependsOn` steps aren't all completed or skipped
- Can't skip a non-optional step
- `condition` evaluated via `ConditionEvaluator` SPI against the full step state (see Module Structure — keeps `progress-core` pure Java)
- **Condition evaluation is gate-only** — conditions are checked when activation is attempted (via `/steps/{stepName}/start`), not continuously. Steps do not auto-activate when their conditions become true. The orchestrator decides when to start a step; the progress model validates that the start is legal (dependencies met, condition passes). This is consistent with "observation, not orchestration."
- Cycle detection at creation time — reject definitions with circular `dependsOn`
- **At least one required step** — definitions where all steps are `optional: true` are rejected at creation time. With zero required steps, derived completion would be vacuously true on the first step state change. If all steps are genuinely optional, use `percentage` or `count` shape instead.
- **JQ syntax validation at creation time** — reject definitions with syntactically invalid JQ conditions at creation, not at step activation. Invalid syntax produces HTTP 400 on create.
- **Derived completion:** when all required (non-optional) steps are done (`isDone()` — COMPLETED or SKIPPED), the ProgressInstance auto-transitions to COMPLETED. This is a logical invariant of the shape definition — the orchestrator declared which steps are required, and they're all done. Note: a FAILED required step does NOT satisfy `isDone()`, so derived completion does not fire while any required step is failed.
- **No auto-failure:** when a required step fails, the ProgressInstance status is NOT automatically changed. The orchestrator observes the step failure (via `STATE_UPDATED` event) and decides: retry the step, skip it, or explicitly fail the instance via `POST /progress/{id}/fail`. This keeps failure handling where it belongs — with the system that understands the domain context.

### What's Deliberately Absent

- No tree structure on the instance — parent pointer only, tree is a query
- No rollback history on the instance — the event trail covers that
- No retry tracking — orchestrator's concern, observer SPI for anyone who wants it

---

## 2. RollupStrategy SPI

```java
public interface RollupStrategy extends NamedStrategy {
    JsonNode compute(RollupContext context);
}

public record RollupContext(
    ProgressInstance parent,
    List<ProgressInstance> children  // ALL children — strategies filter by status as needed
)
```

The platform calls `compute()` whenever a child's state changes or a new child is attached. The returned `JsonNode` becomes the parent's new state.

The parent's `shapeType` doesn't need to match the children's — a `count` parent can aggregate `percentage` children.

### Built-in Strategies

| Strategy ID | Behaviour |
|-------------|-----------|
| `count-completed` | `{current: <completed children>, total: <total children>}` — the default |
| `average-percentage` | `{value: <mean of children's percentage values>}` |
| `weighted-percentage` | `{value: <weighted mean>}` — weights declared in parent state |

**Zero-children behavior:** When `RollupContext.children` is empty (parent created but no children attached yet), built-in strategies return a zero-progress baseline:
- `count-completed`: `{current: 0, total: 0}` — 0% complete, not 100%
- `average-percentage`: `{value: 0}` — no children means no progress
- `weighted-percentage`: `{value: 0}` — same rationale

This matters for milestone criteria: `progress.workitem.abc123.state.value >= 80` correctly evaluates to `false` for a childless parent.

**No rollup (leaf):** `rollupStrategyId = null`. State is reported directly, not derived.

**Custom:** implement `RollupStrategy`, annotate with CDI, select by config. The heterogeneous fleet deployment case uses different strategy IDs at each level. You pay the complexity only at the levels that need it.

**Resolution:** Rollup strategies resolve via the existing `StrategyResolver.resolve(RollupStrategy.class, rollupStrategyId)` from `casehub-platform-api`. `DefaultStrategyResolver` auto-discovers all CDI beans implementing `NamedStrategy` subtypes and indexes them by interface type and `id()`. No extension to `StrategyResolver` is needed — it already handles any `NamedStrategy` subtype generically. Built-in strategies are CDI beans in `progress-core`; the first discovered strategy for the `RollupStrategy` type becomes the default (used when `rollupStrategyId` is null but `rollupStrategyId != null` scenarios resolve by ID).

**Dynamic children (emergent trees):** when a new child is attached, the platform re-invokes rollup on the parent. The parent's aggregate is always current, even as children appear.

**Children include all statuses.** `RollupContext.children` contains PENDING, ACTIVE, COMPLETED, and FAILED children. Built-in strategies filter to ACTIVE + COMPLETED internally. Custom strategies have full visibility — a strategy that needs "3 of 10 children failed" reads it directly from the children list.

**Step failures are not visible to rollup.** Rollup operates on instance-level status (`ProgressStatus`), not step-level state. When a required step fails inside a step-shaped child instance, the child remains ACTIVE (per "no auto-failure") and its parent's rollup sees it as still in progress. The parent and all ancestors have no signal that a required step has failed — until the orchestrator explicitly fails the child via `POST /progress/{childId}/fail`, at which point the child's status changes to FAILED and the rollup reflects it. This is a deliberate consequence of "observation, not orchestration": the rollup tree faithfully represents instance-level state, and failure is an orchestrator decision, not a rollup inference. SSE consumers watching the subtree DO see step failures — `STATE_UPDATED` events on the child carry the full step state including the failed step. Consumers building dashboards from rollup data should be aware that a healthy-looking parent may have children with failed required steps awaiting orchestrator action.

---

## 3. Events

State changes emit CDI events. This is the integration surface — milestones, conversation rendering, audit, SSE all subscribe here.

```java
public record ProgressUpdatedEvent(
    UUID progressId,
    String tenancyId,            // multi-tenancy scoping (matches WorkItemLifecycleEvent)
    String scopeType,
    String scopeId,
    UUID parentProgressId,       // null for roots
    UUID rootProgressId,         // enables efficient subtree SSE filtering
    String shapeType,
    JsonNode previousState,      // null on creation
    JsonNode currentState,
    ProgressStatus status,
    ProgressChangeType changeType,
    Instant timestamp
)

enum ProgressChangeType {
    CREATED,          // new instance
    STATE_UPDATED,    // state changed (forward or backward)
    CHILD_ATTACHED,   // new child added to this parent
    COMPLETED,        // quiescent — success
    FAILED,           // quiescent — failure
    REACTIVATED,      // COMPLETED/FAILED -> ACTIVE (retry)
    ROLLED_BACK       // state moved backward
}
```

### Rollback

The model doesn't prevent backward movement — it records it. `previousState` and `currentState` on the event tell consumers what changed. Backward movement emits a `ROLLED_BACK` event. Examples by shape:

- **Percentage:** value decreasing (80 → 60) — e.g. rework discovered, progress regressed
- **Count:** current decreasing (35/50 → 30/50) — e.g. items failed revalidation
- **Step:** a step's status moving from `completed` → `active` or `failed` → `active` (reactivation/retry) — the orchestrator decided to retry; the progress model observed it

The `ROLLED_BACK` change type is shape-agnostic — it fires whenever the new state represents regression from the previous state. For percentage and count shapes, this is a numeric comparison. For step shapes, it's any step status moving to a less-advanced state. The orchestrator decides when to roll back; the progress model classifies and records the direction.

**Per-shape rollback detection:** `ProgressService` auto-detects rollback by comparing previous and current state using shape-specific comparators:
- **percentage:** `currentState.value < previousState.value` — value decreased
- **count:** `currentState.current < previousState.current` — progress counter decreased (a `total` change alone is not a rollback)
- **step:** any step transitioning from a quiescent status back to active (`completed→active`, `failed→active`) — detected by comparing step status ordinals. `ACTIVE→PENDING` is not a valid step transition (see step lifecycle table), so it is not a rollback case.

If rollback is detected, `changeType` is set to `ROLLED_BACK` instead of `STATE_UPDATED`. This is auto-detected, not caller-specified — the event stream is self-describing.

### Event Semantics

**CHILD_ATTACHED:** fires on the **parent** (progressId = parent's ID). The child receives a separate CREATED event. Attaching a child via `POST /progress/{id}/children` emits two events: CREATED on the child, CHILD_ATTACHED on the parent. Different entities, different subscribers.

**PENDING -> ACTIVE:** the first STATE_UPDATED on a PENDING instance transitions it to ACTIVE. No separate activation event — the status change is visible on the event.

**Step transitions:** Step-level state changes (step activated, step completed, step skipped, step failed) are `STATE_UPDATED` events. The state diff shows what changed. No step-specific `ProgressChangeType` values — the change type enum is shape-agnostic.

### Rollup Cascade

Rollup is **asynchronous** via `@ObservesAsync`. When a child emits `ProgressUpdatedEvent`, a CDI observer (`@ObservesAsync`) re-reads all children from the store and re-computes the parent's rollup in its own `@Transactional` boundary. If the parent's state changes, the parent emits its own `ProgressUpdatedEvent`, cascading up the tree. This follows the `MultiInstanceCoordinator` pattern: `@ObservesAsync` ensures the child's transaction is committed before the rollup observer runs; the observer's `@Transactional` method manages its own transaction boundary.

Asynchronous propagation means:
- No transaction spanning multiple tree levels — each level commits independently
- Concurrent leaf updates enqueue separate rollup recomputations on the managed executor
- Eventual consistency: a query immediately after a leaf update may return the previous rollup; the next rollup cycle corrects it
- OCC (`@Version`) on the parent **detects** concurrent modifications — `OptimisticLockException` fires when two rollup recomputations race

**Retry on OCC conflict:** the rollup observer retries on `OptimisticLockException` — re-reads the parent, re-reads all children, recomputes, and retries. Bounded at 3 attempts (configurable via `casehub.progress.rollup.max-retries`). Each retry re-reads ALL children, so a successful write reflects the aggregate of all committed children at read time — not just the triggering child. This means OCC contention under high fan-in (e.g., 100 concurrent child updates) does not lose data: the last successful rollup write captures all committed children's state.

**Reconciliation sweep:** To handle the crash-window edge case (application crashes after child commit but before the async rollup observer fires), a scheduled reconciliation job periodically re-computes rollup for parent instances whose `updatedAt` is older than their most recent child's `updatedAt`. This is a catch-up mechanism, not a primary path — normal operation relies on event-driven rollup. The sweep interval is configurable via `casehub.progress.rollup.reconciliation-interval` (default: 5 minutes).

**Note on milestone composition:** The engine's `MilestoneLifecycleManager` uses Vert.x `@ConsumeEvent` + `@RunOnVirtualThread` for asynchronous milestone evaluation, not CDI observation. The progress rollup observer uses `@ObservesAsync` (CDI) because it operates within casehub-work's CDI event surface. Both achieve the same goal — asynchronous, independently-transacted evaluation — via different mechanisms appropriate to their respective modules.

### Event Persistence

Events are persisted to a `progress_event` table via `ProgressEventStore` SPI (following the `AuditEntry`/`AuditEntryStore` pattern from casehub-work). CDI events are the integration surface; the `ProgressEventStore` is the persistence surface. REST event trail endpoints (`GET /progress/{id}/events`) query the store.

**Self-containment distinction:** SSE events are self-contained — the CDI `ProgressUpdatedEvent` carries `scopeType`, `scopeId`, `parentProgressId`, `shapeType` alongside state and change type. Persisted `ProgressEventEntity` records are compact but include selected denormalized fields for query efficiency without joins: `scopeType`, `scopeId`, and `rootProgressId` (for subtree event queries). Other instance-level context fields (`parentProgressId`, `shapeType`) are immutable on `progress_instance` and available via join. The REST event trail endpoint (`GET /progress/{id}/events`) enriches responses with instance context from `ProgressInstanceStore`, so consumers of the REST API receive self-contained event records without manual joins.

The sequence of persisted events is the full history of how progression unfolded, including rollbacks.

---

## 4. REST API

### Reporting (write)

```
POST   /progress                              — create a new instance
PUT    /progress/{id}/state                   — update state
POST   /progress/{id}/complete                — mark completed
POST   /progress/{id}/fail                    — mark failed
POST   /progress/{id}/reactivate              — reactivate (COMPLETED/FAILED -> ACTIVE)
POST   /progress/{id}/children                — attach a new child instance
```

Creating an instance requires: `tenancyId`, `scopeType`, `scopeId`, `shapeType`, initial `state`. Optional: `parentProgressId`, `rollupStrategyId`, `definition` (required for `step` shape). `tenancyId` is a required field from request context, following the `WorkItemCreateRequest` pattern. `rootProgressId` is computed at creation: equal to the instance's own ID for roots, or inherited by looking up the parent's `rootProgressId` for children.

State updates require: `state` conforming to the declared `shapeType`. Shape conformance is validated in core (data integrity).

### Step Convenience Endpoints (write)

For `step`-shaped instances, convenience endpoints that modify step state and validate DAG constraints:

```
POST   /progress/{id}/steps/{stepName}/start      — activate a step
POST   /progress/{id}/steps/{stepName}/complete    — complete a step
POST   /progress/{id}/steps/{stepName}/skip        — skip (optional steps only)
POST   /progress/{id}/steps/{stepName}/fail        — fail a step
PUT    /progress/{id}/steps/{stepName}/state       — update step-level data
```

These are thin delegates — each modifies the step's entry in the `state` JSON and calls the same validation/event/rollup path as `PUT /progress/{id}/state`. They exist for ergonomics: a REST caller reporting "I finished the calibration step" shouldn't need to construct the full state JSON.

The generic `PUT /progress/{id}/state` still works for bulk updates or non-step shapes.

### Querying (read)

```
GET    /progress/{id}                         — single instance with current state
GET    /progress/{id}/tree                    — instance + all descendants (recursive)
GET    /progress/{id}/tree?depth=2            — bounded depth
GET    /progress?scopeType=X&scopeId=Y        — find by anchor
GET    /progress/{id}/events                  — event trail for this instance
GET    /progress/{id}/events?since=<instant>  — event trail since timestamp
```

Tree queries return pre-computed rollup — not calculated at query time.

Anchor queries return all progress instances attached to a scope, ordered by `createdAt DESC` (newest first). A PlanItem with retry iterations returns multiple instances. The first result is the current instance.

**Current instance convenience:** `GET /progress?scopeType=X&scopeId=Y&latest=true` returns only the most recently created instance for the scope. This serves the common case — conversation renderer and milestone evaluator both want "current progress for this scope" without filtering through historical iterations. `status` filter is also supported: `GET /progress?scopeType=X&scopeId=Y&status=ACTIVE,PENDING` returns only non-quiescent instances.

**Reactivation interaction:** `latest=true` orders by creation time, not by current activity. If an older instance is reactivated (COMPLETED→ACTIVE) while a newer instance exists, `latest=true` returns the newer instance — which may not be the currently active one. For the truly current instance when reactivation is possible, combine status and latest filters: `?status=ACTIVE,PENDING&latest=true`. In practice, the primary retry pattern (PlanItem repeat) creates NEW child instances per retry rather than reactivating old ones, so `latest=true` alone is correct for the common case.

### SSE (live)

```
GET    /progress/{id}/stream                  — SSE stream for this subtree
```

Real-time updates as any node in the subtree changes. SSE events (serialized from `ProgressUpdatedEvent` CDI events) are self-contained — they carry `scopeType`, `scopeId`, `shapeType`, `parentProgressId`, `previousState`, and `currentState`, so consumers don't need to re-query.

**SSE ordering contract:** SSE consumers MUST treat the event stream as eventually consistent. Child events precede parent rollup events but with an unbounded async gap (rollup is `@ObservesAsync` in its own transaction). Under high fan-in (e.g., 100 concurrent node updates in the fleet deployment scenario), an SSE client sees a burst of leaf events followed by a cascade of parent rollup events arriving in rollup-completion order, not leaf-update order. Reconciliation sweep events may update parents without a preceding child event in the same stream window, since the child event was already delivered before the sweep correction. Consumers that need a consistent aggregate snapshot at a point in time should use `GET /progress/{id}/tree` after receiving events, treating SSE as a change notification rather than a source of truth for aggregate state.

**Subtree membership tracking:** the SSE endpoint maintains a per-subscriber set of tracked progress IDs. On connection:
1. Load the subtree rooted at `{id}` (recursive query via `parentProgressId`, or CTE — same query as `GET /progress/{id}/tree`)
2. Subscribe to the `ProgressEventBroadcaster` stream, filtering events where `progressId` is in the tracked set
3. When a `CHILD_ATTACHED` event arrives for any node in the tracked set, add the new child's `progressId` to the set — this handles emergent trees dynamically

This is a per-subscriber filter on a shared broadcast stream, not a per-subtree broadcast channel. The `ProgressEventBroadcaster` itself streams all progress events (filtered by `tenancyId` for isolation); subtree scoping is applied at the SSE endpoint layer. The same pattern works for both `LocalProgressEventBroadcaster` and `PostgresProgressEventBroadcaster`.

---

## 5. Module Structure

Modules inside casehub-work. No separate repo needed — progress is a work concern, co-located with work runtime.

```
casehub-work/
  progress-api/           — pure Java: ProgressInstance, ProgressStatus,
                            ProgressUpdatedEvent, RollupStrategy SPI,
                            ConditionEvaluator SPI, ProgressCreateRequest,
                            typed shapes, StepDefinition, StepStatus
  progress-core/          — pure Java + Jandex: built-in rollup strategies,
                            rollup computation engine, shape validation,
                            DAG cycle detection. Condition evaluation
                            delegates to ConditionEvaluator SPI (injected
                            by progress-runtime — keeps core pure Java).
  progress-runtime/       — Quarkus extension: ProgressService, JPA entity,
                            persistence, event emission, rollup observer,
                            SSE broadcaster, JqConditionEvaluator
                            (implements ConditionEvaluator via
                            casehub-platform expression module)
  progress-rest/          — JAX-RS: reporting + query + step endpoints
                            (opt-in, thin delegate to ProgressService)
  progress-deployment/    — @BuildStep
  progress-memory/        — in-memory stores for tests
```

### ConditionEvaluator SPI

```java
@FunctionalInterface
public interface ConditionEvaluator {
    boolean evaluate(String expression, JsonNode context);
}
```

`progress-core` shape validation takes `ConditionEvaluator` as a parameter — core functions are pure, with no CDI injection. `progress-runtime` provides `JqConditionEvaluator @ApplicationScoped` which delegates to the `casehub-platform` expression module (`JQExpressionEvaluator` from `casehub-platform-api`). This keeps `progress-core` free of Quarkus extension dependencies while supporting JQ condition evaluation at runtime.

### ProgressCreateRequest

```java
public record ProgressCreateRequest(
    String tenancyId,           // required — multi-tenancy scoping
    String scopeType,           // required — "workitem", "planitem", "node", etc.
    String scopeId,             // required — the anchor entity's ID
    String shapeType,           // required — "percentage", "count", or "step"
    JsonNode state,             // required — initial state conforming to shape
    UUID parentProgressId,      // optional — null for root instances
    String rollupStrategyId,    // optional — null = no rollup (leaf)
    JsonNode definition         // conditional — required when shapeType = "step"
)
```

`rootProgressId` is not on the request — it is computed at creation: the instance's own ID for roots, or inherited from the parent's `rootProgressId` for children.

Validation at creation:
- `tenancyId`, `scopeType`, `scopeId`, `shapeType`, `state` are all required (HTTP 400 if missing)
- `state` must conform to the declared `shapeType` (shape validation in core)
- `definition` is required when `shapeType = "step"` (HTTP 400 if missing); ignored for other shapes
- Step definition must pass cycle detection and have at least one required step

### Dependencies

```
platform-api                ← NamedStrategy, ExpressionEvaluator, shared markers
    ^
casehub-work/progress-api   ← progress types + SPIs (pure Java)
    ^
casehub-work/progress-core  ← built-in strategies, rollup engine (pure Java)
    ^
casehub-work/progress-runtime ← Quarkus extension (persistence, events, SSE)
casehub-work/progress-rest    ← JAX-RS endpoints (opt-in)
    ^
engine                          ← adds explicit dep on progress-api (for ProgressUpdatedEvent)
                                   and progress-runtime (for ProgressService CDI injection)
blocks                          ← adds explicit dep on progress-api (for ProgressUpdatedEvent observation)
qhorus                          ← REST only, no compile-time dependency
```

### Persistence Design

**Entity:** `ProgressInstanceEntity` — JPA entity mapping `ProgressInstance` to the `progress_instance` table.

```java
@Entity
@Table(name = "progress_instance")
public class ProgressInstanceEntity extends PanacheEntityBase {
    @Id public UUID id;
    @Version @Column(nullable = false) public Long version = 0L;
    @Column(name = "tenancy_id", nullable = false) public String tenancyId;
    @Column(name = "scope_type", nullable = false) public String scopeType;
    @Column(name = "scope_id", nullable = false) public String scopeId;
    @Column(name = "parent_progress_id") public UUID parentProgressId;
    @Column(name = "root_progress_id", nullable = false) public UUID rootProgressId;
    @Column(name = "shape_type", nullable = false) public String shapeType;
    @JdbcTypeCode(SqlTypes.JSON) @Column public JsonNode definition;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false) public JsonNode state;
    @Enumerated(EnumType.STRING) @Column(nullable = false) public ProgressStatus status;
    @Column(name = "rollup_strategy_id") public String rollupStrategyId;
    @Column(name = "created_at", nullable = false) public Instant createdAt;
    @Column(name = "updated_at", nullable = false) public Instant updatedAt;
}
```

`@Version` provides optimistic concurrency control — concurrent updates to the same instance produce `OptimisticLockException`, matching `WorkItemEntity` and `WorkItemSpawnGroup` patterns.

`state` and `definition` use `@JdbcTypeCode(SqlTypes.JSON)` for PostgreSQL `jsonb`; H2 `MODE=PostgreSQL` maps it to `TEXT`.

**Event entity:** `ProgressEventEntity` — append-only, following `AuditEntry` pattern.

```java
@Entity
@Table(name = "progress_event")
public class ProgressEventEntity extends PanacheEntityBase {
    @Id public UUID id;
    @Column(name = "tenancy_id", nullable = false) public String tenancyId;
    @Column(name = "progress_id", nullable = false) public UUID progressId;
    @Column(name = "root_progress_id", nullable = false) public UUID rootProgressId;
    @Column(name = "scope_type", nullable = false) public String scopeType;
    @Column(name = "scope_id", nullable = false) public String scopeId;
    @Column(name = "change_type", nullable = false) public String changeType;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "previous_state") public JsonNode previousState;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "current_state") public JsonNode currentState;
    @Column(nullable = false) public String status;
    @Column(name = "occurred_at", nullable = false) public Instant occurredAt;
}
```

**Store SPIs:**

```java
public interface ProgressInstanceStore {
    ProgressInstanceEntity put(ProgressInstanceEntity instance);
    Optional<ProgressInstanceEntity> get(UUID id);
    List<ProgressInstanceEntity> findByScopeTypeAndScopeId(String scopeType, String scopeId);
    List<ProgressInstanceEntity> findByParentProgressId(UUID parentProgressId);
}

public interface ProgressEventStore {
    void append(ProgressEventEntity event);
    List<ProgressEventEntity> findByProgressId(UUID progressId);
    List<ProgressEventEntity> findByProgressIdSince(UUID progressId, Instant since);
    List<ProgressEventEntity> findByRootProgressIdSince(UUID rootProgressId, Instant since);
}
```

CDI backend activation follows the four-tier priority ladder per the persistence-backend-cdi-priority protocol.

### ProgressService

The high-level service coordinating all progress operations. Lives in the `progress-runtime` module. Co-deployed consumers (casehub-work, casehub-engine) inject it via CDI. REST endpoints are thin delegates.

```java
@ApplicationScoped
public class ProgressService {
    ProgressInstance create(ProgressCreateRequest request);
    ProgressInstance updateState(UUID id, JsonNode newState);
    ProgressInstance complete(UUID id);
    ProgressInstance fail(UUID id);
    ProgressInstance reactivate(UUID id);
    ProgressInstance attachChild(UUID parentId, ProgressCreateRequest childRequest);
    Optional<ProgressInstance> findById(UUID id);
    List<ProgressInstance> findByScope(String scopeType, String scopeId);
    List<ProgressInstance> findChildren(UUID parentId);

    // Step convenience methods (step-shaped instances only)
    ProgressInstance startStep(UUID id, String stepName);
    ProgressInstance completeStep(UUID id, String stepName);
    ProgressInstance skipStep(UUID id, String stepName);
    ProgressInstance failStep(UUID id, String stepName);
    ProgressInstance updateStepState(UUID id, String stepName, JsonNode data);
}
```

All mutating methods are `@Transactional`, following the `WorkItemService` pattern. Each mutating method: validates shape conformance (delegates to core) -> validates status transition -> persists via `ProgressInstanceStore` -> persists event via `ProgressEventStore` -> emits `ProgressUpdatedEvent` CDI event. Instance update and event persistence happen in the same transaction — they commit or fail together. The rollup observer (separate CDI bean, `@ObservesAsync`) listens for `ProgressUpdatedEvent` and re-invokes `ProgressService` to update the parent, with OCC retry, in its own transaction boundary.

**Flyway migrations:** path `classpath:db/work/migration/`, version range V6000–V6999 (reserved for the progress module), consistent with other optional modules (ai: V4001–V4002, notifications: V3000–V3001, issue-tracker: V5000–V5001). Single Flyway path per PP-20260525-607b33.

```sql
-- V6000__progress_schema.sql
CREATE TABLE progress_instance (
    id                  UUID         NOT NULL,
    version             BIGINT       NOT NULL DEFAULT 0,
    tenancy_id          VARCHAR(255) NOT NULL,
    scope_type          VARCHAR(255) NOT NULL,
    scope_id            VARCHAR(255) NOT NULL,
    parent_progress_id  UUID,
    root_progress_id    UUID         NOT NULL,
    shape_type          VARCHAR(50)  NOT NULL,
    definition          JSONB,
    state               JSONB        NOT NULL,
    status              VARCHAR(20)  NOT NULL,
    rollup_strategy_id  VARCHAR(255),
    created_at          TIMESTAMP    NOT NULL,
    updated_at          TIMESTAMP    NOT NULL,
    CONSTRAINT pk_progress_instance PRIMARY KEY (id)
);

CREATE INDEX idx_progress_scope ON progress_instance (scope_type, scope_id);
CREATE INDEX idx_progress_parent ON progress_instance (parent_progress_id);
CREATE INDEX idx_progress_root ON progress_instance (root_progress_id);
CREATE INDEX idx_progress_tenancy ON progress_instance (tenancy_id);

CREATE TABLE progress_event (
    id                UUID         NOT NULL,
    tenancy_id        VARCHAR(255) NOT NULL,
    progress_id       UUID         NOT NULL,
    root_progress_id  UUID         NOT NULL,
    scope_type        VARCHAR(255) NOT NULL,
    scope_id          VARCHAR(255) NOT NULL,
    change_type       VARCHAR(30)  NOT NULL,
    previous_state    JSONB,
    current_state     JSONB,
    status            VARCHAR(20)  NOT NULL,
    occurred_at       TIMESTAMP    NOT NULL,
    CONSTRAINT pk_progress_event PRIMARY KEY (id)
);

CREATE INDEX idx_progress_event_progress ON progress_event (progress_id, occurred_at);
CREATE INDEX idx_progress_event_root ON progress_event (root_progress_id, occurred_at);
CREATE INDEX idx_progress_event_tenancy ON progress_event (tenancy_id);
CREATE INDEX idx_progress_event_scope ON progress_event (scope_type, scope_id, occurred_at);
```

### SSE Broadcaster

Follows the `WorkItemEventBroadcaster` SPI pattern: `ProgressEventBroadcaster` interface with `LocalProgressEventBroadcaster` (in-process Mutiny `BroadcastProcessor`) as the default, and `PostgresProgressEventBroadcaster` (LISTEN/NOTIFY) for cross-node delivery. Uses a separate PostgreSQL channel (`progress_events`) from work's channel.

**Subtree streaming:** The SSE endpoint `GET /progress/{id}/stream` streams events for the entire subtree rooted at `{id}`. Subtree membership tracking uses `rootProgressId` (stored on every instance at creation time — inherited from the parent chain). The broadcaster:

1. Subscribes to ALL progress events for the tenant
2. Filters by `rootProgressId == subscribedRootId` — O(1) field comparison per event, no tree traversal
3. For non-root subscriptions (subscribing to a mid-tree node), the REST endpoint queries all descendants of `{id}` at subscription time and builds a `Set<UUID>` of member IDs. Events are accepted if `progressId ∈ memberSet`.
4. **Emergent children:** `CHILD_ATTACHED` and `CREATED` events with a `parentProgressId` in the member set automatically add the new child's ID to the set. New children appearing mid-flight are included in the stream without re-subscription.

For LISTEN/NOTIFY, the notification payload includes `rootProgressId` and `parentProgressId`, enabling the same filtering at the PostgreSQL listener level.

**Both broadcasters must use `@Observes(during = TransactionPhase.AFTER_SUCCESS)`** to prevent phantom event delivery when a transaction rolls back (e.g., OCC conflict). This follows the `PostgresWorkItemEventBroadcaster` pattern and the ARC42STORIES §8 anti-pattern fix. The existing `LocalWorkItemEventBroadcaster` omits this — the progress module must not repeat that bug.

**Subtree filtering:** SSE subtree streams (`GET /progress/{id}/stream`) filter events by `rootProgressId` equality — efficient O(1) check per event, no ancestry walk required. For non-root subtree streams, the broadcaster maintains a lightweight in-memory set of descendant IDs per active subscription (populated from the initial tree query, extended by `CHILD_ATTACHED` events). This keeps server-side filtering efficient without materialized paths or per-event database queries.

---

## 6. Milestone Composition

Progress and Milestone compose via events, not coupling.

**Integration path:** `ProgressUpdatedEvent` fires -> engine writes progress state into case context at `progress.<scopeType>.<scopeId>` -> `CaseContextChangedEvent` fires -> `MilestoneLifecycleManager` evaluates expression criteria (existing mechanism). A milestone criterion like `progress.workitem.abc123.state.value >= 80` works with existing expression evaluation.

**What the progress model needs from the engine:**
- Milestone criteria expressions that can reference progress state paths (e.g. `progress.workitem.abc123.state.value >= 80`)
- A mechanism to handle milestone re-evaluation when progress aggregates change (e.g., emergent tree: new children appear, aggregate drops below threshold). The existing TODO at `MilestoneLifecycleManager:198` ("maybe it must be configurable whether SLA violation deactivates the milestone or not?") addresses the same category of configurable milestone lifecycle behavior.

**What the progress model does NOT prescribe:** the shape of any engine-internal SPI (reset policies, milestone lifecycle configuration). How engine#84 is restructured to deliver configurable milestone re-evaluation is an engine-internal decision. The progress spec's contract is limited to: "Progress publishes `ProgressUpdatedEvent`. The engine subscribes, writes progress state into `CaseContext`, and `MilestoneLifecycleManager` evaluates expression criteria via the existing `ExpressionEngineRegistry` mechanism."

---

## 7. Integration Points

### casehub-work

- Removes `percentComplete` and `statusNote` from WorkItem (new migration dropping columns added by V37). Callers adopt the progress API directly — no backward-compatible shim.
- `WorkItemService.progress()` removed. Callers use `ProgressService` directly (co-deployed in same runtime).
- The old `PUT /workitems/{id}/progress` endpoint is removed. Callers use `PUT /progress/{id}/state` or step convenience endpoints.
- **Work spawning:** parent WorkItem's ProgressInstance gets children as WorkItems are spawned. `WorkItemSpawnGroup` continues to own M-of-N completion policy (idempotency, `policyTriggered`, `onThresholdReached`) — that's control, not observation. The progress tree observes completion state; the spawn group controls policy outcomes. A parent WorkItem's ProgressInstance can use the `count-completed` rollup strategy to aggregate child completion, giving the same data as `SpawnGroup.completedCount` through the observation lens.

### casehub-engine

- `MilestoneLifecycleManager` subscribes to `ProgressUpdatedEvent`
- Milestone re-evaluation and reset policy is engine-internal (see engine#84, `MilestoneLifecycleManager:198` TODO)
- PlanItem repeat: each iteration creates a child ProgressInstance (the anchoring pattern)
- **Hierarchy construction:** the engine creates ProgressInstances with `parentProgressId` set to mirror PlanItem containment. Hierarchy is always declared via `parentProgressId` — the progress module never walks PlanItem trees. "Inferred hierarchy" means the engine infers the parent-child relationship from PlanItem containment and declares it in the progress model. The progress module only sees `parentProgressId`.

### Anchoring composition

Three mechanisms serve different purposes and compose for the common case:
- `callerRef` on WorkItem: opaque engine->work routing token (`"case:{caseId}/pi:{planItemId}"`). casehub-work never interprets it.
- PlanItem ID: engine-internal identity.
- `scopeType`/`scopeId` on ProgressInstance: platform-level anchor to any entity.

**Common traversal — "progress for this PlanItem":** engine looks up the PlanItem -> finds its WorkItem via callerRef -> finds the ProgressInstance by `scopeType="workitem"`, `scopeId=workItemId`. The engine can also create a ProgressInstance anchored directly to the PlanItem (`scopeType="planitem"`, `scopeId=planItemId`), bypassing WorkItem entirely. Which path to use depends on whether the progress is conceptually "work progress" (anchor to WorkItem) or "plan progress" (anchor to PlanItem).

### casehub-blocks (blocks#62)

- `ConversationRenderer` accepts progress snapshot alongside reactions
- Subscribes to `ProgressUpdatedEvent`, renders as choreography overlay per topic
- Adds explicit dependency on `progress-api` (for `ProgressUpdatedEvent` type)

### qhorus

- Agents report structured progress via REST API
- No special integration — agents are REST clients

---

## 8. Testing Strategy

### Pure Java (progress-api + progress-core)

| What to prove | Approach |
|---|---|
| Percentage shape validates correctly | Bounds (0-100), rejects negatives and >100 |
| Count shape validates correctly | `current <= total`, rejects negative, unit optional |
| Step shape validates correctly | Rejects unknown step names, validates status transitions |
| DAG cycle detection | Define steps with circular `dependsOn`, verify creation rejects |
| Dependency enforcement | Attempt to activate step whose dependency is still pending, verify rejection |
| Parallel branches | Two steps with same dependency, both activate independently |
| Optional skip | Skip an optional step, verify downstream steps become eligible |
| Non-optional skip rejected | Attempt to skip a required step, verify rejection |
| Condition evaluation | Step with JQ condition, verify it blocks activation when false, allows when true |
| Auto-completion | All required steps completed, verify ProgressInstance transitions to COMPLETED |
| Auto-completion with optional | Required steps done, optional step still pending, verify COMPLETED fires |
| Step failure does NOT auto-fail instance | Fail a required step, verify ProgressInstance remains ACTIVE — orchestrator must explicitly call `/fail` |
| Step reactivation | Complete then reactivate a step (retry), verify state is legal |
| parentProgressId builds trees | Flat, one-level, deep (4+) trees; query subtree |
| Rollup cascades | 3-level tree, update leaf, verify parent and grandparent recompute |
| Heterogeneous rollup | Leaf=percentage, parent=count, grandparent=percentage; different strategies per level |
| Emergent tree rollup | Add third child mid-flight, verify parent recalculates |
| Rollback event | Forward then backward, verify ROLLED_BACK type and rollup reflects backward state |
| Failed children excluded | Mark child FAILED, verify default rollup ignores it |

### Engine Hierarchy Construction (critical)

```
Given: case with PlanItems A -> B -> C (A contains B, B contains C)
And: engine creates ProgressInstances with parentProgressId mirroring PlanItem containment
When: leaf C's progress updates
Then: rollup cascades C -> B -> A via parentProgressId — same mechanism as any declared tree
```

Proves that engine-constructed hierarchy uses the same rollup path as explicitly declared trees.

### Integration (Quarkus)

| What to prove | Approach |
|---|---|
| REST round-trip | Create, update, query via endpoints |
| Step endpoints round-trip | Start, update state, complete, skip steps via convenience endpoints |
| SSE streaming | Subscribe to subtree, update leaf, verify event arrives |
| Milestone fires on threshold | Progress reaches 85%, milestone with `>= 80` criterion activates |
| Milestone reset policy | Threshold crossed, new child added, aggregate drops; verify KEEP default and RESET opt-in |
| Reconciliation sweep + live rollup | Sweep fires while live rollup is processing same parent — verify OCC retry handles contention without data loss; SSE consumer receives exactly one correction event, not duplicates |
| Reconciliation sweep SSE | Sweep corrects a stale parent — SSE consumer receives parent update event with no preceding child event in the stream window; verify consumer handles orphan parent event gracefully |

### Fleet Deployment Stress Test

4-level tree: fleet -> datacenters -> nodes -> components. Static datacenters, emergent components. Heterogeneous rollup per level. Verify root aggregate accuracy as leaves report asynchronously. Proves the model handles the shape — not the transport.

**Latency measurement:** In addition to accuracy, measure root-aggregate staleness — the time between a leaf update commit and the root aggregate reflecting it. Under concurrent load (100 nodes updating simultaneously), measure P50 and P99 staleness to characterise the eventual consistency window. This validates that the async rollup cascade latency is acceptable for the fleet deployment use case.

---

## Use Case Matrix

| # | Scenario | Orchestration | Topology | Hierarchy | Rollup |
|---|----------|---------------|----------|-----------|--------|
| 1 | Human reports % on standalone WorkItem | Self-directed | Sequential local | None (flat) | None |
| 2 | AI agent reports structured progress on WorkItem | Self-directed | Sequential local | None (flat) | None |
| 3 | WorkItem inside case — case wants aggregate | Self-directed | Sequential local | Engine-declared from PlanItem tree | Case aggregates PlanItem progress |
| 4 | Parent WorkItem spawns children | Work spawning | Parallel local | parentProgressId | Automatic from children |
| 5 | PlanItem with engine repeat control | Engine repeat | Sequential local | parentProgressId | Aggregate across iterations |
| 6 | Flow loop dispatches to remote agents | Flow loop | Distributed async | parentProgressId | Aggregate across async iterations |
| 7 | Case + Flow loop + work spawning | Mixed | Distributed async | Engine-declared + explicit | Cross-hierarchy rollup |
| 8 | Progress threshold triggers Milestone | Any | Any | N/A | Composition point |
| 9 | Distributed installation — fleet/dc/node/component | Mixed | Distributed async | Deep parentProgressId (4+), static + emergent | Heterogeneous per level |
| 10 | Step-based WorkItem with DAG | Self-directed | Sequential local | None (flat) | None — steps are internal |
| 11 | Step-based WorkItem with conditional steps | Self-directed | Sequential local | None (flat) | None — JQ evaluates conditions |

## Scope Boundaries

**In scope:** recording state, nesting, rollup, events, querying, SSE, milestone composition via events, step DAG with dependencies/optional/conditions.

**Out of scope:** control flow, retry decisions, failure handling, orchestration. These capabilities exist in engine, Flow, and work — the progress model observes what they do.

**Deferred:**
- Arbitrary JSON schema shape type — work#307
- Rollback control mechanism — work#308
- Visualisation modes — work#309
- Progress event retention policy (TTL, archival, partitioning) — work#310

**Scope entity lifecycle:** Progress instances intentionally outlive their scoped entities. `scopeType`/`scopeId` are strings with no FK constraint — this is by design for scope-agnostic decoupling. When a scoped entity is deleted, its progress instances remain for audit and historical queries. Consumers that want cascade cleanup can observe scope entity deletion events and call `ProgressService` to clean up. This is documented, not a gap.
