# Progress Model — Platform-Level Observation and Reporting

**Date:** 2026-07-17
**Status:** Draft
**Related issues:** casehubio/work#237 (ideas capture), casehubio/engine#84 (milestone alignment — stale), casehubio/blocks#49 (topic-aware conversation), casehubio/blocks#62 (progress overlay consumer)

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
- **Observation, not control** — records state, doesn't validate structure or drive execution
- **Typed union now, extensible later** — small set of built-in shapes; arbitrary JSON schema can be added as a future shape type
- **Pay for complexity only when needed** — a flat percentage on a standalone WorkItem uses the same type as a 4-level fleet deployment tree
- **Hierarchy is always declared via parentProgressId** — orchestrators set it explicitly. For case-embedded progress, the engine constructs the progress tree by mirroring PlanItem containment into parentProgressId relationships. The progress module has no knowledge of PlanItem trees.

---

## 1. Core Types

### ProgressInstance

The atomic unit. Same shape at every level of any tree.

```java
public record ProgressInstance(
    UUID id,
    String scopeType,          // "workitem", "planitem", "node", anything
    String scopeId,            // the anchor's ID
    UUID parentProgressId,     // null for root instances
    String shapeType,          // discriminator for the typed union
    JsonNode state,            // current state conforming to the shape
    ProgressStatus status,     // PENDING, ACTIVE, COMPLETED, FAILED
    String rollupStrategyId,   // null = no rollup (leaf), or NamedStrategy id
    Instant createdAt,
    Instant updatedAt
)

enum ProgressStatus { PENDING, ACTIVE, COMPLETED, FAILED }
```

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
- Any → PENDING — PENDING means "no activity yet"; once left, cannot be re-entered
- COMPLETED ↔ FAILED — terminal-to-terminal must go through ACTIVE (reactivate, then transition)

Invalid transitions produce HTTP 409 via REST, `IllegalStateException` via `ProgressService`.

### Typed Union Shapes

The `shapeType` field discriminates the `state` structure:

| shapeType | State structure | Example |
|-----------|----------------|---------|
| `percentage` | `{value: 42}` | Simple % complete |
| `count` | `{current: 23, total: 50, unit: "files"}` | N-of-M with unit |
| `step` | `{current: "validate", steps: ["unpack","configure","validate","start","smoke-test"]}` | Named step position |

Three shapes cover the real cases. Extensible to `custom` with an arbitrary JSON schema reference in the future.

The `state` field is `JsonNode` at the storage layer. Shape conformance validation (is this valid JSON for a `percentage` shape?) happens in `casehub-progress-core` on creation and update — this is data integrity, not structural control. Both the REST layer and programmatic callers get the same validation.

### What's Deliberately Absent

- No tree structure on the instance — parent pointer only, tree is a query
- No rollback history on the instance — the event trail covers that
- No retry tracking — orchestrator's concern, observer SPI for anyone who wants it
- No structural validation — orchestrator's responsibility

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

**No rollup (leaf):** `rollupStrategyId = null`. State is reported directly, not derived.

**Custom:** implement `RollupStrategy`, annotate with CDI, select by config. The heterogeneous fleet deployment case uses different strategy IDs at each level. You pay the complexity only at the levels that need it.

**Dynamic children (emergent trees):** when a new child is attached, the platform re-invokes rollup on the parent. The parent's aggregate is always current, even as children appear.

