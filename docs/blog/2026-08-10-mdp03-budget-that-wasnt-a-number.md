---
layout: post
title: "The Budget That Wasn't a Number"
date: 2026-08-10
entry_type: note
subtype: diary
projects: [casehub-engine]
tags: [planning-constraints, cost-budgets, decomposition, llm-prompts]
---

Cost budgets looked simple at first glance. The issue asked for a `costBudget` field on `PlanningConstraints` — an integer, like `resourceLimit`. One number, one meaning.

The problem surfaces when you try to write the LLM prompt. "Cost budget: 5000." Five thousand *what*? Tokens? API calls? Dollars? The prompt renderer has to say something meaningful, and an abstract integer with no declared dimension says nothing. The enforcement layer has the same problem — it needs to know what it's counting to know when to stop.

I ended up with `Map<String, Integer> costBudgets`, keyed by dimension name. A deployment tracking tokens declares `tokens: 5000`. One tracking API calls declares `apiCalls: 10`. The prompt renderer iterates the map and writes natural language: "Token budget: 5000. Plan steps that stay within this token budget." The enforcement layer (when it eventually exists) matches only the keys it understands and ignores the rest.

The `weights` map on `PlanningConstraints` turned out to be dead code. It was parsed from YAML, stored on the record, and tested in two assertion methods — but no production code ever read it. Not the prompt builder, not the decomposition strategy, nothing. We wired it into `buildConstraintText()` as priority signals for the LLM: "Priority weights: speed=0.8, quality=0.2. Prioritize steps aligned with higher-weighted dimensions."

Wiring weights exposed a second gap. The existing guard in `buildConstraintText()` checked only `timeBudget` and `resourceLimit`:

```java
if (constraints.timeBudget() == null && constraints.resourceLimit() == null) {
  return "";
}
```

A case definition with only `costBudgets` or only `weights` — no time budget, no resource limit — would silently produce no constraint text at all. The LLM would never see the constraints. Claude caught this during the design review. We extended the guard to check all four fields.

The same kind of gap appeared with the feasibility audit. `DefaultGoalDecomposer` returns early and logs a warning when decomposition produces an empty plan. But there was no way to distinguish "decomposition failed because nothing matched" from "constraints made this infeasible." We added a `CONSTRAINTS_INFEASIBLE` event type — but only for hard constraints. `hasHardConstraints()` deliberately excludes `weights`, because priority weights are advisory signals to the LLM, not feasibility constraints. A plan can't be infeasible because of a priority preference.

`ForwardReplanRevision` had the same blind spot as `buildConstraintText()` — it builds LLM prompts for plan adaptation but included no constraint text at all. The adaptation path was generating revised plans with no awareness of the original constraints. The rendering logic is ~20 lines of string building; we duplicated it rather than creating a public utility class across the two different packages.

The deferred work is in blocks. `AgenticDecompositionContext` inherits `DecompositionContext.constraints()` but never overrides it — it always returns unconstrained. Engine's `GoalDecompositionContext` already overrides correctly, so goal decomposition respects constraints. But the agentic pattern path won't see constraints until blocks#100 lands. And static decomposition method pruning by resource requirements needs resource metadata on `DecompositionMethod` — tracked as blocks#101.
