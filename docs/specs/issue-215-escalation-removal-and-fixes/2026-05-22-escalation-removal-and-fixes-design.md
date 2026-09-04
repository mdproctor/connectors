# Design: EscalationPolicy Removal, EscalateTo Auto-Assignment, Inbox Fix, Docs Sync

**Branch:** `issue-215-escalation-removal-and-fixes`
**Issues:** casehubio/work#215, casehubio/work#216, casehubio/work#205, casehubio/parent#43
**Date:** 2026-05-22

---

## work#215 — Remove deprecated EscalationPolicy

### Why

`EscalationPolicy` was the original SLA breach SPI: the runtime called `policy.escalate(event)`
and stopped — the policy owned execution, mutating status, writing audits, firing events
inconsistently. `SlaBreachPolicy` inverts this: the policy returns a `BreachDecision`; the
runtime executes it, owning all side effects (status transition, audit, `SlaBreachEvent`).
The three built-in implementations are redundant:

| Old impl | Equivalent in new design |
|---|---|
| `AutoRejectEscalationPolicy` | `BreachDecision.Fail` → `WorkItemStatus.EXPIRED` |
| `ReassignEscalationPolicy` | `BreachDecision.EscalateTo` (groups validation already in factory) |
| `NotifyEscalationPolicy` | `SlaBreachEvent` observer in consuming app |

No consuming repos implement `EscalationPolicy` (devtown, clinical, aml — all clean).
Engine does not reference it. Safe to remove the SPI interface itself.

### What to delete

**`casehub-work-api` module:**
- `EscalationPolicy.java` — the SPI interface

**`runtime` module — `service/` package:**
- `AutoRejectEscalationPolicy.java`
- `ReassignEscalationPolicy.java`
- `NotifyEscalationPolicy.java`
- `EscalationPolicyProducer.java`
- `ExpiryEscalation.java` (CDI qualifier)
- `ClaimEscalation.java` (CDI qualifier)
- `WorkItemExpiredEvent.java` — fired only by NotifyEscalationPolicy; zero external consumers

**`runtime` module — `config/WorkItemsConfig.java`:**
- `escalationPolicy()` method + Javadoc
- `claimEscalationPolicy()` method + Javadoc
- Class-level header comment referencing `casehub.work.escalation-policy=reassign`

**`runtime` test files:**
- `service/EscalationPolicyTest.java`
- `service/NotifyEscalationPolicyTest.java`
- Two tests in `config/WorkItemsConfigDefaultsTest.java`:
  `escalationPolicyIsNotify` and `claimEscalationPolicyIsNotify`

**`casehub-work-api` test files:**
- `EscalationPolicyTest.java`

### Audit detail — preserve from AutoRejectEscalationPolicy

`AutoRejectEscalationPolicy` set `AuditEntry.detail = "auto-rejected: expiry deadline exceeded"`.
The new `executeFail()` stores `fail.reason()` in `item.resolution` but never propagates it to
the audit entry. Fix: pass `fail.reason()` as the `detail` parameter when calling `writeAudit()`.

Change `writeAudit()` signature to accept a nullable detail:

```java
private void writeAudit(WorkItem item, String event, String detail, Instant now)
```

Call sites (all three executors):
- `executeFail()` → `writeAudit(item, "EXPIRED", fail.reason(), now)`
- `executeEscalateTo()` → `writeAudit(item, "ESCALATED", null, now)`
- `executeExtend()` → `writeAudit(item, "SLA_EXTENDED", null, now)`

Note: `checkClaimDeadlines()` fires a `CLAIM_EXPIRED` lifecycle event but writes no audit entry
directly — the executor (Fail / EscalateTo / Extend) writes the audit for the actual outcome.

### Notes

- `REJECTED` status used by `AutoRejectEscalationPolicy` is **not** preserved.
  `BreachDecision.Fail` maps to `WorkItemStatus.EXPIRED` — correct semantics for a timed-out
  WorkItem. REJECTED implies human/system decision; EXPIRED implies time ran out.
