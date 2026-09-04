# PlanItemCompletionApplier: Handle ESCALATED WorkItem Status

**Issue:** casehubio/work#341
**Date:** 2026-08-06
**Revised:** 2026-08-07 (post design review — coherence, structure, robustness, cross-cutting)

## Problem

When `SlaBreachPolicy` returns `BreachDecision.Exhausted`, `ExpiryLifecycleService.executeExhausted()` sets `WorkItemStatus.ESCALATED` on the WorkItem (terminal). The lifecycle event fires, but `WorkItemLifecycleAdapter.onWorkItemLifecycle()` intercepts ESCALATED at line 77 and routes to `handleEscalation()` — which writes a `workItemEscalated` context signal but does NOT transition the PlanItem. The PlanItem stays DELEGATED. Cases stall.

The issue description states ESCALATED hits `applyStatus()`'s default branch — this is incorrect. ESCALATED never reaches `PlanItemCompletionApplier` because the adapter intercepts it first.

### Contradictory Javadoc

- Class-level (line 52): "ESCALATED is terminal" — correct, matches `WorkItemStatus.isTerminal()`
- `handleEscalation()` method (line 169): "ESCALATED is non-terminal" — wrong; describes `EscalateTo` behavior (WorkItem stays PENDING with new groups), not `Exhausted` behavior (WorkItem → terminal ESCALATED)

### Two ESCALATED Concepts

- **`TaskStatus.ESCALATED`** (engine): active/transient state for human routing oversight. `PlanItem.tryMarkEscalated()` only valid from PENDING/RUNNING. Never persisted. Revertible.
- **`WorkItemStatus.ESCALATED`** (work module): terminal state meaning all SLA breach policies exhausted. Irreversible.

These are distinct — engine ESCALATED is the wrong PlanItem target for terminal WorkItem ESCALATED.

## Design

**Approach:** Remove the ESCALATED intercept from `WorkItemLifecycleAdapter`, let ESCALATED flow through the normal terminal path to `PlanItemCompletionApplier.apply()`.

### Changes

#### 1. WorkItemLifecycleAdapter

- Remove the ESCALATED intercept (lines 77-79)
- Delete `handleEscalation()` method (lines 176-223)
- Update class-level Javadoc: ESCALATED now transitions PlanItem to FAULTED via the applier AND writes the context signal

#### 2. PlanItemCompletionApplier

**`applyStatus()`** — add case:
```java
case ESCALATED -> item.markFaulted();
```

`markFaulted()` is valid from any non-terminal state including DELEGATED (CAS loop in PlanItem lines 254-267).

**Resolution validation bypass:** The existing validation guard (lines 105-117) checks `ref.resolutionTypeName() != null && ref.resolution() != null` and can abort `apply()` if stale resolution data fails deserialisation. `executeExhausted()` does not clear `item.resolution`, so stale resolution from a partial form submission before expiry could trigger the guard and silently block the ESCALATED transition — re-introducing the original bug through a different path. Add an early bypass:
```java
if (status == WorkItemStatus.ESCALATED) {
    // ESCALATED is not an actor-submitted result — skip resolution validation
} else if (ref != null && ref.resolutionTypeName() != null && ref.resolution() != null) {
    // existing validation guard...
}
```

**`apply()`** — after `applyStatus()` returns true:
```java
if (status == WorkItemStatus.ESCALATED) {
    writeEscalationSignal(instance, item, ref);
}
```

This is a new private method in `PlanItemCompletionApplier` (not moved from `WorkItemLifecycleAdapter` — that method is deleted with the rest of `handleEscalation()`). It writes `workItemEscalated` to the CaseContext with:
- `workItemId` — from `ref.id()`
- `lastCandidateGroups` — parsed from `ref.candidateGroups()` (comma-separated). Renamed from the previous `newGroups` — for terminal ESCALATED there are no "new" groups; these are the last-tried groups before exhaustion. No existing case definitions bind on the `workItemEscalated` signal (confirmed: this signal path was always intercepted before reaching the terminal processing path, so no production bindings exist).
- `bindingName` — from `item.getBindingName()`

This is written on the same CaseInstance that fires CONTEXT_CHANGED — no persistence context split.

**Output mapping:** `applyOutputMapping()` is called after the signal, following the standard `apply()` flow. For ESCALATED, this is a no-op in practice — `executeExhausted()` does not set `item.resolution`, so `applyOutputMapping()` short-circuits at its `ref.resolution() == null` guard. This is acceptable: the generic `apply()` path handles the null-resolution case gracefully, and the escalation signal provides the necessary context for case definitions to react.

