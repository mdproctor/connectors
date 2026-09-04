# Multi-Instance WorkItems — Design Spec
**Date:** 2026-04-28
**Epic:** #106
**Status:** Approved for implementation

---

## Overview

Adds M-of-N parallel instance semantics to WorkItems. A template configured with `instanceCount=5, requiredCount=3` creates 5 parallel child WorkItems on instantiation; the parent completes when 3 are approved regardless of the other 2. The inbox surfaces the parent as a thread root with aggregate progress.

Covers: four-eyes approval (2-of-N), supermajority voting, peer review (any-of-N).

---

## Boundary Placement

M-of-N group completion is a **group policy primitive**, not orchestration. The boundary test (LAYERING.md): does it impose ordering? Does it require external context? Static M-of-N satisfies neither — policy is declared at creation time and evaluated purely from member states. Lives in core runtime, extending `WorkItemSpawnGroup`. LAYERING.md updated.

`Stage.requiredItemIds` in CaseHub remains the correct tool for heterogeneous named plan items. For multi-instance WorkItems, CaseHub observes the single parent COMPLETED event — it does not track individual instances.

---

## Architecture

```
POST /workitems (multi-instance template)
        │
        ▼
WorkItemService.create()   [@Transactional]
  ├── creates parent WorkItem
  ├── detects MultiInstanceConfig on template
  ├── creates WorkItemSpawnGroup (policy copied from template)
  ├── spawns N child WorkItems (parentId = parent.id, PART_OF wired)
  ├── applies InstanceAssignmentStrategy to children
  └── returns parent with aggregate stats

        │ child terminal event (@ObservesAsync)
        ▼
MultiInstanceCoordinator
  ├── ignores: no parentId, non-terminal, non-multi-instance group
  ├── increments completedCount or rejectedCount on WorkItemSpawnGroup (@Version OCC)
  ├── success: completedCount >= requiredCount → complete parent, fire COMPLETED
  ├── failure: remaining active < still needed → reject parent, fire REJECTED
  ├── in progress: fire IN_PROGRESS with current counts
  └── OptimisticLockException → retry once (policyTriggered=true on retry = return silently)

GET /workitems (inbox — always threaded)
  └── roots (parentId IS NULL) visible to user (direct or descendant)
      each root: childCount, completedCount, requiredCount, groupStatus

GET /workitems/{id}/instances
  └── children + group summary header
```

---

## Data Model

### New in `quarkus-work-api`

```java
public record MultiInstanceConfig(
    int instanceCount,
    int requiredCount,
    ParentRole parentRole,             // COORDINATOR | PARTICIPANT; default COORDINATOR
    String assignmentStrategyName,     // CDI bean name; null = "pool"
    OnThresholdReached onThresholdReached,  // CANCEL | LEAVE; default CANCEL
    boolean allowSameAssignee,         // default false (guard enforced)
    List<String> explicitAssignees     // required when strategy="explicit"; size must == instanceCount
) {}

public enum ParentRole         { COORDINATOR, PARTICIPANT }
public enum OnThresholdReached { CANCEL, LEAVE }
public enum GroupStatus        { IN_PROGRESS, COMPLETED, REJECTED }

public interface InstanceAssignmentStrategy {
    void assign(List<WorkItem> instances, MultiInstanceContext context);
}

public record MultiInstanceContext(WorkItem parent, MultiInstanceConfig config) {}

public class WorkItemGroupLifecycleEvent {
    UUID parentId;
    UUID groupId;
    int instanceCount;
    int requiredCount;
    int completedCount;
    int rejectedCount;
    GroupStatus groupStatus;
    String callerRef;      // echoed opaquely from parent — CaseHub routing key
    Instant occurredAt;
}
```

### `WorkItemTemplate` — new fields (nullable; null = not multi-instance)

```
instance_count          INT
required_count          INT
parent_role             VARCHAR(15)
assignment_strategy     VARCHAR(255)
on_threshold_reached    VARCHAR(10)
allow_same_assignee     BOOLEAN
```

### `WorkItemEntity` — new field

```
parent_id   UUID   nullable   FK → work_item(id)
```
+ index on `parent_id`

### `WorkItemSpawnGroup` — new fields

```
instance_count       INT          nullable
required_count       INT          nullable
on_threshold_reached VARCHAR(10)  nullable
allow_same_assignee  BOOLEAN      NOT NULL  default false
parent_role          VARCHAR(15)  nullable
completed_count      INT          NOT NULL  default 0
rejected_count       INT          NOT NULL  default 0
policy_triggered     BOOLEAN      NOT NULL  default false
version              BIGINT       NOT NULL  default 0    ← @Version for OCC
```

