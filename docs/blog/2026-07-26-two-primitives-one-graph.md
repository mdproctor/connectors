---
title: "Two Primitives, One Graph"
date: 2026-07-26
tags: [casehub, engine, unified-execution-model, planning, architecture]
projects: [casehub-engine]
entry_type: note
subtype: diary
status: draft
---

The unified execution model spec sat for eleven days after its adversarial review. Thirty-five issues raised, twenty-three verified, ten accepted. The spec was solid. The implementation plan existed. What was missing was someone sitting down and cutting code.

## The Two-Primitives Thesis

Everything in the execution model reduces to two kinds of PlanItem. A Primitive dispatches a single worker. A Compound contains children and selects which to dispatch via a named strategy. That's it. No third type. Every orchestration model we catalogued — sequential pipelines, HTN decomposition, GOAP, supervisor patterns, debate rounds, blackboard systems — maps to one of these or a composition of both.

The architectural bet: if we get the sealed hierarchy right, the entire planning infrastructure simplifies. Stages become a configuration of Compound (choreography strategy, ALL completion semantics). Sequential pipelines become a Compound with a sequential strategy. HTN becomes a Compound whose strategy decomposes and creates child Compounds. The type system does the work.

## Where the Plan Was Wrong

The implementation plan had a circular dependency bug. It said "add engine-common as a dependency of engine-api" to make DagPlan available to blocks. But engine-common already depends on engine-api. The fix was obvious once you see it: move DagPlan, DagNode, and JoinType TO engine-api. They're plan-definition types — they belong alongside ExecutorRef and Binding, not alongside DagDriver and the execution infrastructure.

The plan also assumed ChoreographyLoopControl could simply be deleted. It can't — blackboard depends on runtime, and runtime can't depend on blackboard without creating a Maven cycle. The `@DefaultBean` pattern solved it cleanly: ChoreographyLoopControl becomes the fallback that yields to PlanningStrategyLoopControl when the planning module is on the classpath. Same pattern as NoOpWorkerProvisioner.

Both corrections are the kind of thing you only find by implementing. The spec was right about the model; the plan was wrong about the dependency graph.

## What Got Built

The sealed hierarchy: `PlanItemDefinition` with `Primitive` and `Compound` records. I chose `PlanItemDefinition` over the plan's `NewPlanItem` — it encodes the domain concept (immutable plan definition) rather than a migration artifact name. `DispatchMode` (ORCHESTRATED / CHOREOGRAPHED / HYBRID) and `CompletionSemantics` (ALL / M_OF_N / FIRST_WINS) are companion types.

`PlanItemExecutionState` externalises the CAS-guarded state machine that currently lives inside the mutable PlanItem class. The separation is the point — plan definition is immutable by construction, execution state is mutable and CAS-guarded. They're different concerns conflated in the current code.

`CompoundCompletionEvaluator` walks the parent chain upward. When a child completes and its parent's CompletionSemantics is satisfied, the parent completes — and the evaluator keeps walking. Three-level nesting propagates correctly. FIRST_WINS completes on the first terminal child and cancels siblings.

`CompoundStrategyDispatcher` groups eligible bindings by their containing compound, resolves each compound's strategy by name, and delegates. Free-floating bindings (not in any compound) route through the default choreography strategy. Different compounds in the same case can use different strategies — the per-compound dispatch model is uniform from root to leaf.

## What Remains

The sealed hierarchy exists alongside the old mutable PlanItem. No consumers have migrated. The Stage type is still distinct. The bridge between old and new — where PlanningStrategyLoopControl actually calls CompoundStrategyDispatcher — isn't wired yet. Phases 4 through 8 in the plan cover DAG unification in blocks, HTN promotion, composable strategy wiring, agent dispatch, and the Quarkus Flow backend.

The plan is detailed enough to continue from here. The foundations are tested and the dependency graph corrections are captured in a protocol.
