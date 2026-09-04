# Subprocess Spawning — Design Spec
**Date:** 2026-04-23  
**Status:** FINAL — reviewed against real casehub-engine + quarkus-ledger  
**Epic:** #105

---

## Positioning

quarkus-work is the **primitive layer**. It provides the mechanics of spawning child WorkItems and fires events. It makes no decisions about what those events mean or what should happen next. That is CaseHub's job.

See `docs/architecture/LAYERING.md` for the full boundary definition.

---

## What quarkus-work provides for subprocess spawning

### 1. `callerRef` on WorkItem

A nullable opaque string field added to `WorkItemEntity`:

```java
@Column(name = "caller_ref", length = 512, nullable = true)
public String callerRef;
```

quarkus-work stores it and echoes it in every `WorkItemLifecycleEvent`. It never interprets it. CaseHub embeds its routing key here (e.g. `caseId:planItemId`) so it can route child completion back to the right `PlanItem` without a query. This mirrors `CaseInstance.parentPlanItemId` in casehub-engine.

`callerRef` is also present in:
- `WorkItemCreateRequest` (passed through at spawn time)
- Every `WorkItemLifecycleEvent` on the child WorkItem
- The spawn response body (echoed per child)

### 2. `WorkEventType.SPAWNED`

New value added to `WorkEventType` in `quarkus-work-api`. Fired on the parent WorkItem when children are spawned. Allows `FilterRegistryEngine`, ledger, and `EscalationPolicy` observers to react to spawn events.

### 3. `SpawnPort` SPI in `quarkus-work-api`

```java
public interface SpawnPort {
    SpawnResult spawn(SpawnRequest request);
    void cancelGroup(UUID groupId, boolean cascadeChildren);
}

public record SpawnRequest(
    UUID parentId,
    String idempotencyKey,
    List<ChildSpec> children
) {}

public record ChildSpec(
    UUID templateId,
    String callerRef,        // opaque, stored on spawned WorkItem
    Map<String, Object> overrides
) {}

public record SpawnResult(
    UUID groupId,
    List<SpawnedChild> children
) {}

public record SpawnedChild(
    UUID workItemId,
    String callerRef
) {}
```

### 4. Caller-driven spawn API

```
POST /workitems/{parentId}/spawn
```

```json
{
  "idempotencyKey": "case-loan-123-stage-review-attempt-1",
  "children": [
    { "templateId": "credit-check-template",     "callerRef": "case:loan-123/pi:credit-1" },
    { "templateId": "fraud-check-template",       "callerRef": "case:loan-123/pi:fraud-2",
      "overrides": { "candidateGroups": "fraud-team" } },
    { "templateId": "compliance-check-template",  "callerRef": "case:loan-123/pi:compliance-3" }
  ]
}
```

Response:

```json
{
  "groupId": "uuid",
  "children": [
    { "workItemId": "uuid", "callerRef": "case:loan-123/pi:credit-1" },
    { "workItemId": "uuid", "callerRef": "case:loan-123/pi:fraud-2" },
    { "workItemId": "uuid", "callerRef": "case:loan-123/pi:compliance-3" }
  ]
}
```

CaseHub calls this explicitly when a binding fires. quarkus-work creates the children, wires PART_OF, stores `callerRef`, fires `WorkEventType.SPAWNED` on the parent, and returns. It does nothing further.

### 5. `WorkItemSpawnGroup` — minimal tracking entity

Exists solely for idempotency and group membership queries. No state machine. No completion policy. No status.

```
WorkItemSpawnGroup {
  id:               UUID
  parentId:         UUID
  idempotencyKey:   String (unique constraint per parent)
  createdAt:        Instant
}
```

Child membership is derived from the PART_OF graph — no childIds column. The group record proves "this spawn happened" for idempotency purposes.

### 6. Group management endpoints

```
GET    /workitems/{id}/spawn-groups          list groups spawned from this parent
GET    /spawn-groups/{id}                    group record + PART_OF children
DELETE /workitems/{id}/spawn-groups/{gid}    cancel group
       ?cancelChildren=true                  also cancel all PENDING children atomically
```

CaseHub uses `DELETE ?cancelChildren=true` when `StageTerminatedEvent` fires — avoids O(N) individual cancel calls.

### 7. Ledger wiring

When children are spawned:
- Parent gets a `LedgerEntry` with `WorkEventType.SPAWNED`
- Each child's CREATED `LedgerEntry` carries `causedByEntryId` pointing to the parent's SPAWNED entry

