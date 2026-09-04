---
title: "Why Your Case Definition Needs a Decomposition Strategy"
date: 2026-07-28
tags: [casehub, engine, unified-execution-model, planning, htn, decomposition, architecture]
projects: [casehub-engine]
entry_type: note
subtype: diary
status: draft
series: issue-60-unified-execution-model
---

*Part of a series on [#60 — unified execution model](https://github.com/casehubio/blocks/issues/60). Previous: [Delete the Duplicate, Promote the SPI](2026-07-28-mdp01-delete-duplicate-promote-spi.md).*

Most case management systems treat task dispatch as flat: a condition fires, a worker runs, the context updates. CaseHub's engine already goes further — compound PlanItems can contain children dispatched by a named strategy. But until now, the planning infrastructure couldn't express *how* a complex task decomposes into subtasks. The decomposition was invisible to the engine.

## What Decomposition Actually Means

HTN — Hierarchical Task Network — is a planning technique from AI. You give it a compound task ("investigate this fraud case") and a library of methods, each with a guard condition and a strategy. The planner picks the first method whose guard matches the current state and decomposes the compound into subtasks. Those subtasks might themselves be compound, decomposing recursively until you reach leaf tasks that a worker can execute.

This matters for case management because real case work is hierarchical. An AML investigation has phases (triage, evidence collection, decision). Each phase decomposes differently depending on what's been found. Triage might be a simple checklist. Evidence collection might fan out to parallel data source queries. The decision phase might need a sequential review chain with human approval gates. A flat dispatch model forces you to encode this structure as trigger conditions — possible, but the intent disappears into JQ expressions.

## From Library Pattern to Engine SPI

Blocks had this capability already. `DecompositionStrategy<T>` existed as a blocks-internal interface. `StaticDecomposition`, `HybridDecomposition`, and `LlmDecomposition` were concrete implementations. But they operated outside the engine — through blocks' own `OrchestratedDriver`, with no persistence, no recovery, no milestones, no SLA tracking. A decomposition that produced ten subtasks couldn't participate in the engine's case lifecycle.

The fix is to make decomposition an engine-level SPI. `DecompositionStrategy<T>` now extends `NamedStrategy` — the same interface that routing strategies, matching strategies, and context store factories implement. The engine's `StrategyResolver` discovers implementations by CDI bean ID. A YAML case definition can declare:

```yaml
spec:
  decompositionStrategy: llm
  planningStrategy: htn
```

The engine resolves `"llm"` to the `LlmDecomposition` CDI bean at runtime. The decomposition runs inside the case lifecycle — its subtasks become PlanItems with full persistence, retry policies, and outcome tracking.

## Why the Type Hierarchy Matters

`TaskNode<T>` is sealed: `LeafTask` (executable) or `CompoundTask` (decomposable, carries a list of guard-gated methods). But `LeafTask` is `non-sealed` — blocks defines `PrimitiveTask` (with preconditions and effects for GOAP) and `PlannedTask` (with LLM rationale) without engine-api knowing about them. The sealed hierarchy guarantees exhaustive matching in switch expressions. The non-sealed leaf guarantees extensibility.

`DecompositionContext<T>` follows the same principle. Engine-api defines the interface: `state()` and `depth()`. Blocks extends it with `AgenticDecompositionContext` adding the `agents()` field that LLM-based decomposition needs for prompt construction. The strategy casts to the richer type when it needs it — type-safe at the blocks boundary, minimal at the engine boundary.

## What This Opens Up

With decomposition as a first-class engine SPI, the next steps become straightforward. A GOAP (Goal-Oriented Action Planning) strategy — backward-chaining from a desired state through agent capabilities — is just another `DecompositionStrategy` bean. Disposition-aware routing that scores agents by personality profile is a `RoutingSignalProvider`. Neither requires engine changes. They're CDI beans that the strategy resolver discovers by name.

The deeper point: the engine doesn't need to know about AI planning techniques, personality models, or LLM prompt construction. It needs to know that a compound task decomposes into a DAG of leaf tasks, and that the decomposition is selected by a string ID from YAML. Everything else is a bean on the classpath.
