---
title: "The Gate That Blocked Everything"
date: 2026-08-02
author: Mark Proctor
tags: [casehub, engine, lifecycle-scopes, architecture, design]
---

# The Gate That Blocked Everything

The casehub engine has had scoped worker infrastructure for weeks — the types, the records, the registry, the YAML parsing, the completion suppression. All the plumbing. None of it worked.

Five open issues sat in the tracker, each marked "blocked on #823." Registration wasn't wired. Accumulated state wasn't threaded. Output was published to an event bus address with no consumer. The persistent execution model existed only as a `WorkerFunction.Persistent` interface in the worker-api module with no handler to run it. I'd been treating these as independent wiring tasks — wire registration here, thread state there, add a handler over there. Claude and I sat down to do them all.

## The root nobody was looking at

The first thing we did was trace the dispatch path from first principles rather than trusting the issue descriptions. Every binding dispatch flows through `PlanningStrategyLoopControl.select()`, which uses `PlanItem.tryMarkRunning()` as a CAS gate. The PlanItem transitions from PENDING to RUNNING on first activation. For scoped workers, it stays RUNNING for the scope's entire lifetime — compound or case.

`tryMarkRunning()` returns `false` for an already-RUNNING PlanItem.

Which means every subsequent `CONTEXT_CHANGED` event skips the binding entirely. The registry check in `publishWorkerSchedule()` — the one that's supposed to route context changes to existing sessions — never executes. The dispatch path is dead before it reaches the registry.

None of the five issues mentioned this. Each one described its own gap (registration, state threading, output handling) without noticing that all of them shared the same root blocker: the dispatch gate was never designed for re-entrant bindings.

The fix is three lines. Allow scoped bindings with `COMPOUND` or `CASE` scope to re-dispatch when their PlanItem is already RUNNING. The registry check downstream handles the actual routing decision — mailbox for persistent, re-schedule for reinvoked.

## Two execution models, one handler extension point

REINVOKED workers re-execute on each context change with accumulated state from the prior invocation. PERSISTENT workers run indefinitely on a virtual thread, receiving context changes via a blocking mailbox.

The interesting architectural question was where registration should happen. `ScopedWorkerRegistry` lives in the runtime module. `QuartzWorkerExecutionJob` lives in scheduler-quartz. Scheduler-quartz depends on engine-api and engine-common — not on runtime. The module boundary is load-bearing; adding a reverse dependency would create a cycle.

For REINVOKED, registration happens in `CaseContextChangedEventHandler` (runtime module) right after agent routing succeeds. The session stores the selected worker's name so re-invocations skip routing entirely — the spec says "agent routing: first activation only."

For PERSISTENT, the question was harder. The session needs a mailbox and a running virtual thread. The thread only exists after the Quartz job starts execution. But the Quartz job can't access the registry.

The answer was already in the codebase: `WorkerFunctionHandler`. The handler extension point is in runtime, called by `DefaultWorkerExecutor` (also runtime), invoked by the Quartz job through CDI. A new `PersistentWorkerFunctionHandler` in runtime has full access to the registry. It retrieves the pre-registered session (mailbox created at dispatch time), builds a `DefaultPersistentScope` backed by that mailbox, and spawns the virtual thread. No module boundary changes needed.

Pre-registering the mailbox at dispatch time — before the Quartz pipeline runs — closes a context-change loss window. Any `CONTEXT_CHANGED` arriving between dispatch and thread startup queues on the mailbox rather than being lost. The handler picks up the pre-created mailbox from the registry when the thread starts.

## The feedback loop that almost wasn't

REINVOKED workers write output to the case context, which triggers `CONTEXT_CHANGED`, which re-evaluates bindings, which re-dispatches the scoped binding, which re-invokes the worker with the new context — and its own output is now part of that context.

If the worker's output changes keys within its input projection, the cycle is infinite.

We break it with input-hash comparison. Before re-dispatching a REINVOKED worker, the handler computes a hash of the projected input and compares it against the hash from the last invocation. If unchanged, the re-invocation is suppressed — the output changed context keys outside the worker's input projection, so re-invoking with identical input would produce identical output.

This is a design constraint, not a safety net. Workers whose output modifies keys within their own input projection must converge. Workers that oscillate infinitely are a binding design error.

## What $45 of adversarial review caught

We ran the spec through a four-dimension adversarial review before writing any code. The reviewers found things I'd missed:

The `ConflictResolver` API in the spec used a 3-arg method signature that doesn't exist — the actual API is per-key, 4-arg. The spec's `PersistentWorkerFunctionHandler` constructed a `ScopedWorkerSession` with `metadata.workerName()` in the `planItemId` position — a positional argument bug that would have compiled fine and silently stored the wrong value. The `planItemId` field itself turned out to have zero references anywhere in the codebase; every consumer uses binding name. We replaced it with `executorName`.

The robustness dimension found the per-binding execution lock gap. Two concurrent REINVOKED Quartz jobs for the same binding can execute in parallel — `CaseEvaluationSerializer` serializes evaluations, but dispatched Quartz jobs run asynchronously. Without a lock, both read the same accumulated state and last writer wins. A per-binding `ReentrantLock` in the registry serializes the read-execute-write sequence.

The structure dimension caught the registration asymmetry in the original design. REINVOKED sessions were registered at dispatch time. PERSISTENT sessions were registered at execution time (in the handler). This created a race window for PERSISTENT — concurrent `CONTEXT_CHANGED` events between dispatch and handler execution find an empty registry. Making registration symmetric (both at dispatch time, with PERSISTENT pre-creating the mailbox) closed the window for both session types.

## Where this lands

The scoped worker execution model is now production-wired. REINVOKED workers accumulate state across invocations with cycle detection. PERSISTENT workers run on managed virtual threads with mailbox-backed `PersistentScope`. Output flows through a dedicated handler with conflict resolution. Schedule triggers check the registry before dispatch.

Consumer repos can now declare `lifecycleScope: COMPOUND` or `lifecycleScope: CASE` on bindings and the workers actually persist. What's left is follow-on: durable accumulated state (survives JVM restart), persistent session recovery with mailbox replay, and the oscillation guard for pathological bindings.