This creates the causal chain: parent SPAWNED → child CREATED, visible in PROV-DM export as `prov:wasDerivedFrom`.

---

## What is NOT in quarkus-work

These are orchestration concerns. They belong in CaseHub or the calling application:

| Concern | Owner |
|---|---|
| When to spawn children | CaseHub (binding fires) |
| Which children to spawn | CaseHub (CasePlanModel) |
| Template-driven auto-spawn | Not in quarkus-work |
| Completion rollup | CaseHub (`Stage.requiredItemIds` + autocomplete) |
| Parent auto-completion | CaseHub |
| What REJECTED means for the group | CaseHub (goal evaluation) |
| Activation conditions per child | CaseHub (blackboard) |
| SpawnGroupCompletedEvent | Not in quarkus-work |

If a standalone application (no CaseHub) wants completion rollup, it writes a CDI observer on `WorkItemLifecycleEvent`. That is ten lines of code. quarkus-work does not provide it.

---

## Terminal status mapping (for CaseHub adapter)

| WorkItemStatus | CaseHub PlanItemStatus |
|---|---|
| COMPLETED | COMPLETED |
| REJECTED | FAULTED |
| CANCELLED | CANCELLED |
| EXPIRED | FAULTED |
| ESCALATED | FAULTED |

This mapping lives in `quarkus-work-casehub` (future adapter module), not in quarkus-work.

---

## REST API summary

| Method | Path | Description |
|---|---|---|
| `POST` | `/workitems/{id}/spawn` | Spawn children, PART_OF links, fire SPAWNED event |
| `GET` | `/workitems/{id}/spawn-groups` | List spawn groups for parent |
| `GET` | `/spawn-groups/{id}` | Group record + children via PART_OF |
| `DELETE` | `/workitems/{id}/spawn-groups/{gid}` | Cancel group; `?cancelChildren=true` cascades to PENDING children |
| `GET` | `/workitems/{id}/children` | (existing) PART_OF children — unchanged |

---

## Test strategy

### Unit tests (no Quarkus, no DB)

**SpawnRequestBuilder:**
- Template override merging: child overrides win over template defaults field-by-field
- `callerRef` preserved exactly — no transformation, no trimming
- Null `templateId` → exception before any persistence
- Empty children list → exception before any persistence

**SpawnPort implementation:**
- Happy path: request → correct `WorkItemCreateRequest` objects produced per child
- `callerRef` stored on each created WorkItem
- `idempotencyKey` uniqueness: second call same key+parent → returns existing group, no new children
- PART_OF link created child→parent for each child
- `WorkEventType.SPAWNED` fired on parent after all children created

**callerRef contract:**
- Unicode, special chars, 512-char strings round-trip unchanged
- Null `callerRef` on one child → that child's events carry null; others carry their own
- `callerRef` present on every terminal lifecycle event (COMPLETED, REJECTED, CANCELLED, EXPIRED)

**Cascade cancellation logic:**
- `cancelChildren=false` → group record only, children untouched
- `cancelChildren=true` → PENDING children cancelled, IN_PROGRESS children untouched
- Already-cancelled group → idempotent no-op

### Integration tests (@QuarkusTest, H2)

**Spawn API — happy paths:**
- `POST /workitems/{id}/spawn` → 201, children PENDING, PART_OF links verified, `callerRef` on each child, groupId returned
- Response echoes `callerRef` per child matching request
- `GET /workitems/{id}/children` returns spawned children
- `GET /spawn-groups/{id}` returns group + children

**Spawn API — error paths:**
- Invalid `templateId` → 422
- Non-existent parent → 404
- Terminal parent (COMPLETED, CANCELLED) → 409
- Duplicate `idempotencyKey` on same parent → 200 with existing group (idempotent, no new children)
- Empty children → 422
- `callerRef` > 512 chars → 422

**Cascade cancellation:**
- `DELETE ?cancelChildren=false` → group only, children unchanged
- `DELETE ?cancelChildren=true` → PENDING children CANCELLED, IN_PROGRESS child unchanged
- `DELETE` on non-existent group → 404

**WorkEventType.SPAWNED:**
- After spawn, parent audit trail contains SPAWNED entry
- Ledger: parent SPAWNED entry created
- Ledger: each child CREATED entry has `causedByEntryId` = parent SPAWNED entry ID

