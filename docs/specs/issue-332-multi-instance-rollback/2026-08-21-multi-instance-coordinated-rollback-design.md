# Multi-Instance Coordinated Rollback (Subtree-Wide Undo)

**Date:** 2026-08-21
**Status:** Draft
**Issue:** casehubio/work#332
**Parent epic:** #92 (Distributed WorkItems — clustering + federation)
**Foundation:** #308 (single-instance rollback — shipped via #329)
**Decisions:** `specs/issue-332-multi-instance-rollback/decisions.md`

## Context

The progress model (#237) provides tree-structured progress tracking with rollup cascading child state changes upward to parents. The progress enhancements (#329) added single-instance rollback: `rollback()` walks an instance's event trail backward, `rollbackToEvent()` jumps to a specific event's state, and `getSnapshots()` projects the state history. Both bypass `rollbackPolicy` since they're deliberate undo actions.

Single-instance rollback has no tree awareness. Rolling back a parent doesn't affect its children. Rolling back multiple nodes in a subtree requires N independent calls with no coordination — intermediate states are visible, rollup cascades fire independently for each child, and OCC contention under fan-in is a practical failure mode (retry capped at 3 attempts).

This design extends rollback to coordinated subtree operations: roll back all descendants of a node to their state at a given point in time, suppress individual rollup cascades during the operation, then recompute rollup bottom-up in a single pass.

**Scope boundary:** This is coordinated state rollback within the progress observer model. Full saga compensation (compensating bindings, COMPENSATING/COMPENSATED states, cross-repo compensation orchestration) remains in #238.

---

## 1. API Types (progress-api)

### SubtreeRollbackResult

```java
public record SubtreeRollbackResult(
    UUID operationId,
    UUID rootId,
    Instant targetTimestamp,
    List<NodeRollbackOutcome> outcomes
) {}
```

### NodeRollbackOutcome

```java
public record NodeRollbackOutcome(
    UUID progressId,
    Outcome outcome,
    String reason,
    JsonNode previousState,
    JsonNode restoredState,
    boolean policyBypassed
) {
    public enum Outcome { ROLLED_BACK, SKIPPED, FAILED }
}
```

| Field | Description |
|-------|-------------|
| `progressId` | The instance that was processed |
| `outcome` | `ROLLED_BACK` (state restored), `SKIPPED` (not applicable), `FAILED` (error) |
| `reason` | `null` for ROLLED_BACK. For SKIPPED: `"created after target timestamp"`, `"no event history before target"`, `"already at target state"`. For FAILED: error description. |
| `previousState` | State before rollback. `null` if SKIPPED. |
| `restoredState` | State after rollback. `null` if SKIPPED or FAILED. |
| `policyBypassed` | `true` if the node had `rollbackPolicy = "denied"` and was bypassed (D5). Surfaces that domain-specific protections were overridden. |

### ProgressUpdatedEvent — new field

```java
public record ProgressUpdatedEvent(
    UUID id,
    UUID progressId,
    String tenancyId,
    String scopeType,
    String scopeId,
    UUID parentProgressId,
    UUID rootProgressId,
    String shapeType,
    JsonNode previousState,
    JsonNode currentState,
    ProgressStatus status,
    ProgressChangeType changeType,
    Instant timestamp,
    UUID operationId          // NEW — null for normal operations
)
```

`operationId` is a generic correlation field. When non-null, `RollupObserver` skips its async rollup recomputation for this event — the coordinated operation handles rollup explicitly. Consumers combine `operationId` with `changeType` for correlation and semantics (e.g., `ROLLED_BACK` + `operationId` = coordinated rollback; `STATE_UPDATED` + `operationId` = rollup consequence of a coordinated operation). Named generically (`operationId`, not `coordinatedRollbackId`) because future coordinated operations (bulk complete, bulk fail) will use the same suppression mechanism.

Breaking change: all callers constructing `ProgressUpdatedEvent` gain a parameter. Pre-release project — acceptable.

### ProgressEventStore — new SPI method

```java
Optional<ProgressUpdatedEvent> findLastEventAtOrBefore(UUID progressId, Instant cutoff);
```

Returns the most recent event for the given progress instance at or before the cutoff instant (`<=` semantics). Returns `Optional.empty()` if no events exist at or before the cutoff.

**Why at-or-before (`<=`) and not strict before (`<`):** The event-based rollback target resolves to `event.timestamp()`. With strict `<`, the node that the anchor event belongs to would get the event *before* the anchor — one step behind the intended target. With `<=`, it correctly returns the anchor event itself.

**JPA implementation:** `WHERE progress_id = :id AND occurred_at <= :cutoff ORDER BY occurred_at DESC, id DESC LIMIT 1`. The `id DESC` tiebreaker handles events with identical timestamps deterministically.

**In-memory implementation:** Stream filter + max by timestamp.

### ProgressInstanceStore — new SPI method

```java
List<ProgressInstance> findDescendantsOf(UUID parentId);
```

Returns all transitive descendants of the given node (children, grandchildren, etc.). Does not include the node itself.

**JPA implementation:** Recursive CTE:
```sql
WITH RECURSIVE descendants AS (
    SELECT * FROM progress_instance WHERE parent_progress_id = :parentId
    UNION ALL
    SELECT pi.* FROM progress_instance pi
    JOIN descendants d ON pi.parent_progress_id = d.id
)
SELECT * FROM descendants
```

**In-memory implementation:** Recursive iteration (same algorithm as the existing `ProgressResource.collectDescendants()`).

---

## 2. SubtreeRollbackService (progress-runtime)

New `@ApplicationScoped` service in `io.casehub.work.progress.runtime.service`.

### Dependencies

```java
@Inject ProgressService progressService;
@Inject ProgressInstanceStore instanceStore;
@Inject ProgressEventStore eventStore;
@Inject RollupEngine rollupEngine;
```

### Public API

```java
@Transactional(Transactional.TxType.NOT_SUPPORTED)
public SubtreeRollbackResult rollbackSubtree(UUID rootId, Instant targetTimestamp) { ... }

public SubtreeRollbackResult rollbackSubtreeToEvent(UUID rootId, UUID eventId) {
    ProgressUpdatedEvent event = eventStore.findById(eventId)
        .orElseThrow(() -> new IllegalArgumentException("Event not found: " + eventId));
    return rollbackSubtree(rootId, event.timestamp());
}
```

`rollbackSubtreeToEvent` resolves the event's timestamp and delegates to `rollbackSubtree`. The anchor event must exist; it does not need to belong to the root (it can be any event in the subtree).

`@Transactional(NOT_SUPPORTED)` on `rollbackSubtree` ensures no ambient transaction wraps the entire operation. Each node's rollback runs in its own transaction (D13).

### Algorithm

```
rollbackSubtree(rootId, targetTimestamp):
  1. operationId = UUID.randomUUID()
  2. root = instanceStore.get(rootId) — fail if not found
  3. descendants = instanceStore.findDescendantsOf(rootId)
  4. allNodes = [root] + descendants
  5. Partition allNodes into:
     - leafNodes: rollupStrategyId == null (no rollup — state is reported directly)
     - rollupNodes: rollupStrategyId != null (state derived from children)
  
  // Phase 1: Roll back leaf nodes only
  6. outcomes = []
     for each node in leafNodes:
       if node.createdAt > targetTimestamp:
         outcomes.add(SKIPPED, "created after target timestamp")
         continue
       try:
         result = progressService.rollbackToTimestamp(node.id, targetTimestamp, operationId)
         bypassed = "denied".equalsIgnoreCase(node.rollbackPolicy())
         outcomes.add(ROLLED_BACK, previousState, restoredState, bypassed)
       catch (IllegalStateException e):  // no event history before target
         outcomes.add(SKIPPED, e.getMessage())
       catch (Exception e):
         outcomes.add(FAILED, e.getMessage())
  
  // Phase 2: Bottom-up rollup recomputation (rollup nodes only)
  7. Group rollupNodes by depth (distance from root)
     Skip rollup nodes created after targetTimestamp (SKIPPED in result)
  8. For each depth level, deepest first:
       For each rollupNode at this depth:
         if rollupNode.createdAt > targetTimestamp:
           outcomes.add(SKIPPED, "created after target timestamp")
           continue
         children = instanceStore.findByParentProgressId(rollupNode.id)
         preTargetChildren = children.filter(c -> c.createdAt <= targetTimestamp)
         result = progressService.applyRollupState(rollupNode.id, preTargetChildren, operationId)
         if result != null:  // state changed
           outcomes.add(ROLLED_BACK, previousState, result.state(), false)
         else:
           outcomes.add(SKIPPED, "already at target state")
  
  9. return SubtreeRollbackResult(operationId, rootId, targetTimestamp, outcomes)
```

**Why rollup nodes are skipped in Phase 1 (R1-02):** Rolling back a rollup parent to its historical state in Phase 1 is wasted work — Phase 2 immediately recomputes the parent's state from its children's post-rollback states, overwriting the Phase 1 result. With partial failures (some children FAILED), the Phase 2 recomputed value differs from the historical state. Phase 2 is always the correct final authority for rollup nodes. Skipping them in Phase 1 avoids an unnecessary transaction, OCC version increment, and misleading `ROLLED_BACK` event that would be immediately superseded.

### Depth computation

Depth is derived during the initial tree traversal. The root is depth 0, its direct children are depth 1, etc. This is computed from the `parentProgressId` chain using the already-loaded descendants list — no additional queries.

### Post-target children in rollup (D4)

During Phase 2, each parent's rollup uses only children with `createdAt <= targetTimestamp`. Post-target children are excluded. This ensures the parent's rolled-back rollup reflects the tree shape at the target time, not a mixed-era aggregate. After the coordinated rollback completes, subsequent normal operations (child state updates) will re-include all children via the standard `RollupObserver` async cascade.

### Transaction scope (D13)

Each node's rollback (`progressService.rollbackToTimestamp`) runs in its own transaction. The service method is `@Transactional(NOT_SUPPORTED)` to prevent an ambient transaction from wrapping the entire operation. If a node fails (OCC conflict, missing history), it's reported as FAILED and the operation continues. Partial rollback state is observable mid-operation — consistent with the observer model's best-effort semantics (D3).

### OCC handling (D14)

`OptimisticLockException` during a node's rollback is caught and reported as FAILED with reason `"concurrent modification"`. No retry — the operator can re-invoke if needed. This differs from `RollupObserver.recomputeWithRetry()` which retries because it's a fire-and-forget observer context. Subtree rollback is an explicit operation with a result report where failure is reported, not hidden.

---

## 3. ProgressService Changes

### Transaction model

`ProgressService` is a plain Java class constructed via the Quarkus extension build-step processor — not a standard `@ApplicationScoped` CDI bean. It has no `@Transactional` annotations. The caller provides transaction context: `RollupObserver.recomputeWithRetry()` is `@Transactional`, and REST endpoints rely on the Quarkus JAX-RS transaction integration.

The new `rollbackToTimestamp` and `applyRollupState` methods are called by `SubtreeRollbackService`, which uses `@Transactional(NOT_SUPPORTED)` to suspend ambient transactions. To ensure each per-node call has its own transaction boundary, `SubtreeRollbackService` wraps each call in an explicit `@Transactional(REQUIRES_NEW)` helper method. This keeps `ProgressService` unchanged as a plain Java class — the transaction boundary is the caller's responsibility.

### New public method — rollbackToTimestamp

```java
public ProgressInstance rollbackToTimestamp(UUID id, Instant target, UUID operationId) {
    ProgressInstance instance = requireInstance(id);
    ProgressUpdatedEvent event = eventStore.findLastEventAtOrBefore(id, target)
        .orElseThrow(() -> new IllegalStateException("No event history at or before target"));

    JsonNode targetState = event.currentState();
    if (targetState.equals(instance.state())) {
        return null; // already at target state — no-op, caller reports as SKIPPED
    }

    return applyRollbackState(instance, targetState, operationId);
}
```

Returns `null` for no-op (already at target state). The caller (`SubtreeRollbackService`) reports this as `SKIPPED` with reason `"already at target state"` — not `ROLLED_BACK` with identical previous/restored states.

### New public method — applyRollupState

```java
public ProgressInstance applyRollupState(UUID id, List<ProgressInstance> children, UUID operationId) {
    ProgressInstance instance = requireInstance(id);
    JsonNode previousState = instance.state();
    JsonNode newState = rollupEngine.recompute(instance, children);

    if (newState == null || !rollupEngine.hasStateChanged(previousState, newState)) {
        return null; // no change — caller reports as SKIPPED
    }

    ProgressInstance updated = withState(instance, newState, instance.status());
    instanceStore.put(updated);
    emitEvent(updated, previousState, ProgressChangeType.STATE_UPDATED, operationId);
    return updated;
}
```

Centralizes the rollup recomputation pipeline within `ProgressService`. Phase 2 of `SubtreeRollbackService` calls this instead of reimplementing the persist-emit pipeline. This ensures consistent event emission through the `eventEmitter` Consumer (which may include broadcasting to `ProgressEventBroadcaster`), avoiding the divergence that would occur if `SubtreeRollbackService` emitted events directly via CDI.

### applyRollbackState — operationId parameter

The existing private `applyRollbackState(ProgressInstance, JsonNode)` gains an `operationId` parameter:

```java
private ProgressInstance applyRollbackState(ProgressInstance instance, JsonNode newState, UUID operationId) {
    validateShape(instance.shapeType(), newState, instance.definition());
    ProgressStatus newStatus = instance.status();
    if (newStatus == ProgressStatus.PENDING) {
        newStatus = ProgressStatus.ACTIVE;
    }
    ProgressInstance updated = withState(instance, newState, newStatus);
    instanceStore.put(updated);
    emitEvent(updated, instance.state(), ProgressChangeType.ROLLED_BACK, operationId);
    return updated;
}
```

The existing `rollback()` and `rollbackToEvent()` pass `null` for `operationId` — backward compatible.

### emitEvent — operationId parameter

```java
private void emitEvent(ProgressInstance instance, JsonNode previousState,
                       ProgressChangeType changeType, UUID operationId) {
    ProgressUpdatedEvent event = new ProgressUpdatedEvent(
        UUID.randomUUID(), instance.id(), instance.tenancyId(),
        instance.scopeType(), instance.scopeId(),
        instance.parentProgressId(), instance.rootProgressId(),
        instance.shapeType(), previousState, instance.state(),
        instance.status(), changeType, Instant.now(), operationId);
    eventStore.append(event);
    eventEmitter.accept(event);
}
```

Uses `eventEmitter.accept()` (the constructor-injected Consumer), not direct CDI `fireAsync`. Existing callers pass `null` for `operationId`.

---

## 4. RollupObserver Changes

```java
void onProgressUpdated(@ObservesAsync ProgressUpdatedEvent event) {
    if (event.parentProgressId() == null) {
        return;
    }
    if (event.operationId() != null) {
        return; // coordinated operation handles its own rollup
    }
    tenantContextRunner.runInTenantContext(event.tenancyId(), () ->
            recomputeWithRetry(event.parentProgressId(), event.tenancyId()));
}
```

One `if` check added. Events with a non-null `operationId` are skipped — the coordinated operation is responsible for rollup.

---

## 5. REST Changes (progress-rest)

### New endpoint

```
POST /progress/{id}/rollback/subtree?timestamp={instant}
POST /progress/{id}/rollback/subtree?toEvent={eventId}
```

At least one query parameter required. Both provided → 400. Neither provided → 400.

Returns `SubtreeRollbackResult` as JSON.

```java
@POST
@Path("/{id}/rollback/subtree")
public Response rollbackSubtree(
        @PathParam("id") UUID id,
        @QueryParam("timestamp") String timestamp,
        @QueryParam("toEvent") UUID toEventId) {

    if (timestamp != null && toEventId != null) {
        return Response.status(Response.Status.BAD_REQUEST)
            .entity("timestamp and toEvent are mutually exclusive").build();
    }

    SubtreeRollbackResult result;
    if (toEventId != null) {
        result = subtreeRollbackService.rollbackSubtreeToEvent(id, toEventId);
    } else if (timestamp != null) {
        result = subtreeRollbackService.rollbackSubtree(id, Instant.parse(timestamp));
    } else {
        return Response.status(Response.Status.BAD_REQUEST)
            .entity("timestamp or toEvent required").build();
    }
    return Response.ok(result).build();
}
```

### ProgressResource — remove collectDescendants

`collectDescendants()` is replaced by `instanceStore.findDescendantsOf()`. The `getTree()` method calls the store directly.

---

## 6. Rollup Recompute Event Semantics (D15)

Events emitted during the Phase 2 rollup recomputation use `changeType = STATE_UPDATED`, not `ROLLED_BACK`. Rollup is a derivative computation — the engine aggregates children's current (post-rollback) states. The parent was never necessarily at this exact rollup state before. `STATE_UPDATED` accurately describes what happened.

SSE consumers distinguish:
- `ROLLED_BACK` + `operationId` → direct rollback of a leaf node (Phase 1)
- `STATE_UPDATED` + `operationId` → rollup recomputation of a parent node (Phase 2)
- `ROLLED_BACK` + `null operationId` → standalone single-instance rollback
- `STATE_UPDATED` + `null operationId` → normal state update or standard rollup

---

## 7. Flyway Migration

```sql
-- V7003__progress_operation_id.sql
ALTER TABLE progress_event ADD COLUMN operation_id UUID;
CREATE INDEX idx_progress_event_operation ON progress_event (operation_id) WHERE operation_id IS NOT NULL;
```

Partial index on `operation_id` — only indexes non-null values (coordinated operations). Normal events (null) don't bloat the index.

No changes to `progress_instance` table.

---

## 8. Post-Target Children — Documentation Contract

**When a subtree is rolled back to timestamp T:**

Nodes with `createdAt > T` (created after the target) are **skipped** — they remain in the tree in their current state but are **excluded from the rolled-back rollup recomputation**. The parent's rolled-back state reflects only children that existed at time T.

**The orchestrator is responsible for deciding the fate of post-target nodes.** Options available via existing APIs:
- `POST /progress/{id}/fail` — fail the node
- `POST /progress/{id}/complete` — complete it regardless
- Leave as-is — it will participate in subsequent normal rollup cascades

**After the coordinated rollback completes,** the next normal child state update on any node in the subtree triggers the standard `RollupObserver` async cascade, which includes all children (including post-target ones) in its recomputation. The rolled-back rollup state is a point-in-time snapshot — ongoing operations restore standard rollup behavior.

---

## 9. Testing Strategy

### Unit (progress-core, pure Java)

| What to prove | Approach |
|---|---|
| Depth computation from flat descendant list | 3-level tree, verify depth assignments |
| Post-target child filtering | Mix of pre/post-target children, verify correct partition |

### Service (progress-runtime)

| What to prove | Approach |
|---|---|
| Timestamp rollback — all nodes rolled back | 3-node tree (root + 2 children), update all, rollback to mid-point, verify all states restored |
| Event-based rollback delegates to timestamp | Rollback to specific event, verify same result as timestamp using that event's instant |
| Post-target nodes skipped | Create child after target time, rollback, verify SKIPPED in result |
| No-history nodes skipped | Node created at T, rollback to T-1, verify SKIPPED with reason |
| Rollup suppression | Roll back 3 children, verify RollupObserver did NOT fire during operation |
| Bottom-up recompute | 3-level tree, rollback all, verify root rollup reflects rolled-back leaf states |
| Post-target children excluded from rollup | Parent with 2 children (one pre-target, one post-target), rollback, verify rollup only includes pre-target child |
| Policy bypassed and flagged | Node with rollbackPolicy=denied, verify rolled back and policyBypassed=true |
| OCC failure reported | Simulate concurrent modification, verify FAILED in result with reason |
| Per-node transaction isolation | Node 2 fails, verify node 1 was committed and node 3 still processed |
| No-op when already at target state | Node whose state matches target, verify SKIPPED with reason "already at target state" — not ROLLED_BACK |
| Step-shaped descendants rolled back | Subtree with step-shaped child, rollback, verify rolled-back step state passes shape validation and DAG dependency constraints |
| operationId correlates all events | Rollback subtree, query events, verify all share the same operationId |

### Integration (Quarkus, REST round-trip)

| What to prove | Approach |
|---|---|
| `POST /rollback/subtree?timestamp=` | Full REST round-trip, verify SubtreeRollbackResult JSON shape |
| `POST /rollback/subtree?toEvent=` | Event-based REST round-trip |
| Both params → 400 | Provide both timestamp and toEvent |
| Neither param → 400 | Omit both |
| SSE consumer sees ROLLED_BACK + operationId | Subscribe to subtree SSE, trigger rollback, verify events |
| SSE consumer sees STATE_UPDATED + operationId for rollup | Verify rollup recompute events arrive with correct changeType |
| findDescendantsOf replaces collectDescendants | getTree endpoint still works correctly |

### SPI (store implementations)

| What to prove | Approach |
|---|---|
| findLastEventAtOrBefore — at-or-before semantics | Event at exact cutoff returned (not skipped) |
| findLastEventAtOrBefore — no event before cutoff | Returns empty |
| findLastEventAtOrBefore — timestamp tiebreaker | Two events at same instant, verify deterministic ordering |
| findDescendantsOf — 3-level tree | Returns all descendants, not the root itself |
| findDescendantsOf — mid-tree node | Returns only the targeted subtree, not siblings |
| findDescendantsOf — leaf node | Returns empty list |

---

## 10. Module Impact

| Module | Change |
|--------|--------|
| `progress-api` | New: `SubtreeRollbackResult`, `NodeRollbackOutcome`. Modified: `ProgressUpdatedEvent` (+operationId), `ProgressEventStore` (+findLastEventAtOrBefore), `ProgressInstanceStore` (+findDescendantsOf) |
| `progress-core` | No changes |
| `progress-runtime` | New: `SubtreeRollbackService`. Modified: `ProgressService` (+rollbackToTimestamp, +applyRollupState, applyRollbackState gains operationId, emitEvent gains operationId and uses eventEmitter), `RollupObserver` (+operationId check), `JpaProgressInstanceStore` (+findDescendantsOf with CTE), `JpaProgressEventStore` (+findLastEventAtOrBefore), `ProgressEventEntity` (+operationId column), `JpaProgressEventStore.toEntity()`/`toDomain()` (+operationId mapping) |
| `progress-rest` | New: `POST /{id}/rollback/subtree`. Modified: `getTree` uses store's findDescendantsOf, remove `collectDescendants` |
| `progress-memory` | Modified: `InMemoryProgressInstanceStore` (+findDescendantsOf), `InMemoryProgressEventStore` (+findLastEventAtOrBefore) |
| `progress-deployment` | No changes expected |
| Flyway | New: `V7003__progress_operation_id.sql` |

---

## 11. Scope Boundaries

**In scope:**
- Subtree rollback by timestamp and by event
- Rollup suppression via operationId on ProgressUpdatedEvent
- Bottom-up rollup recomputation with post-target child exclusion
- Best-effort result reporting with skip/fail reasons
- findDescendantsOf and findLastEventAtOrBefore SPI methods
- Flyway migration for operationId column

**Out of scope:**
- Full saga compensation (#238)
- Compensation callbacks or SPI
- Node detachment or cancellation during rollback (orchestrator's job)
- Subtree-level operations beyond rollback (bulk complete, bulk fail — future work using the same operationId mechanism)

---

## 12. Issue Updates

Update #332 description: remove "saga-style compensation across nodes" from scope. Replace with "coordinated rollback across nodes." Reference #238 for full saga compensation.

Update #92 epic scope to include #332. All other children (#93, #155, #94, #96, #95, #97) are closed. Closing #332 makes #92 fully closeable.

---

## References

- `progress-runtime/src/main/java/io/casehub/work/progress/runtime/service/ProgressService.java` — single-instance rollback (lines 178-237)
- `progress-runtime/src/main/java/io/casehub/work/progress/runtime/event/RollupObserver.java` — async rollup cascade
- `progress-core/src/main/java/io/casehub/work/progress/validation/RollbackDetector.java` — per-shape rollback detection
- `progress-rest/src/main/java/io/casehub/work/progress/rest/ProgressResource.java` — REST endpoints, collectDescendants (lines 206-213)
- `progress-api/src/main/java/io/casehub/work/progress/spi/ProgressInstanceStore.java` — store SPI
- `progress-api/src/main/java/io/casehub/work/progress/spi/ProgressEventStore.java` — event store SPI
- `specs/issue-237-structured-progress/2026-08-01-progress-model-design.md` — base progress model
- `specs/issue-329-progress-enhancements/2026-08-04-progress-enhancements-design.md` — single-instance rollback design
- `specs/issue-333-progress-api-docs-spi-fix/2026-08-13-progress-docs-spi-extraction-design.md` — recent SPI extraction
- `docs/protocols/casehub/async-event-tenant-context-propagation.md` — @ObservesAsync tenant context protocol
- Issue #238 — saga compensation (out of scope, referenced for boundary)
- Issue #92 — parent epic (Distributed WorkItems)
- `specs/issue-332-multi-instance-rollback/decisions.md` — all 15 design decisions with rationale
