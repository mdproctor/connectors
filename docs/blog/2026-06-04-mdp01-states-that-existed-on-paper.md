---
layout: post
title: "States That Existed on Paper"
date: 2026-06-04
entry_type: note
subtype: diary
projects: [casehub-work]
tags: [lifecycle, java, quarkus]
---

## States That Existed on Paper

Five bugs had been sitting in the issue tracker: `EXPIRED` missing from `isTerminal()`, `ESCALATED` in `isTerminal()` but never set, `DELEGATED` in the enum but `delegate()` setting `PENDING` instead. I knew they were there. I kept deferring them because each one looked small.

They weren't small. They were a pattern.

`WorkItemStatus` had ten values. Three of them — EXPIRED, ESCALATED, DELEGATED — were defined but the code didn't treat them seriously. EXPIRED existed as a status that `executeFail()` set, but nothing blocked an EXPIRED item from being cancelled or extended. The metrics counted it as active. Children could be spawned from it. It was terminal in name, not in practice.

DELEGATED was stranger. The `delegate()` method set `PENDING` — so a delegated item and a freshly created item were indistinguishable from the outside. `DelegationState` tracked a separate PENDING/RESOLVED distinction, but `RESOLVED` was never set. The whole subsystem was describing a workflow that the code wasn't actually running.

ESCALATED was the most interesting case. `isTerminal()` returned true for it, which was correct design — an escalated item should require operator intervention. But no code path ever set `item.status = WorkItemStatus.ESCALATED`. The `executeEscalateTo()` path set `PENDING` and fired an `"ESCALATED"` lifecycle event, which conflated two different concepts: a status meaning "item is operationally stuck" and an event meaning "item was rerouted to a new pool." We'd named the event after the terminal state and then never used the terminal state.

Once I saw the pattern, the fixes were more design than code. For EXPIRED: add it to `isTerminal()` and accept the consequences — eight call sites now behave correctly. For DELEGATED: commit to the pre-acceptance semantic. `delegate()` now sets `DELEGATED`, there are `accept-delegation` and `decline-delegation` endpoints, and `DelegationState` is gone. For ESCALATED: define when it should actually occur — when all Chained SLA breach policy branches are exhausted — and wire it up. `BreachDecision.Exhausted` is now the fifth variant of the sealed interface, and `executeExhausted()` is the method that finally sets it.

The detail that sharpened the DELEGATED design was the decline path. When a delegatee says no, where does the item go? Back to the general pool? Back to whoever delegated it? We ended up with a `DeclineTarget` preference key (`casehub.work.delegation.decline-target`, default `POOL`) with an optional instance-level override at delegation time. The item keeps track of which path to take, and `declineDelegation()` reads it at decline time.

The whole change is 28 files, about 840 tests. None of it is particularly clever — it's mostly the work of deciding what those statuses should mean and then making the code mean it.
