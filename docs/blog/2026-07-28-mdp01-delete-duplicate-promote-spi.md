---
title: "Delete the Duplicate, Promote the SPI"
date: 2026-07-28
tags: [casehub, engine, unified-execution-model, planning, architecture, htn]
projects: [casehub-engine]
entry_type: note
subtype: diary
status: draft
series: issue-60-unified-execution-model
---

*Part of a series on [#60 — unified execution model](https://github.com/casehubio/blocks/issues/60). Previous: [Two Primitives, One Graph](2026-07-26-two-primitives-one-graph.md).*

Two phases of the unified execution model landed today, both following the same principle: if two things are structurally identical, one of them shouldn't exist.

## One Plan Type

Blocks had `ExecutionPlan<T>`. Engine had `DagPlan<T>`. Same record structure, same validation (cycle detection, dangling reference rejection, entry node check), same factories (singleton, sequence, parallel, sequentialMerge), same `JoinType` enum with the same two values. The only real difference: `ExecutionPlan` constrained its nodes to hold `LeafTask<T>`, while `DagPlan` was generic over any `T`.

The generic version is the right one. A DAG plan is structural — it defines execution order and join semantics. What flows through the nodes is a type parameter concern, not a plan concern. So blocks now uses `DagPlan<LeafTask<T>>` directly from engine-api, and `ExecutionPlan` is gone.

The cleanup also fixed a naming lie. Engine's `DagPlan.sequence(List<DagNode<T>>)` didn't create a sequence — it took pre-wired nodes and dumped them in a map. The real sequence factory (auto-wiring each node to depend on the previous) was in blocks. Now `fromNodes()` honestly names what the old method did, and `sequence(List<? extends T>)` does what a developer would expect: hand it tasks, get back a chain.

## Non-Sealed as a Module Boundary Tool

The HTN decomposition SPI promotion was the more interesting problem. `TaskNode` is a sealed interface: `LeafTask` (executable) and `CompoundTask` (decomposable). The decomposition strategy takes a `CompoundTask`, applies guard-gated methods, and produces a `DagPlan<LeafTask<T>>`. Engine should own this SPI — it's planning infrastructure. But blocks defines the concrete leaf types: `PrimitiveTask` (with preconditions and effects) and `PlannedTask` (with LLM rationale). Java sealed interfaces require permits in the same compilation unit.

The answer is `non-sealed`. `TaskNode` is sealed with two permits. `CompoundTask` is a closed record — task trees always have exactly two structural kinds. But `LeafTask` is `non-sealed` — any module can define concrete implementations without engine-api knowing about them. The sealed hierarchy says "a task node is either a leaf or a compound." The non-sealed leaf says "what kind of leaf is your problem."

`DecompositionContext` followed a similar principle. Engine-api defines an interface with `state()` and `depth()`. Blocks provides `AgenticDecompositionContext` adding the `agents()` field that LLM decomposition needs for prompt building. The strategy casts when it needs the richer type — safe because the builder always constructs the blocks-specific context.

The decomposition strategy now extends `NamedStrategy`, which means Phase 6 — composable strategy wiring via `StrategyResolver` — has all its types in place. The existing resolution mechanism that wires routing strategies can now wire decomposition strategies the same way.