Policy config is copied from template into `WorkItemSpawnGroup` at spawn time. The coordinator reads from the group — no template lookup at evaluation time.

### Flyway migrations

Two migrations (next available V-numbers):
- V_next: `parent_id` on `work_item` + index
- V_next+1: new columns on `work_item_spawn_group` + `work_item_template`

---

## API

### `POST /workitems` — unchanged signature

When the referenced template has `instanceCount` set: creates parent + N children in one `@Transactional`. Returns parent with aggregate stats. Callers see one call regardless of multi-instance or not.

### `GET /workitems` — always threaded

Returns roots (`parentId IS NULL`) visible to the requesting user. Each root includes:

```json
{
  "childCount": 5,
  "completedCount": 2,
  "requiredCount": 3,
  "groupStatus": "IN_PROGRESS"
}
```

Standalone WorkItems: `childCount=0`, others `null`. No noise for non-multi-instance users.

Visibility via recursive CTE — roots where user has direct assignment OR visibility into at least one descendant:

```sql
WITH RECURSIVE visible AS (
  SELECT id FROM work_item
  WHERE assignee_id = :userId OR candidate_groups && :userGroups
  UNION
  SELECT w.parent_id FROM work_item w
  JOIN visible v ON w.id = v.id
  WHERE w.parent_id IS NOT NULL
)
SELECT w.*, sg.completed_count, sg.required_count, sg.instance_count, sg.policy_triggered
FROM work_item w
LEFT JOIN work_item_spawn_group sg ON sg.parent_id = w.id
WHERE w.parent_id IS NULL
  AND w.id IN (SELECT id FROM visible)
```

### `GET /workitems/{id}/instances` — new

```json
{
  "parentId": "...",
  "instanceCount": 5,
  "requiredCount": 3,
  "completedCount": 2,
  "groupStatus": "IN_PROGRESS",
  "instances": [
    { "id": "...", "status": "COMPLETED", "assigneeId": "alice", ... },
    { "id": "...", "status": "IN_PROGRESS", "assigneeId": "bob", ... }
  ]
}
```

### `POST /workitems/{id}/claim` — claim guard added

Before existing claim logic: if `parentId` is set and group has `allowSameAssignee=false`, check for any sibling with `assigneeId = claimant`. Reject 409: `"You already hold another instance in this group"`.

### `POST /workitems/templates` — validation

| Condition | Response |
|---|---|
| `instanceCount < 1` | 422 |
| `requiredCount < 1` | 422 |
| `requiredCount > instanceCount` | 422 |
| `parentRole=COORDINATOR` and `claimDeadline` non-null | 422 |
| `instanceCount=1, requiredCount=1` | valid |
| strategy=`explicit` and `explicitAssignees` null or wrong size | 422 |

---

## Core Services

### `WorkItemService.create()` — multi-instance path

```
1. Create parent WorkItem
   - If COORDINATOR: no assigneeId, no candidateGroups, no candidateUsers
   - If PARTICIPANT: normal assignment fields from template
2. Create WorkItemSpawnGroup with policy copied from template
3. For i in 0..instanceCount-1:
   - Create child WorkItem from template, parentId = parent.id
   - Wire PART_OF relation (child → parent)
4. Apply InstanceAssignmentStrategy to full child list
5. Return parent
```

### `InstanceAssignmentStrategy` implementations (runtime)

- **`PoolAssignmentStrategy`** (`@Named("pool")`) — copies parent candidateGroups/candidateUsers to all children; workers claim first-come. Default.
- **`RoundRobinAssignmentStrategy`** (`@Named("roundRobin")`) — applies `WorkerSelectionStrategy` per child with exclusion; each child pre-assigned to a different worker.
- **`ExplicitListAssignmentStrategy`** (`@Named("explicit")`) — maps each child to a specific assignee from `MultiInstanceContext`; list length must match `instanceCount`.
- **`CompositeInstanceAssignmentStrategy`** (`@Named("composite")`) — applies multiple strategies in sequence; later strategies override earlier ones.

### `MultiInstanceCoordinator`

`@ApplicationScoped`. Uses `@ObservesAsync` — group update runs in a separate transaction from child status change, enabling clean OCC retry without rolling back child completion.

