# WorkItemGroupLifecycleEvent Hierarchy Evaluation

**Issue:** casehubio/work#279
**Date:** 2026-06-30
**Status:** Approved — standalone confirmed, GroupStatus retroactive compliance fix

---

## Decision

WorkItemGroupLifecycleEvent remains a standalone final class with no type relationship to WorkItemEvent.

## Evaluation

### Question

Should WorkItemGroupLifecycleEvent implement a common base event interface (WorkItemEvent or a new shared type), or remain standalone?

### Analysis

WorkItemEvent and WorkItemGroupLifecycleEvent represent different domain concepts:

| Dimension | WorkItemEvent | WorkItemGroupLifecycleEvent |
|-----------|--------------|----------------------------|
| Subject | Single WorkItem lifecycle transition | Aggregate group threshold crossing |
| Identity | WorkItemRef (id, status, callerRef, assigneeId, resolution, candidateGroups, outcome, tenancyId) — 8 fields | parentId, groupId, instanceCount, requiredCount, completedCount, rejectedCount — 6 fields |
| Status vocabulary | WorkEventType (25 values) | GroupStatus (3 values) |
| Status semantics | "Human completed this task" | "M-of-N threshold crossed" |
| Shared fields | callerRef, tenancyId, occurredAt | callerRef, tenancyId, occurredAt |

The 3 shared fields are infrastructure (multi-tenancy, timestamps, routing correlation), not domain identity.

### Observer analysis

Every observer in the system that handles both event types uses separate methods with completely different logic:

| Observer | Observes type | GroupLifecycleEvent | Identical handling? |
|----------|:---:|:---:|:---:|
| WorkCloudEventAdapter | `WorkItemLifecycleEvent` (concrete) | ✅ | No — different type string, source URI, subject derivation |
| Engine WorkItemLifecycleAdapter | `WorkItemEvent` (interface) | ✅ | No — entirely separate routing logic |
| LedgerEventCapture | `WorkItemLifecycleEvent` (concrete) | — | — |
| WorkItemFlowEventListener | `WorkItemLifecycleEvent` (concrete) | — | — |
| LocalWorkItemEventBroadcaster (SSE) | `WorkItemLifecycleEvent` (concrete) | — | — |
| MultiInstanceCoordinator | `WorkItemLifecycleEvent` (concrete) | — | — |

casehub-work observers subscribe to the concrete `WorkItemLifecycleEvent` class. Only the engine's `WorkItemLifecycleAdapter` subscribes to the `WorkItemEvent` interface (per engine#585 migration). This distinction matters for the rejected alternatives analysis below.

No observer handles them polymorphically. No concrete use case requires polymorphic observation.

### Rejected alternatives

**Common `WorkDomainEvent` marker interface** — no observer would use it. An interface with only occurredAt/tenancyId/callerRef has no useful observation semantics; consumers would always need to cast to the concrete type.

**WorkItemGroupLifecycleEvent implements WorkItemEvent** — `ref()` would return a WorkItemRef where 6 of 8 fields are null/invalid. `eventType()` has no valid WorkEventType for group events. The engine's `WorkItemLifecycleAdapter`, which observes the `WorkItemEvent` interface (per engine#585), would receive group events on the `onWorkItemLifecycle()` handler in addition to the dedicated `onWorkItemGroupLifecycle()` handler — breaking the adapter's routing assumptions. casehub-work observers (LedgerEventCapture, WorkItemFlowEventListener, etc.) subscribe to the concrete `WorkItemLifecycleEvent` class and would not be affected, but the engine's interface-level observation is sufficient reason to reject this alternative.

### Conclusion

The current standalone design is architecturally correct. The evaluation surfaced no use case warranting unification and confirmed the #278 design rationale.

---

## GroupStatus Lifecycle Protocol Compliance

GroupStatus is a lifecycle state machine (3 states, clear terminal semantics) that is missing platform-required methods, LIFECYCLE.md registration, and PLATFORM.md capability ownership.

