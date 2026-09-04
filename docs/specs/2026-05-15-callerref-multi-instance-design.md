# callerRef propagation for multi-instance groups — casehubio/work#166

**Date:** 2026-05-15  
**Issue:** casehubio/work#166  
**Status:** Approved

---

## Problem

`WorkItemTemplateService.instantiate()` accepts a `callerRef` parameter so the
casehub-engine can route completion events back to the correct `PlanItem`. For
single-item templates this works: the callerRef is written to the WorkItem and
echoed in every `WorkItemLifecycleEvent`.

For multi-instance templates the callerRef is silently dropped with a warning
(see the guard block in `instantiate()` lines 101-105). The parent WorkItem is
created with `callerRef = null`, so `WorkItemGroupLifecycleEvent.callerRef()`
is always null — the engine has no routing signal when the group completes.

---

## Scope

casehub-work only. The engine side (template-mode wiring in
`HumanTaskScheduleHandler` and a `WorkItemGroupLifecycleAdapter`) is tracked
in casehubio/engine#255 and will be handled separately.

---

## Design

### What changes

**`MultiInstanceSpawnService.createGroup()`** — add a fourth parameter
`String callerRef`. Pass it into `buildParentRequest()`, which sets it on the
`WorkItemCreateRequest` at the position currently hardcoded `null`.
`buildChildRequest()` is unchanged — children remain callerRef-null.

**`WorkItemTemplateService.instantiate()`** — remove the warn-and-ignore block,
pass `callerRef` through to `createGroup()`. The 4-arg overload (which supplies
`null` for callerRef) continues to delegate correctly.

**`MultiInstanceGroupPolicy.buildGroupEvent()`** — no change. It already reads
`parent.callerRef` and echoes it into `WorkItemGroupLifecycleEvent` (line 141).
Once the parent has a real callerRef this path is correct.

### Why children do not carry callerRef

The routing signal for a multi-instance group is `WorkItemGroupLifecycleEvent`,
not individual child `WorkItemLifecycleEvent`s. Giving children the same
callerRef as the parent would cause `WorkItemLifecycleAdapter` to attempt a
`PlanItem` transition for each child completion — N transitions instead of one.
The engine's `WorkItemLifecycleAdapter` already silently ignores child events
with no callerRef (null parse → early return), which is the correct behaviour.

### Data flow

```
instantiate(template, title, assignee, createdBy, callerRef)
  │
  ├─ instanceCount != null → createGroup(template, title, createdBy, callerRef)
  │     ├─ parent WorkItem   callerRef = "case:{id}/pi:{id}"
  │     ├─ WorkItemSpawnGroup  no callerRef field (reads parent at event-build time)
  │     └─ children (×N)    callerRef = null
  │
  └─ children complete → MultiInstanceCoordinator → MultiInstanceGroupPolicy
        └─ threshold met → parent.complete() → buildGroupEvent()
              └─ WorkItemGroupLifecycleEvent.callerRef = parent.callerRef ✓
```

The engine's future `WorkItemGroupLifecycleAdapter` (engine#255 follow-on)
observes `WorkItemGroupLifecycleEvent`, parses the callerRef, and marks the
PlanItem. `WorkItemLifecycleAdapter` already ignores individual child events.

---

## Tests

One new test in `MultiInstanceCoordinatorTest`:

1. **`createGroup_withCallerRef_setsOnParentOnly`** — call `createGroup()` directly
   with a non-null callerRef; assert parent has it; assert all 3 children have null.

One new test in `WorkItemGroupLifecycleEventTest` (placed here rather than
`MultiInstanceCoordinatorTest` because `EventCapture`, the Awaitility
stability-window pattern, and `@ObservesAsync` infrastructure already live there):

2. **`completedGroupEvent_carriesCallerRef`** — drive a 2-of-2 group to
   completion via `instantiate()`, assert the resulting `COMPLETED`
   `WorkItemGroupLifecycleEvent.callerRef()` matches the original value.
   Uses a `during(300ms)` stability window to guard against spurious duplicate events.

One renamed+inverted test in `WorkItemTemplateInstantiateTest`:

3. **`instantiate_multiInstanceTemplate_setsCallerRefOnParent`** — verify callerRef
   is no longer silently dropped when instantiating a multi-instance template.

---

## Out of scope / deferred

- engine#255: template mode in `HumanTaskScheduleHandler` + `WorkItemGroupLifecycleAdapter`
- work#172: emit+listen SWF 1.0 bridge (separate integration path, not required here)
