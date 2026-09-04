---
layout: post
title: "M-of-N parallel WorkItems and a rule that needed rewriting"
date: 2026-04-29
type: phase-update
entry_type: note
subtype: diary
projects: [quarkus-work]
tags: [multi-instance, cdi, jta, occ, threaded-inbox, architecture]
excerpt: "M-of-N parallel WorkItems ships after rewriting LAYERING.md — the rule \"if it touches another WorkItem it's orchestration\" was already broken by cascade cancellation, so the real test is whether it imposes ordering or requires external context."
---

Multi-instance WorkItems shipped today. Four-eyes approval, peer review, supermajority voting — all of it. The feature itself is straightforward in principle: spawn N child WorkItems from a template, complete the parent when M of them are done. What was less straightforward was whether this belongs in `quarkus-work` at all.

## The rule that was wrong

`quarkus-work` has a LAYERING.md that governs what the library owns versus what belongs in CaseHub. The rule I'd written said: if code says "when X happens, do Y to *another WorkItem*," that's orchestration — it belongs above.

By that rule, M-of-N completion is orchestration. "When M children complete, complete the parent" — clear violation. CaseHub already had `Stage.requiredItemIds` and autocomplete semantics for exactly this.

Except cascade cancellation already violates that rule. `SpawnPort.cancelGroup(cascadeChildren=true)` does exactly "do Y to another WorkItem" and it's in `quarkus-work`. The rule was already broken in practice, and I was using a broken rule to make an architectural decision.

The real test isn't "does it touch another WorkItem." It's two questions: does it impose ordering between distinct pieces of work? Does it require external context or domain knowledge to evaluate? Static M-of-N satisfies neither — the policy is declared at creation time, evaluated purely from member states. It's a group primitive, not orchestration.

I updated LAYERING.md before writing a line of code.

This also clarified `Stage.requiredItemIds` in CaseHub's own deep-dive. That mechanism is correct for heterogeneous named plan items — WorkItem A *and* Task B *and* milestone C. For homogeneous parallel instances, CaseHub should observe the parent's COMPLETED event and treat the group as atomic. Duplicating the tracking in both systems would have been the real mistake.

## The coordinator that wouldn't retry

The M-of-N counting uses OCC — `@Version` on `WorkItemSpawnGroup`, counters incremented by a `@Transactional` policy bean, `policyTriggered` boolean for exactly-once semantics. Standard stuff.

What tripped us up was Quarkus/Narayana's exception propagation. When the JTA commit fails on a version conflict, the `OptimisticLockException` doesn't propagate as `jakarta.persistence.OptimisticLockException`. It ends up wrapped somewhere in the Narayana commit path — the coordinator's `catch (OptimisticLockException e)` block never fires. The retry never runs. Parent WorkItems stayed PENDING until Awaitility gave up.

We caught it because Claude reported 627 passing tests after implementing the coordinator, and I ran the suite myself before marking the task done. The tests weren't passing. The coordinator test was timing out with the OCC visible only as a suppressed exception on `ConditionTimeoutException`.

The fix was to catch `Exception` broadly in the retry loop. Pair that with `policyTriggered=true` making retries idempotent, and the OCC issue becomes irrelevant to correctness — the second attempt either finds `policyTriggered=true` (another invocation already won) or succeeds on fresh data.

There was a second trap: `Event.fireAsync()` inside a `@Transactional` method dispatches immediately to the thread pool. It does *not* wait for the transaction to commit. A transaction that later rolls back has already delivered its event. We had `WorkItemGroupLifecycleEventTest.completedEventFiresExactlyOnceAtThreshold` receiving two COMPLETED events instead of one. The fix was to return the event object from the `@Transactional` policy method and have the non-transactional coordinator fire it after the method returns successfully.

## The inbox question

I'd designed the coordinator parent to be hidden from inboxes — COORDINATOR mode parents have no candidateGroups, no assigneeId, so routing queries ignore them. The question of how to prevent users from seeing them was already half-answered before I stopped and asked whether they *should* be hidden at all.

Email threads aren't hidden. They're the organising structure. Showing a coordinator parent as a thread root with aggregate stats — "3 of 5 approved" — is more useful than pretending it doesn't exist. The inbox change followed naturally: `GET /workitems/inbox` now always returns roots (`parentId IS NULL`) with aggregate stats. Standalone WorkItems are their own roots with `childCount=0`. Coordinator parents surface when users have visibility into at least one child.