**Children include all statuses.** `RollupContext.children` contains PENDING, ACTIVE, COMPLETED, and FAILED children. Built-in strategies filter to ACTIVE + COMPLETED internally. Custom strategies have full visibility — a strategy that needs "3 of 10 children failed" reads it directly from the children list.

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
    COMPLETED,        // terminal — success
    FAILED,           // terminal — failure
    REACTIVATED,      // COMPLETED/FAILED → ACTIVE (retry)
    ROLLED_BACK       // state moved backward
}
```

### Rollback

The model doesn't prevent backward movement — it records it. `previousState` and `currentState` on the event tell consumers what changed. A step going from "validate" back to "configure" emits a `ROLLED_BACK` event. The orchestrator decided to roll back; the progress model observed it.

### Event Semantics

**CHILD_ATTACHED:** fires on the **parent** (progressId = parent's ID). The child receives a separate CREATED event. Attaching a child via `POST /progress/{id}/children` emits two events: CREATED on the child, CHILD_ATTACHED on the parent. Different entities, different subscribers.

**PENDING → ACTIVE:** the first STATE_UPDATED on a PENDING instance transitions it to ACTIVE. No separate activation event — the status change is visible on the event.

### Rollup Cascade

Rollup is **asynchronous**. When a child emits `STATE_UPDATED`, a CDI observer re-reads all children from the store and re-computes the parent's rollup. If the parent's state changes, the parent emits its own `STATE_UPDATED`, cascading up the tree. Asynchronous propagation means:
- No transaction spanning multiple tree levels
- Concurrent leaf updates don't contend at the parent — they enqueue separate rollup recomputations
- Eventual consistency: a query immediately after a leaf update may return the previous rollup; the next rollup cycle corrects it
- OCC (`@Version`) on the parent **detects** concurrent modifications — `OptimisticLockException` fires when two rollup recomputations race

**Retry on OCC conflict:** the rollup observer retries on `OptimisticLockException` — re-reads the parent, re-reads all children, recomputes, and retries. Bounded at 3 attempts. Without retry, a conflicting rollup recomputation is silently dropped and the parent's state remains stale until the next child event. This is the same retry pattern used by `WorkItemSpawnGroup` for `completedCount` OCC contention.

This matches the engine's milestone evaluation pattern — `MilestoneLifecycleManager` evaluates asynchronously via CDI event observation.

### Event Persistence

Events are persisted to a `progress_event` table via `ProgressEventStore` SPI (following the `AuditEntry`/`AuditEntryStore` pattern from casehub-work). CDI events are the integration surface; the `ProgressEventStore` is the persistence surface. REST event trail endpoints (`GET /progress/{id}/events`) query the store.

The sequence of persisted events is the full history of how progression unfolded, including rollbacks.

---

## 4. REST API

### Reporting (write)

```
POST   /progress                              — create a new instance
PUT    /progress/{id}/state                   — update state
POST   /progress/{id}/complete                — mark completed
POST   /progress/{id}/fail                    — mark failed
POST   /progress/{id}/reactivate              — reactivate (COMPLETED/FAILED → ACTIVE)
POST   /progress/{id}/children                — attach a new child instance
```

Creating an instance requires: `scopeType`, `scopeId`, `shapeType`, initial `state`. Optional: `parentProgressId`, `rollupStrategyId`.

State updates require: `state` conforming to the declared `shapeType`. Shape conformance is validated in core (data integrity) — that's the only validation the progress model does. No structural validation of trees.

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

Anchor queries return all progress instances attached to a scope. A PlanItem with retry iterations returns multiple instances.

### SSE (live)

```
GET    /progress/{id}/stream                  — SSE stream for this subtree
```

Real-time updates as any node in the subtree changes. Events carry enough context that consumers don't need to re-query.

---

## 5. Module Structure

Following casehub-work's pattern: pure Java core, Quarkus layered on top.

**Repository:** `casehubio/progress` — new Git repo, foundation tier.
**Build position:** after `casehub-platform-api`, before `casehub-work` and `casehub-engine`.
**Artifact coordinates:** `io.casehub:casehub-progress-api:0.2-SNAPSHOT` (matching platform version scheme).

```
casehub-progress/
  api/                  — pure Java: ProgressInstance, ProgressStatus,
                          ProgressUpdatedEvent, RollupStrategy SPI,
                          typed shapes
  core/                 — pure Java + Jandex: built-in rollup strategies,
                          rollup computation engine, shape validation
  runtime/              — Quarkus extension: ProgressService, JPA entity,
                          persistence, event emission, rollup observer,
                          SSE broadcaster
  rest/                 — JAX-RS: reporting + query endpoints (opt-in,
                          thin delegate to ProgressService)
  deployment/           — @BuildStep
  persistence-memory/   — in-memory stores for tests