- `hasCandidates` guard from `ReassignEscalationPolicy` is already covered: `EscalateTo` factory
  rejects empty groups, and `executeEscalateTo()` converts an empty `EscalateTo` to `Fail`.

---

## work#216 — WorkBroker auto-assignment after EscalateTo

### Problem

After `executeEscalateTo()` sets `candidateGroups` and status to `PENDING`, no assignment
attempt is made. The item sits in the escalation pool waiting for a worker to claim manually —
even when the routing strategy (LeastLoaded, RoundRobin) could pre-assign immediately.

### Design

**New `AssignmentTrigger` value:** `SLA_ESCALATED`

`WorkerSelectionStrategy.triggers()` returns `Set.of(AssignmentTrigger.values())` by default,
which includes all enum values. Adding `SLA_ESCALATED` is automatically included in every
strategy's default trigger set — no existing strategy needs to change.

Built-in strategy behavior on `SLA_ESCALATED`:
- `LeastLoadedStrategy` — pre-assigns to lowest-loaded worker in the escalation groups
- `RoundRobinStrategy` — pre-assigns in round-robin order across the escalation groups
- `ClaimFirstStrategy` — returns `noChange()` (no override of `triggers()` needed; correct
  behavior: escalation returns the item to the pool for first-claim)

**Inject `WorkItemAssignmentService` into `ExpiryLifecycleService`.**

No circular dependency: `WorkItemAssignmentService` does not inject `ExpiryLifecycleService`.

**Call order within `executeEscalateTo()`:**

```
1. Set candidateGroups, assigneeId = null, status = PENDING
2. Set deadline (expiresAt or claimDeadline per BreachType)
3. assignmentService.assign(item, AssignmentTrigger.SLA_ESCALATED)
   → mutates item to ASSIGNED if candidate found; noChange() leaves PENDING
4. workItemStore.put(item)   ← single write, captures final state
5. writeAudit(item, "ESCALATED", null, now)
6. fireLifecycleEvent("ESCALATED", item)   ← carries final state (PENDING or ASSIGNED)
```

**Consistency with CREATED path:** `WorkItemService.create()` calls `assign()` then persists,
fires only the CREATED event (not a separate ASSIGNED event). Same pattern here — ESCALATED
event carries the final item state including `assigneeId` if pre-assigned.

**Error handling:** if `assign()` finds no eligible candidates it returns `noChange()` silently.
Item stays PENDING in the escalation pool. No exception, no rollback.

---

## work#205 — scanRoots 3-param (already done)

3-param `scanRoots(assignee, candidateUser, candidateGroups)` is fully implemented in
`JpaWorkItemStore` since commit `66c4541`. Each dimension is an independent OR predicate.
Close the issue with a pointer to that commit.

---

## parent#43 — docs/repos/casehub-work.md sync

Four changes to `casehub-parent/docs/repos/casehub-work.md`:

1. **SPI table** — add `SlaBreachPolicy`; mark `EscalationPolicy` as **removed** (not deprecated
   — it's gone after this branch merges)
2. **`casehub-work-api` module description** — note dependency on `casehub-platform-api` for
   `Path` and `Preferences` in `SlaBreachContext`
3. **WorkItem fields** — add `scope VARCHAR(255)` (V31): hierarchical scope path for SLA
   preference resolution; null = root scope
4. **`WorkItemStore.scanRoots`** — update to 3-param signature
   `scanRoots(assignee, candidateUser, candidateGroups)`

No `PLATFORM.md` update required — `casehub-platform-api` boundary rule is already documented.

---

## Platform coherence

- All changes are within `casehub-work` (foundation tier) and `casehub-parent` (docs)
- No new cross-repo dependencies introduced
- `EscalationPolicy` removal affects no consuming repos (verified: devtown, clinical, aml clean)
- `SLA_ESCALATED` trigger addition is backward-compatible: default `triggers()` includes all
  enum values; no existing strategy override is broken