**callerRef round-trip:**
- `callerRef` set on spawn → present on `GET /workitems/{childId}` → present in lifecycle event

### End-to-end tests (scenario-style, @QuarkusTest)

**Scenario 1 — CaseHub pattern (caller-driven):**
1. Create parent WorkItem
2. `POST /workitems/{id}/spawn` with 3 children, distinct `callerRef` per child
3. Verify 3 children PENDING, `callerRef` on each, PART_OF links correct
4. Complete each child; capture `WorkItemLifecycleEvent` per child
5. Verify each event carries correct `callerRef`
6. No group-level event fired (quarkus-work makes no completion decision)

**Scenario 2 — Idempotency under retry:**
1. `POST /workitems/{id}/spawn` with `idempotencyKey=key-1` → 201, 3 children created
2. Same POST with `idempotencyKey=key-1` → 200, same groupId, no new children
3. Child count still 3, no duplicate PART_OF links

**Scenario 3 — Cascade cancellation:**
1. Spawn 3 children
2. Assign child 1 (IN_PROGRESS), leave 2 and 3 PENDING
3. `DELETE ?cancelChildren=true`
4. Children 2+3 CANCELLED; child 1 still IN_PROGRESS
5. Each cancellation fires `WorkItemLifecycleEvent(CANCELLED)` with `callerRef`

**Scenario 4 — Ledger causal chain:**
1. Spawn 3 children
2. Fetch ledger entries for parent → SPAWNED entry present
3. Fetch ledger entries for each child → CREATED entry has `causedByEntryId` = parent SPAWNED entry
4. PROV traversal: `findCausedBy(parentSpawnedEntryId)` returns all 3 child CREATED entries

**Scenario 5 — PART_OF graph integrity:**
1. Spawn 3 children from parent A
2. Spawn 2 grandchildren from child 1 (nested spawn)
3. `GET /workitems/child-1/children` → returns 2 grandchildren
4. `GET /workitems/parent-A/children` → returns 3 children only (not grandchildren)
5. Cyclic PART_OF guard: attempt to make parent-A a child of grandchild → 409

### Robustness tests

- **DB failure mid-spawn**: transaction rolled back, no orphaned PART_OF links, no partial group record
- **Child template deleted after spawn**: existing WorkItems unaffected (no runtime FK to template)
- **Spawn observer throws**: error logged, parent WorkItem state unchanged, no partial state
- **Duplicate concurrent spawn** (same idempotencyKey, race): exactly one group created (DB unique constraint)
- **`causedByEntryId` when ledger module absent**: spawn succeeds; ledger wiring skipped gracefully

### Correctness tests

**PART_OF direction:**
- Source = child, target = parent (consistent with existing `WorkItemRelation` convention)

**callerRef fidelity:**
- Empty string stored as empty string (not null)
- Null stored as null
- 512-char string stored and retrieved intact

**Idempotency boundary:**
- Same `idempotencyKey`, different parent → creates new group (key is scoped per parent)
- Different `idempotencyKey`, same parent → creates new group

---

## Model changes summary

| Artifact | Change |
|---|---|
| `WorkItemEntity` | Add `caller_ref VARCHAR(512)` (nullable) |
| `WorkItemCreateRequest` | Add `callerRef` field (optional) |
| `WorkItemLifecycleEvent` | `callerRef` accessible via source WorkItem |
| `WorkEventType` | Add `SPAWNED` |
| `WorkItemSpawnGroup` | New entity: `id`, `parentId`, `idempotencyKey`, `createdAt` |
| Flyway | Next migration: `caller_ref` column + `work_item_spawn_group` table |
| `quarkus-work-api` | Add `SpawnPort`, `SpawnRequest`, `ChildSpec`, `SpawnResult`, `SpawnedChild` |

---

## What is explicitly not in scope

- Completion rollup / `SpawnGroupCompletedEvent` (orchestration — CaseHub's concern)
- Template-level `spawnConfig` / auto-trigger (orchestration)
- Parent auto-completion (orchestration)
- Activation conditions (CaseHub blackboard)
- Milestones (CaseHub CMMN)
- Sequential chaining (deferred — future `spawnStrategy` field)
- Cross-service spawn (distributed WorkItems epic #92)
- `quarkus-work-casehub` adapter module (future — separate epic)
- Terminal status mapping implementation (lives in future `quarkus-work-casehub`)
