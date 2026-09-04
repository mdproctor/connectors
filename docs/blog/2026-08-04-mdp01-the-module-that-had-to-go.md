---
title: "The module that had to go"
date: 2026-08-04
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-work]
tags: [notification, subscription-engine, observer-pattern, module-deletion]
publish: false
---

# CaseHub Work — The Module That Had to Go

**Date:** 2026-08-04
**Type:** phase-update

---

## What I was trying to achieve: unify notification architecture

CaseHub Work had a parallel notification system. `work-notifications` shipped its own `NotificationDispatcher`, its own rule store, its own Slack/Teams/webhook channel implementations — all duplicating what the platform subscription engine already provides. The platform overlap-risks doc literally called it out as issue #4: "must converge."

Two issues framed the work. #316 asked for an observer pattern so label changes can trigger side-effects beyond labelling. #315 asked to migrate the notification infrastructure to the platform subscription engine. Design before migrate.

## What we believed going in: the hard part would be the migration

I expected #315 — ripping out the notifications module and wiring the subscription bridge — to be the complex part. A module deletion with Flyway migration moves, API type removal, and a new bridge class touching platform internals.

#316 felt simpler. Fire a CDI event when labels change. Straightforward.

## The observer pattern had a trap

The reentrancy problem was the interesting part. `LabelRuleEngine` already has a `ThreadLocal<Boolean>` guard against recursive evaluation — garden entry GE-20260421-cd3f95 documents the exact scenario. But when you add a `LabelChangeEvent` that observers can react to, the question becomes: when do you fire it?

If you fire after `evaluate()` returns, the RUNNING guard is cleared. An observer that modifies the WorkItem and triggers a lifecycle event will re-enter label evaluation. Back to infinite recursion.

The fix: fire the event *inside* the guard. While RUNNING is still true, any observer that somehow triggers `FilterEvaluationObserver` → `LabelRuleEngine.evaluate()` hits the guard and returns immediately. The event fires in a window where re-entry is structurally impossible.

We simplified `LabelDelta` to just `(path, changeType)` after the design review caught that two of the four original fields were structurally defective — `persistence` was always INFERRED (the engine only touches inferred labels), and `appliedBy` was unavailable for removals because the label object is already gone when the diff runs.

## The subscription bridge was two lines of real logic

`WorkItemLifecycleEvent` already had `type()` and `tenancyId()`. Adding `implements SubscribableEvent` was literally one line. The bridge observer is a CDI `@Observes(during = AFTER_SUCCESS)` that calls `dataSourceRegistry.resolveSource()` and `ds.add(event)`.

The design review caught the critical detail: the old `NotificationDispatcher` had three layers of error defence — `@Transactional(NOT_SUPPORTED)`, `CompletableFuture.runAsync()`, and per-channel try-catch. The new bridge had zero. An unhandled exception from the DataSource would kill sibling `AFTER_SUCCESS` observers — the ledger, the SSE broadcaster, everything. A migration regression hiding behind a correct `TransactionPhase` annotation.

## Deleting 1,733 lines felt right

The notifications module was 12 production classes, 5 test classes, a REST API, an RLS policy applicator, and three Flyway migrations. All replaced by one 96-line bridge class and one interface addition.

The Flyway migrations needed care — delete the module's files and existing databases fail validation. We moved V3000-V3002 to runtime for history continuity, then added V3003 to drop the table.

## What's next

The `LabelChangeEvent` seam has zero callers today. That's by design — #316 was raised during adversarial review of #314 as a forward-looking gap. When someone needs "label `priority/urgent` applied → set `workItem.priority = URGENT`", the seam is ready. No SPI, no registration, just `@Observes LabelChangeEvent`.

The subscription bridge activates only when `casehub-platform` is on the classpath. `Instance<DataSourceRegistry>.isUnsatisfied()` means the bridge is invisible to deployments that don't need it. Platform convergence without platform coupling.
