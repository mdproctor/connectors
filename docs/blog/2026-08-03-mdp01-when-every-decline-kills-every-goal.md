---
layout: post
title: "When every decline kills every goal"
date: 2026-08-03
type: phase-update
entry_type: note
subtype: diary
projects: [CaseHub Engine]
tags: [goal-abandonment, routing, eidos, ledger]
---

The goal abandonment work looked straightforward on paper: track DECLINE signals per goal, threshold, abandon. The APIs are there — eidos ships goals and constraints as first-class fields on `AgentDescriptor`, and `BehavioralSignalStore` already records signals keyed by agent, capability, and qualifier. The infrastructure should have been routine.

The interesting part was the gap I didn't see until I started implementing.

## The mapping problem

When a worker declines a task, the engine knows the agent, the capability, and the outcome. It does not know which goal the agent was pursuing. Goals live on the `AgentDescriptor` — the agent's own aspirations. Capabilities are what the agent can do. The engine dispatches by capability, not by goal.

Without a goal-to-capability mapping, every decline increments every goal. An agent with three goals that declines five times has all three goals at count five. All abandoned at the same rate. This is indistinguishable from capability-level exclusion — which `CapabilityHealth` already handles.

The value of goal abandonment is discrimination: agent X pursues goal A but has given up on goal B. Without the mapping, that discrimination doesn't exist. The feature degrades to a duplicate of something the platform already provides.

Claude and I built the infrastructure anyway — `GoalAbandonmentEvaluator` queries `BehavioralSignalStore` using a `__goal__` sentinel as the capability name, with goal names as qualifiers. `GoalFailureRecorder` records DECLINE signals on non-success outcomes, wired into `WorkflowExecutionCompletedHandler` at the semantic failure path. Both use `Instance<BehavioralSignalStore>` with `isResolvable()` guards — transparent no-op when eidos runtime isn't on the classpath. The threshold is configurable (`casehub.engine.goal.abandonment-threshold`, default 5). I filed #860 for the goal-capability mapping that would make it actually useful.

## Breaking what deserves breaking

The other piece was `OutcomeRecorder.addAttestation` in the ledger. The original issue proposed keeping `void record()` unchanged and adding a separate `UUID recordAndReturnId()`. Two methods that do the same thing, differing only by return type.

Pre-release, the right answer is simpler: change `record()` to return UUID. The void return was throwing away information the implementation already had — the entry ID is assigned during save. No production callers exist outside the ledger repo. The break costs nothing and the API is cleaner for it.

`addAttestation()` appends to an existing entry, inheriting `subjectId` from the entry and resolving the attestor from config defaults. The repository's `saveAttestation()` already fires `AttestationRecordedEvent`, so `IncrementalTrustUpdateObserver` recomputes trust scores without any additional wiring.

The goal-capability mapping is where this gets genuinely interesting. The simplest version — tag each `AgentGoal` with the capabilities that serve it — would let the recorder narrow its signals to the right goals. When that lands, goal abandonment stops being a blunt instrument and starts being a per-goal adaptive mechanism. The infrastructure is ready for it.