```

### Dependencies

```
platform-api               ← NamedStrategy, shared markers
    ↑
casehub-progress-api       ← progress types + SPIs (pure Java)
    ↑
casehub-progress-core      ← built-in strategies, rollup engine (pure Java)
    ↑
casehub-progress-runtime   ← Quarkus extension (persistence, events, SSE)
casehub-progress-rest      ← JAX-RS endpoints (opt-in)
    ↑
work / engine / blocks / qhorus  ← consumers
```

Consumers depend on `casehub-progress-api` for types and SPIs. Only the host deployment includes the runtime. Remote actors (agents, node installers) use the REST API with zero classpath dependency.

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
    @Column(name = "shape_type", nullable = false) public String shapeType;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false) public JsonNode state;
    @Enumerated(EnumType.STRING) @Column(nullable = false) public ProgressStatus status;
    @Column(name = "rollup_strategy_id") public String rollupStrategyId;
    @Column(name = "created_at", nullable = false) public Instant createdAt;
    @Column(name = "updated_at", nullable = false) public Instant updatedAt;
}
```

`@Version` provides optimistic concurrency control — concurrent updates to the same instance produce `OptimisticLockException`, matching `WorkItemEntity` and `WorkItemSpawnGroup` patterns.

`state` uses `@JdbcTypeCode(SqlTypes.JSON)` for PostgreSQL `jsonb`; H2 `MODE=PostgreSQL` maps it to `TEXT`.

**Event entity:** `ProgressEventEntity` — append-only, following `AuditEntry` pattern.

```java
@Entity
@Table(name = "progress_event")
public class ProgressEventEntity extends PanacheEntityBase {
    @Id public UUID id;
    @Column(name = "tenancy_id", nullable = false) public String tenancyId;
    @Column(name = "progress_id", nullable = false) public UUID progressId;
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
}
```

CDI backend activation follows the four-tier priority ladder per the persistence-backend-cdi-priority protocol.

### ProgressService

The high-level service coordinating all progress operations. Lives in the `runtime` module. Co-deployed consumers (casehub-work, casehub-engine) inject it via CDI. REST endpoints are thin delegates.

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
}
```

Each mutating method: validates shape conformance (delegates to core) → validates status transition → persists via `ProgressInstanceStore` → persists event via `ProgressEventStore` → emits `ProgressUpdatedEvent` CDI event. The rollup observer (separate CDI bean) listens for `ProgressUpdatedEvent` and re-invokes `ProgressService` to update the parent, with OCC retry.

This is the equivalent of `WorkItemService` for casehub-work — the single coordination point that prevents consumers from bypassing validation, event emission, or rollup triggering.

**Flyway migrations:** path `classpath:db/progress/migration/`, starting at V1.

```sql
-- V1__progress_schema.sql
CREATE TABLE progress_instance (
    id                  UUID        NOT NULL,
    version             BIGINT      NOT NULL DEFAULT 0,
    tenancy_id          VARCHAR(255) NOT NULL,
    scope_type          VARCHAR(255) NOT NULL,
    scope_id            VARCHAR(255) NOT NULL,
    parent_progress_id  UUID,
    shape_type          VARCHAR(50)  NOT NULL,
    state               JSONB       NOT NULL,
    status              VARCHAR(20)  NOT NULL,
    rollup_strategy_id  VARCHAR(255),
    created_at          TIMESTAMP   NOT NULL,
    updated_at          TIMESTAMP   NOT NULL,
    CONSTRAINT pk_progress_instance PRIMARY KEY (id)
);