GroupStatus has been in production since Chapter C23 (multi-instance, delivered 2026-04-28). LIFECYCLE.md Rule 4 requires registration "before the first commit that uses it." This spec retroactively brings GroupStatus into compliance — the omission was an oversight during the original multi-instance implementation.

### Changes

1. **Add `isTerminal()` and `isActive()` to `GroupStatus`**
   - `isTerminal()` → true for COMPLETED, REJECTED
   - `isActive()` → true for IN_PROGRESS

2. **Persist `groupStatus` on `WorkItemSpawnGroup`**
   - Add nullable `GroupStatus groupStatus` field (no default — null for non-multi-instance groups)
   - Set to `IN_PROGRESS` at multi-instance group creation (where `requiredCount != null`)
   - Set authoritatively to `COMPLETED`/`REJECTED` in `MultiInstanceGroupPolicy.process()`
   - Flyway migration with conditional backfill:
     ```sql
     ALTER TABLE work_item_spawn_group ADD COLUMN group_status VARCHAR(15);

     UPDATE work_item_spawn_group SET group_status = CASE
         WHEN policy_triggered = true AND completed_count >= required_count THEN 'COMPLETED'
         WHEN policy_triggered = true AND completed_count < required_count THEN 'REJECTED'
         WHEN required_count IS NOT NULL THEN 'IN_PROGRESS'
         ELSE NULL
     END;
     ```
   - Non-multi-instance groups (`requiredCount IS NULL`) remain `groupStatus = null` — they have no lifecycle state machine
   - Eliminates scattered status derivation — `WorkItemInstancesResource` and `JpaWorkItemStore` read stored value instead of re-deriving from counts
   - `policyTriggered` becomes derivable from `groupStatus != null && groupStatus.isTerminal()` — cleanup tracked separately

3. **Register GroupStatus in `parent/docs/LIFECYCLE.md`**
   - `GroupStatus` | `casehub-work` | `IN_PROGRESS`, `COMPLETED`, `REJECTED` (3) | `isTerminal()`, `isActive()`

4. **Update PLATFORM.md capability ownership**
   - Update M-of-N row to reference `GroupStatus` as a registered lifecycle state machine with `isTerminal()` / `isActive()`

5. **Cross-repo engine migration** (casehubio/engine#624):
   - `WorkItemLifecycleAdapter.onWorkItemGroupLifecycle()` — replace `status != GroupStatus.COMPLETED && status != GroupStatus.REJECTED` with `!status.isTerminal()`

### Consumer audit

Search all repos for explicit GroupStatus enum checks:

| Location | Current code | Migration |
|----------|-------------|-----------|
| engine `WorkItemLifecycleAdapter` | `status != COMPLETED && status != REJECTED` | `!status.isTerminal()` — tracked in casehubio/engine#624 |
| work `WorkItemInstancesResource` | Derives GroupStatus from `policyTriggered` + counts | Read `group.groupStatus` directly |
| work `JpaWorkItemStore.scanRoots()` | Derives GroupStatus from `policyTriggered` + counts | Read `group.groupStatus` directly |
| work `MultiInstanceGroupPolicy` | Internal — produces events, uses switch on GroupStatus values with semantically distinct logic per case | Sets `group.groupStatus` authoritatively; no change to switch (correct per lifecycle protocol Rule 1) |

### #279 disposition

This spec's changes (lifecycle compliance + GroupStatus persistence) are the implementation of casehubio/work#279. The engine consumer migration (casehubio/engine#624) does not block closing #279 — it is a downstream cleanup tracked by its own cross-repo issue.

---

## References

- casehubio/work#278 — API surface cleanup that promoted WorkItemEvent
- casehubio/engine#585 — engine observer migration to WorkItemEvent (completed)
- casehubio/engine#624 — cross-repo: use GroupStatus.isTerminal() in WorkItemLifecycleAdapter
- parent/docs/LIFECYCLE.md — lifecycle coherence protocol
