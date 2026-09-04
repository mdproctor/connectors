---
layout: post
title: "The parent gets the callerRef"
date: 2026-05-15
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-work]
tags: [multiinstance, callerref, engine-routing, awaitility]
---

A prompt arrived at the start describing three bugs in `casehub-work-ledger`
— JSON built with `String.format()`, a null guard missing on `eventSuffix()`,
eight tests with wrong expectations. I looked at the code. All three were
already fixed — the April 29 session had found and shipped them. The prompt
was stale.

The actual work was propagating `callerRef` through
`WorkItemTemplateService.instantiate()` when the template is multi-instance.
Today it silently dropped the value with a log warning.
`WorkItemGroupLifecycleEvent.callerRef()` was always null, leaving the engine
with no routing signal when a group completed.

## The infrastructure was already there

Reading the code before designing, we found `MultiInstanceGroupPolicy.buildGroupEvent()`
already reads `parent.callerRef` and puts it on the event — line 141, in
place since multi-instance shipped. The gap wasn't the event wiring. It was
that `MultiInstanceSpawnService.createGroup()` had no callerRef parameter, so
the parent WorkItem was always created with null. The fix was small once we
understood what was already built.

## Children don't get the callerRef

The issue description said "each child should carry callerRef so its completion
event routes correctly." I pushed back on that.

`WorkItemLifecycleAdapter` in the engine observes individual
`WorkItemLifecycleEvent`s and calls `item.markCompleted()` on the PlanItem for
each one it recognises. If every child carried the parent's callerRef, the
engine would attempt a PlanItem transition for each child completion — N
transitions instead of one. That's wrong for M-of-N semantics.

The group event handles this correctly. Children have null callerRef;
`WorkItemLifecycleAdapter` ignores them; the coordinator fires
`WorkItemGroupLifecycleEvent` when the threshold is met; that event carries
`parent.callerRef`. One signal, one PlanItem transition.

## The poll that exits too early

The code quality review surfaced a testing subtlety. A new test was checking
that exactly one COMPLETED group event fired:

```java
Awaitility.await()
    .atMost(Duration.ofSeconds(5))
    .untilAsserted(() -> assertThat(events).hasSize(1));
```

`untilAsserted` exits the moment that assertion passes on a single poll. A
second event arriving 50ms later doesn't fail anything — the test has already
finished. For an exact-count check, the right tool is `during()`:

```java
Awaitility.await()
    .atMost(Duration.ofSeconds(5))
    .during(Duration.ofMillis(300))
    .until(() -> events.size() == 1);

assertThat(events.get(0).callerRef()).isEqualTo(expected);
```

`during()` holds the poll open for 300ms and requires the condition to stay
continuously true. The pattern was already in the test suite — Claude caught
that the new test hadn't followed it.