```
onChildTerminal(WorkItemLifecycleEvent event):
  child = event.source()
  return if child.parentId == null
  return if !child.status.isTerminal()

  group = WorkItemSpawnGroup.findMultiInstanceByParentId(child.parentId)
  // findMultiInstanceByParentId: SELECT WHERE parentId = ? AND requiredCount IS NOT NULL
  return if group == null  // not a multi-instance group
  return if group.policyTriggered                         // already resolved

  if child.status == COMPLETED: group.completedCount++
  else:                         group.rejectedCount++

  remaining = instanceCount - completedCount - rejectedCount
  needed    = requiredCount  - completedCount

  if completedCount >= requiredCount:
    resolve(group, COMPLETED)
  else if remaining < needed:
    resolve(group, REJECTED)
  else:
    fireGroupEvent(group, IN_PROGRESS)

resolve(group, outcome):
  group.policyTriggered = true
  workItemService.complete/reject(group.parentId, "system:multiinstance", ...)
  if onThresholdReached == CANCEL: cancel non-terminal children [@Transactional]
  fireGroupEvent(group, outcome)

OptimisticLockException → retry once
  if policyTriggered=true on retry → return silently
```

### Coordinator SLA

`expiresAt` on coordinator parent = group deadline. `ExpiryCleanupJob` marks parent EXPIRED; `MultiInstanceCoordinator` observes the EXPIRED event and cascade-cancels non-terminal children via existing policy path.

---

## Event Model

Two distinct CDI event types in `quarkus-work-api`:

| Event | When | Who subscribes |
|---|---|---|
| `WorkItemLifecycleEvent` | Every individual WorkItem status transition (unchanged) | Audit, ledger, per-item observers, existing notification rules |
| `WorkItemGroupLifecycleEvent` | Every child terminal event (IN_PROGRESS) + group resolution (COMPLETED/REJECTED) | Notification rules for group outcomes, progress dashboards, CaseHub adapter |

Both fire independently. `callerRef` from the parent WorkItem is echoed on `WorkItemGroupLifecycleEvent` — CaseHub's adapter routes group outcomes via the same mechanism as individual events.

`IN_PROGRESS` events carry current counts and are intended for real-time progress consumers, not notification rules.

---

## Terminal Status Policy (fixed)

All non-COMPLETED terminal statuses reduce the achievable count:

| Child status | Before `policyTriggered` | After `policyTriggered` |
|---|---|---|
| COMPLETED | counts toward success | ignored |
| REJECTED | counts toward failure | ignored |
| EXPIRED | counts toward failure | ignored |
| CANCELLED | counts toward failure | ignored |

Auto-cancellations from `onThresholdReached=CANCEL` happen after `policyTriggered=true` — they are ignored by the coordinator's entry-point check.

---

## Atomic Counting (OCC pattern)

`WorkItemSpawnGroup` carries `@Version` OCC. Counter increments + threshold check + parent completion happen in one transaction. `policyTriggered=true` makes the outcome exactly-once safe even if two transactions reach the threshold concurrently.

See `casehub-parent/docs/conventions/atomic-threshold-counters.md` for full rationale.

---

## Error Handling

| Scenario | Behaviour |
|---|---|
| Invalid `MultiInstanceConfig` at template creation | 422 with field-level message |
| Claim when already holds sibling instance | 409: "You already hold another instance in this group" |
| `OptimisticLockException` in coordinator | Retry once; silent if `policyTriggered=true` |
| Coordinator: WorkItem with no `parentId` | Immediate return |
| Coordinator: group already resolved | `policyTriggered=true` entry check → immediate return |
| Auto-cancellation fails | Transaction rolls back; parent completion rolls back; coordinator re-evaluates on next event |

---

## Testing

All in `runtime` module. Target: stays within 60s budget.

| Test class | Covers |
|---|---|
| `MultiInstanceConfigValidationTest` | All 422 rules at template creation |
| `MultiInstanceCoordinatorTest` | Happy path; failure path; CANCEL vs LEAVE; `policyTriggered` idempotency; IN_PROGRESS counts |
| `MultiInstanceClaimGuardTest` | `allowSameAssignee=false` rejects sibling claim; `true` permits |
| `MultiInstanceInboxTest` | Roots returned; coordinator parent visibility via descendant; aggregate stats |
| `InstanceAssignmentStrategyTest` | Pool, RoundRobin, ExplicitList, Composite — unit, no JPA |
| `WorkItemGroupLifecycleEventTest` | Correct counts per child terminal; COMPLETED/REJECTED fire once |
