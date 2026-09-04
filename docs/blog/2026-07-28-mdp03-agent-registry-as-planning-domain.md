---
layout: post
title: "When your agent registry is already a planning domain"
date: 2026-07-28
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-engine]
tags: [goap, planning, agent-orchestration, disposition]
series: issue-60-unified-execution-model
---

*Part of a series on [#60 — Unified execution model](https://github.com/casehubio/blocks/issues/60). Previous: [Why your case needs decomposition](2026-07-28-mdp02-why-your-case-needs-decomposition.md).*

The unified execution model migration is done — all eight phases. But the interesting part wasn't the migration itself. It was what fell out at the end.

## The GOAP realisation

When I sat down to build `GoalOrientedDecomposition`, the classical AI planner for the agentic orchestration layer, I expected to need a new capability schema. GOAP needs actions with preconditions and effects — what an agent consumes and what it produces. I assumed eidos didn't have that yet.

It did. `AgentCapability` already carries `inputTypes` and `outputTypes` — string lists declaring what data types an agent consumes and produces. They were there for documentation and matching. But they're also a complete action specification for backward-chaining.

The algorithm is textbook regression planning. Start from the goal types. Find a capability that produces one. Check its input types. If they're not available, find capabilities that produce those. Build the dependency graph. The result is a `DagPlan` — a DAG where independent chains run in parallel and dependent chains are sequenced.

```java
// GOAP backward-chains through AgentCapability I/O types
var enricher = capability("enrich", List.of("raw-data"), List.of("enriched-data"));
var scorer = capability("score", List.of("enriched-data"), List.of("risk-score"));

// Goal: risk-score. Available: raw-data.
// GOAP finds: enricher → scorer (linear chain)
```

What makes this work isn't the algorithm — it's that the platform's agent identity model already spoke the language of classical planning. No adapter layer, no translation. `inputTypes` are preconditions. `outputTypes` are effects. The `DagPlan` from Phase 4 handles the rest.

## Disposition as a routing signal

The second feature — `DispositionAwareRouting` — was simpler but raised a design question I hadn't expected. eidos already models agent personality across five axes: social orientation, rule following, risk appetite, autonomy, conflict mode. The routing signal just scores candidates against a desired profile. The question was where the desired profile comes from.

I considered an SPI, a configuration object, even an LLM-inferred profile. The answer was simpler: case context. A convention-based key — `_routing.disposition.<capabilityName>` — lets domain repos declare what kind of agent they want for each capability. No new interface. The routing signal assembler picks it up automatically alongside trust scores and CBR signals.

## The execution backend split

Phase 8 was supposed to wire Serverless Workflow generation into the pattern builders. But tracing the dependency graph made the right boundary obvious: blocks defines `ExecutionBackend<T>` as a pluggable abstraction, and engine-flow provides the Serverless Workflow implementation. The SDK stays where it belongs — in the engine module that already has it. Pattern builders get a `backend()` method and `PatternType` metadata so the flow module knows whether a pattern is workflow-shaped.

The migration started as infrastructure housekeeping — renaming modules, sealing hierarchies, unifying plan types. It ended with classical planning working out of the box because the agent identity model was already well-designed. That's the kind of outcome you can't plan for; you can only notice when the pieces line up.