CREATE INDEX idx_progress_scope ON progress_instance (scope_type, scope_id);
CREATE INDEX idx_progress_parent ON progress_instance (parent_progress_id);
CREATE INDEX idx_progress_tenancy ON progress_instance (tenancy_id);

CREATE TABLE progress_event (
    id              UUID        NOT NULL,
    tenancy_id      VARCHAR(255) NOT NULL,
    progress_id     UUID        NOT NULL,
    change_type     VARCHAR(30)  NOT NULL,
    previous_state  JSONB,
    current_state   JSONB,
    status          VARCHAR(20)  NOT NULL,
    occurred_at     TIMESTAMP   NOT NULL,
    CONSTRAINT pk_progress_event PRIMARY KEY (id)
);

CREATE INDEX idx_progress_event_progress ON progress_event (progress_id, occurred_at);
CREATE INDEX idx_progress_event_tenancy ON progress_event (tenancy_id);
```

### SSE Broadcaster

Follows the `WorkItemEventBroadcaster` SPI pattern (C26-C27): `ProgressEventBroadcaster` interface with `LocalProgressEventBroadcaster` (in-process Mutiny `BroadcastProcessor`) as the default, and `PostgresProgressEventBroadcaster` (LISTEN/NOTIFY) for cross-node delivery. Uses a separate PostgreSQL channel (`progress_events`) from work's channel. Cross-node delivery is in scope — the fleet deployment use case requires it.

---

## 6. Milestone Composition

Progress and Milestone compose via events, not coupling.

**Integration path:** `ProgressUpdatedEvent` fires → engine writes progress state into case context at `progress.<scopeType>.<scopeId>` → `CaseContextChangedEvent` fires → `MilestoneLifecycleManager` evaluates expression criteria (existing mechanism). A milestone criterion like `progress.workitem.abc123.state.value >= 80` works with existing expression evaluation.

**Milestone reset:** `MilestoneResetPolicy` SPI lives in the engine:

```java
public interface MilestoneResetPolicy extends NamedStrategy {
    ResetDecision evaluate(MilestoneResetContext context);
}

