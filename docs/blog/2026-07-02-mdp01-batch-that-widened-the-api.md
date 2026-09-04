---
layout: post
title: "The Batch That Widened the API"
date: 2026-07-02
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-work]
tags: [spi, api, workitem, escalation, observer]
---

Seven issues, all small, all on one branch. The kind of session where the value isn't in any single change but in the cumulative surface area they open for downstream consumers.

The issues fell into three categories. First, the callerRef lookup fix — `WorkItemService.findByCallerRef()` was doing a linear scan in Java even though the store already had indexed query overrides. One-line delegation. But documenting the ordering semantics (#280) forced a real decision: when multiple WorkItems share the same callerRef (first expires, second created), which one wins? "Most recently created" is the only answer that makes callerRef round-tripping work reliably. That meant adding `ORDER BY createdAt DESC` to JPA and MongoDB store implementations and switching the default fallback from `findFirst()` to `max(Comparator.comparing(wi -> wi.createdAt))`.

Second, the SPI surface for casehub-desiredstate. Adding `payload` to `WorkItemRef` (#281) and `obsoleteByCallerRef` (#282) are both motivated by the same consumption pattern: PendingApprovalHandler stores a `planReference` in the payload at creation, needs to read it back through the SPI, and after processing a rejection, needs to mark the WorkItem as consumed so the next reconciliation cycle can proceed. The `obsoleteByCallerRef` implementation follows the established idempotent pattern from `cancel()` and `complete()` — catch `IllegalStateException`, check terminal, silent no-op.

Third, the two larger features. The `WorkItemObserver` SPI (#285) is the one I expect to get the most use. The runtime's `WorkItemLifecycleEvent` is a CDI event that carries the full `WorkItemEntity` entity — it cannot live in the API module without creating a circular dependency. The observer SPI solves this with a lightweight `WorkItemStatusEvent` record carrying only API-module types, dispatched synchronously from the emitter with per-observer exception isolation. casehub-engine's blocks module needs this for HumanAgent dispatch — completing a `CompletableFuture` when a work item reaches a terminal state.

The escalation endpoint (#284) required a design decision worth naming: `ESCALATED` is already a terminal status (meaning "all SLA breach retries exhausted"). Manual escalation is non-terminal — it re-routes the item to a new candidate group and returns it to `PENDING`. So a new `MANUALLY_ESCALATED` event type distinguishes actor-driven re-routing from the timer-driven terminal state. The code review caught that the initial implementation cancelled the expiry timer without rescheduling it — unlike `cancel` or `obsolete` (terminal, so timer cancellation is correct), escalation returns an active item that should still expire.

The MongoDB Panache API surfaced a build-time surprise: `PanacheQuery` doesn't expose a `.sort()` method. The correct API is `find(filter, sortDocument)` — passing the sort as the second parameter to `find()` rather than chaining it.