**PlanItemStateChangedEvent** — add ESCALATED to the FAULTED firing block:
```java
if (status == WorkItemStatus.FAULTED || status == WorkItemStatus.EXPIRED
        || status == WorkItemStatus.ESCALATED) {
    planItemStateChangedEvents.fireAsync(
        new PlanItemStateChangedEvent(caseId, planItemId, bindingName,
            previousStatus, TaskStatus.FAULTED, instance.tenancyId));
}
```

**Accepted tech debt:** `PlanItemStateChangedEvent` maps ESCALATED → `TaskStatus.FAULTED`, erasing the distinction between infrastructure failure (FAULTED), deadline expiry (EXPIRED), and escalation exhaustion (ESCALATED). This is a pre-existing limitation — EXPIRED already maps to FAULTED. The `workItemEscalated` context signal compensates for PlanItem-backed work. Adding a cause enum to `PlanItemStateChangedEvent` is out of scope for this fix.

#### 3. ActionGateCompletionApplier

Removing the ESCALATED intercept from `WorkItemLifecycleAdapter` means gate-backed ESCALATED events now flow through the normal terminal path to `ActionGateCompletionApplier.apply()`. Without a fix, ESCALATED hits the `default` branch — silent debug log, no gate event published, gate hangs.

Add ESCALATED to the failure case arm (line 66):
```java
case EXPIRED, FAULTED, ESCALATED -> handleExpired(gateRef, tenancyId);
```

This fires `ActionGateExpiredEvent`, consistent with the PlanItem ESCALATED → FAULTED mapping. The same cause-erasure tech debt applies (gate listeners cannot distinguish expiry from escalation exhaustion).

#### 4. Tests

Three tests, split by responsibility:

**a. `PlanItemCompletionApplierTest` (new or existing) — applier behavior:**
- Start PlanItem in DELEGATED state with HumanTaskTarget (outputMapping configured)
- WorkItem with ESCALATED status, null resolution (matches production `executeExhausted()` path)
- Assert: PlanItem → FAULTED
- Assert: `workItemEscalated` signal written with `lastCandidateGroups` and `bindingName`
- Assert: output mapping gracefully skipped (CaseContext unchanged except for signal)
- Assert: `PlanItemStateChangedEvent` fired with `TaskStatus.FAULTED`
- Assert: `CONTEXT_CHANGED` event fires

**b. `WorkItemLifecycleAdapterTest` — adapter routing:**
- Update existing `workItemEscalated_writesContextSignal_planItemUnchanged`
- Rename to `workItemEscalated_routesToApplier_marksPlanItemFaulted`
- Start PlanItem in DELEGATED state
- Assert: PlanItem → FAULTED (confirms the intercept is gone and ESCALATED reaches the applier)
- Assert: `workItemEscalated` signal present

**c. `PlanItemCompletionApplierTest` — stale resolution edge case:**
- WorkItem with ESCALATED status AND non-null resolution/resolutionTypeName (stale from partial form submission)
- Assert: resolution validation is bypassed
- Assert: PlanItem still transitions to FAULTED
- Assert: escalation signal still written

## Data Flow After Fix

```
ExpiryLifecycleService.executeExhausted()
  → WorkItem.status = ESCALATED (terminal)
  → fireLifecycleEvent("ESCALATED", workItem)
  → WorkItemLifecycleAdapter.onWorkItemLifecycle()
    → passes isTerminal() check
    → parses CallerRef
    → PlanItemCallerRef: applier.apply(caseId, planItemId, ESCALATED, ref)
      → bypasses resolution validation (ESCALATED is not actor-submitted)
      → applyStatus(): item.markFaulted()  (DELEGATED → FAULTED)
      → writeEscalationSignal(): workItemEscalated → CaseContext
      → applyOutputMapping(): skipped (resolution is null)
      → fires PlanItemStateChangedEvent(DELEGATED → FAULTED)
      → publishes CONTEXT_CHANGED
    → GateCallerRef: gateApplier.apply(gateRef, ESCALATED, ref, tenancyId)
      → case ESCALATED → handleExpired() → publishes ActionGateExpiredEvent
```

## Issue #341 Acceptance Criteria — Revised

The original acceptance criteria assumed output mapping runs for ESCALATED. Revised:

- [x] `applyStatus()` handles `WorkItemStatus.ESCALATED` — transitions PlanItem to FAULTED
- [x] Resolution validation bypassed for ESCALATED (not actor-submitted)
- [x] `workItemEscalated` signal written to CaseContext
- [x] `CaseContextChangedEvent` fires so bindings re-evaluate
- [x] `ActionGateCompletionApplier` handles ESCALATED → fires `ActionGateExpiredEvent`
- [ ] ~~`applyOutputMapping()` runs~~ — no-op in practice (null resolution); accepted
- [x] Unit tests: applier behavior, adapter routing, stale resolution bypass