enum ResetDecision { KEEP, RESET }
```

Default: `KEEP` — once a milestone fires, it stays fired. Opt-in `RESET` with notification event for the emergent tree case (new children appear, aggregate drops below threshold).

**What the progress model needs from the engine:**
- Milestone criteria expressions that can reference progress state paths (e.g. `progress.workitem.abc123.state.value >= 80`)
- A reset policy mechanism so that emergent tree cases (new children appear, aggregate drops below threshold) can optionally re-evaluate milestone completion

How engine#84 is restructured to deliver these is an engine-internal decision.

---

## 7. Integration Points

### casehub-work
- Removes `percentComplete` and `statusNote` from WorkItem (V37 migration reversed). Callers adopt the progress API directly — no backward-compatible shim.
- `WorkItemService.progress()` creates/updates a ProgressInstance anchored by `scopeType="workitem"`, `scopeId=workItemId`
- The old `PUT /workitems/{id}/progress` endpoint is removed. Callers use `PUT /progress/{id}/state` directly.
- **Work spawning:** parent WorkItem's ProgressInstance gets children as WorkItems are spawned. `WorkItemSpawnGroup` continues to own M-of-N completion policy (idempotency, `policyTriggered`, `onThresholdReached`) — that's control, not observation. The progress tree observes completion state; the spawn group controls policy outcomes. A parent WorkItem's ProgressInstance can use the `count-completed` rollup strategy to aggregate child completion, giving the same data as `SpawnGroup.completedCount` through the observation lens.

### casehub-engine
- `MilestoneLifecycleManager` subscribes to `ProgressUpdatedEvent`
- `MilestoneResetPolicy` SPI lives here (engine-internal — references `MilestoneResetContext` and `ResetDecision`)
- PlanItem repeat: each iteration creates a child ProgressInstance
- **Hierarchy construction:** the engine creates ProgressInstances with `parentProgressId` set to mirror PlanItem containment. Hierarchy is always declared via `parentProgressId` — the progress module never walks PlanItem trees. "Inferred hierarchy" means the engine infers the parent-child relationship from PlanItem containment and declares it in the progress model. The progress module only sees `parentProgressId`.

### Anchoring composition

Three mechanisms serve different purposes and compose for the common case:
- `callerRef` on WorkItem: opaque engine→work routing token (`"case:{caseId}/pi:{planItemId}"`). casehub-work never interprets it.
- PlanItem ID: engine-internal identity.
- `scopeType`/`scopeId` on ProgressInstance: platform-level anchor to any entity.

**Common traversal — "progress for this PlanItem":** engine looks up the PlanItem → finds its WorkItem via callerRef → finds the ProgressInstance by `scopeType="workitem"`, `scopeId=workItemId`. The engine can also create a ProgressInstance anchored directly to the PlanItem (`scopeType="planitem"`, `scopeId=planItemId`), bypassing WorkItem entirely. Which path to use depends on whether the progress is conceptually "work progress" (anchor to WorkItem) or "plan progress" (anchor to PlanItem).

### casehub-blocks (blocks#62)
- `ConversationRenderer` accepts progress snapshot alongside reactions
- Subscribes to `ProgressUpdatedEvent`, renders as choreography overlay per topic
- Depends on `casehub-progress-api` only

### qhorus
- Agents report structured progress via REST API
- No special integration — agents are REST clients

---

## 8. Testing Strategy

### Pure Java (api + core)

| What to prove | Approach |
|---|---|
| Typed shapes validate correctly | Percentage bounds, count invariants, step position |
| parentProgressId builds trees | Flat, one-level, deep (4+) trees; query subtree |
| Rollup cascades | 3-level tree, update leaf, verify parent and grandparent recompute |
| Heterogeneous rollup | Leaf=percentage, parent=count, grandparent=percentage; different strategies per level |
| Emergent tree rollup | Add third child mid-flight, verify parent recalculates |
| Rollback event | Forward then backward, verify ROLLED_BACK type and rollup reflects backward state |
| Failed children excluded | Mark child FAILED, verify default rollup ignores it |

### Engine Hierarchy Construction (critical)

```
Given: case with PlanItems A → B → C (A contains B, B contains C)
And: engine creates ProgressInstances with parentProgressId mirroring PlanItem containment
When: leaf C's progress updates
Then: rollup cascades C → B → A via parentProgressId — same mechanism as any declared tree
```

Proves that engine-constructed hierarchy uses the same rollup path as explicitly declared trees.

### Integration (Quarkus)

| What to prove | Approach |
|---|---|
| REST round-trip | Create, update, query via endpoints |
| SSE streaming | Subscribe to subtree, update leaf, verify event arrives |
| Milestone fires on threshold | Progress reaches 85%, milestone with `>= 80` criterion activates |
| Milestone reset policy | Threshold crossed, new child added, aggregate drops; verify KEEP default and RESET opt-in |

### Fleet Deployment Stress Test

4-level tree: fleet → datacenters → nodes → components. Static datacenters, emergent components. Heterogeneous rollup per level. Verify root aggregate accuracy as leaves report asynchronously. Proves the model handles the shape — not the transport.

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

## Scope Boundaries

**In scope:** recording state, nesting, rollup, events, querying, SSE, milestone composition via events.

**Out of scope:** control flow, retry decisions, failure handling, orchestration, structural validation. These capabilities exist in engine, Flow, and work — the progress model observes what they do.

**Deferred:**
- Arbitrary JSON schema shape type — work#307 (migrate to `casehubio/progress` when repo is created)
- Rollback control mechanism — work#308 (migrate to `casehubio/progress` when repo is created)
- Visualisation modes — work#309 (migrate to `casehubio/progress` when repo is created)
