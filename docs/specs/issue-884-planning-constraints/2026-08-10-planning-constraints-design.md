# Planning Constraints — Cost Budgets, Priority Weights, Feasibility Audit

**Issue:** casehubio/engine#884
**Parent:** casehubio/engine#881
**Companion:** casehubio/blocks#100 (AgenticDecompositionContext constraint threading)
**Date:** 2026-08-10

## Background

`PlanningConstraints` exists on `engine-api` with `timeBudget` (Duration) and `resourceLimit` (Integer), both enforced at pattern execution time. A `weights` map (Map<String, Double>) is parsed from YAML but is dead code — no production code reads it. The issue asks for cost budgets, priority-based step pruning, static decomposition constraint awareness, and test coverage.

## Scope

Engine-only. The blocks-side change (threading constraints through `AgenticDecompositionContext`) is tracked in casehubio/blocks#100.

## Changes

### 1. PlanningConstraints — add costBudgets

Add `Map<String, Integer> costBudgets` to the `PlanningConstraints` record. Keyed by cost dimension name (e.g. `"tokens"`, `"apiCalls"`). Abstract cost units — the enforcement layer interprets what a unit means.

```java
public record PlanningConstraints(
    Duration timeBudget,
    Integer resourceLimit,
    Map<String, Double> weights,
    Map<String, Integer> costBudgets) {

  public PlanningConstraints {
    weights = weights != null ? Map.copyOf(weights) : Map.of();
    costBudgets = costBudgets != null ? Map.copyOf(costBudgets) : Map.of();
  }

  public static PlanningConstraints unconstrained() {
    return new PlanningConstraints(null, null, Map.of(), Map.of());
  }

  public static PlanningConstraints of(Duration timeBudget, Integer resourceLimit) {
    return new PlanningConstraints(timeBudget, resourceLimit, Map.of(), Map.of());
  }
}
```

Backward compatibility: the existing `of(Duration, Integer)` factory passes empty maps for both `weights` and `costBudgets`. Call sites using this factory continue to compile. The canonical constructor gains a 4th parameter, so direct constructor calls (e.g. `CaseDefinitionYamlMapper` line 613) must be updated to pass `costBudgets` — this is covered by the YAML parsing changes in §5.

### 2. LLM prompt constraint text — extend buildConstraintText()

`LlmDecompositionStrategy.buildConstraintText()` currently renders `timeBudget` and `resourceLimit`. Extend to also render:

- **costBudgets:** One line per entry, key humanized. Example: `"- Token budget: 5000. Plan steps that stay within this token budget."`
- **weights:** Priority signal for the LLM. Example: `"- Priority weights: speed=0.8, quality=0.2. Prioritize steps aligned with higher-weighted dimensions. If constraints force trade-offs, keep steps serving high-weight priorities."`

The method signature and return type are unchanged — it still returns a `String` appended to the user prompt.

**Guard logic update:** The existing early return guard checks only `timeBudget` and `resourceLimit`:
```java
if (constraints.timeBudget() == null && constraints.resourceLimit() == null) {
  return "";
}
```
This must be extended to also check `costBudgets` and `weights`:
```java
if (constraints.timeBudget() == null && constraints.resourceLimit() == null
    && constraints.costBudgets().isEmpty() && constraints.weights().isEmpty()) {
  return "";
}
```
Without this, a case with only `costBudgets` or only `weights` set silently produces no constraint text.

### 3. ForwardReplanRevision — include constraint text

`ForwardReplanRevision.revise()` builds a user prompt but does not include constraint text. Add constraint rendering sourced from `context.adaptationContext().definition().getPlanningConstraints()`.

The constraint text rendering logic is simple (~20 lines). Two options:
- Extract to a shared `ConstraintTextBuilder` utility in `engine-common`
- Duplicate the string builder in both classes

Recommendation: duplicate the rendering logic in both classes. `LlmDecompositionStrategy` is in `io.casehub.engine.planning.decomposition` and `ForwardReplanRevision` is in `io.casehub.engine.planning.adaptation` — different packages, so package-private sharing is not possible. The logic is ~20 lines of string building — duplication is cleaner than introducing a public utility class for this.

### 4. Constraint feasibility audit event

When `DefaultGoalDecomposer.decomposeGoal()` produces an empty plan (`validNodes.isEmpty()`) and the definition has hard constraints, write a `CONSTRAINTS_INFEASIBLE` EventLog entry.

The feasibility check tests for hard constraints only — `timeBudget != null || resourceLimit != null || !costBudgets.isEmpty()`. The `weights` map is excluded because weights are advisory priority signals, not feasibility constraints — they cannot make a plan infeasible. Add a `hasHardConstraints()` convenience method to `PlanningConstraints` for this check.

Scope: only the `validNodes.isEmpty()` path emits this event. The other two early-return paths (`!isLinearChain` and `availableBindings.isEmpty()`) are structural failures (v1 linearity constraint, binding scope exhaustion) not caused by planning constraints.

New enum value in `CaseHubEventType`: `CONSTRAINTS_INFEASIBLE`.

Metadata: `goalName`, `strategyId`, and each non-null/non-empty hard constraint field (`timeBudget`, `resourceLimit`, `costBudgets`).

No behavioral change — the existing early return and warning log remain. This adds a structured audit signal only.

### 5. YAML parsing

**`CaseDefinitionYamlMapper`** — extend the `spec.planningConstraints:` block to parse `costBudgets`:

```yaml
spec:
  planningConstraints:
    timeBudget: PT30M
    resourceLimit: 3
    costBudgets:
      tokens: 5000
      apiCalls: 10
    weights:
      speed: 0.8
      quality: 0.2
```

Parse `costBudgets` as `Map<String, Integer>` from the JSON node, same iteration pattern as `weights`.

**`PatternWorkerFunctionProvider`** — extend `pattern.constraints:` parsing to read `costBudgets`:

```yaml
pattern:
  type: debate
  constraints:
    timeBudget: PT30M
    resourceLimit: 3
    costBudgets:
      tokens: 5000
```

Change from `PlanningConstraints.of(timeBudget, resourceLimit)` to full constructor with `costBudgets` map.

Note: pattern-level constraints intentionally omit `weights` — patterns use predefined `ExecutionModel` types rather than LLM decomposition, so priority weights have no consumer at this level.

### 6. Cost enforcement — v1 scope

v1 is prompt-hint-only. `buildConstraintText()` renders cost dimensions into the LLM prompt so the planner generates cost-aware plans. No runtime metering or enforcement.

When runtime enforcement is added later, the enforcement mode will be a per-case field on `PlanningConstraints` (co-located with constraint values), not a global config property.

## What this does NOT change

- `PatternWorkerFunctionHandler.resolveTimeout()` and `applyResourceLimit()` — these enforce `timeBudget` and `resourceLimit` at execution time and are unchanged. `costBudgets` has no runtime enforcement in v1.
- `DecompositionContext` interface — no change needed. `constraints()` default method returns `PlanningConstraints.unconstrained()`.
- `GoalDecompositionContext` — already carries `PlanningConstraints` and overrides `constraints()`. The new `costBudgets` field flows through automatically because it's on the record.
- `StaticDecomposition` (blocks) — no change in engine. Blocks#100 threads constraints through `AgenticDecompositionContext`.
- `DecompositionMethod` — no resource metadata added. Methods are not pre-filtered by cost. The issue's request for static decomposition to "prune methods whose resource requirements exceed available constraints" requires resource metadata on `DecompositionMethod` — tracked as deferred work (see below).

## Deferred work

- **Static decomposition method pruning:** Issue #884 asks for `StaticDecomposition` to prune methods by resource requirements. This requires adding `estimatedCost`/`estimatedDuration` metadata to `DecompositionMethod` (blocks API change). Deferred — constraints flow through to sub-strategies via `DecompositionContext.constraints()` (after blocks#100), but pre-filtering methods by cost before guard evaluation is not yet possible. Tracked as casehubio/blocks#101.

## Test plan

### PlanningConstraintsTest (extend)
- `costBudgetsMapIsUnmodifiable`
- `ofFactoryWithCostBudgets` (new convenience factory if added)
- `fullConstructorSetsAllFieldsIncludingCostBudgets`
- `hasHardConstraintsReturnsTrueForTimeBudget`
- `hasHardConstraintsReturnsTrueForCostBudgets`
- `hasHardConstraintsReturnsFalseForWeightsOnly`
- `hasHardConstraintsReturnsFalseWhenUnconstrained`

### LlmDecompositionStrategyTest (extend)
- `includesConstraintCostBudgetsInPrompt` — capture prompt, assert cost dimension text
- `includesWeightsInPrompt` — capture prompt, assert priority weight text
- `omitsConstraintTextWhenAllNull` — no constraint section when unconstrained
- `rendersCostBudgetsOnlyWithNoTimeBudgetOrResourceLimit` — guard allows cost-only constraints through

### PatternWorkerFunctionHandlerTest (extend)
- `costBudgetThreadedThroughPatternFunction` — verify `PatternWorkerFunction` carries cost budgets

### GoalDecompositionContextTest (extend)
- `costBudgetsThreadedThroughConstraints` — round-trip through `constraints()`

### DefaultGoalDecomposerTest (new or extend)
- `emitsConstraintsInfeasibleEventWhenEmptyPlanWithConstraints` — verify `CONSTRAINTS_INFEASIBLE` EventLog
- `noInfeasibleEventWhenEmptyPlanWithoutConstraints` — existing behavior preserved

### ForwardReplanRevisionTest (extend)
- `includesConstraintTextInRevisionPrompt` — capture prompt, assert constraint text from definition

### CaseDefinitionYamlMapperTest (extend)
- `parsesCostBudgetsFromPlanningConstraints` — YAML round-trip

### PatternWorkerFunctionProviderTest (new)
- `parsesCostBudgetsFromPatternConstraints` — provider reads costBudgets from YAML node

## Decisions

See `specs/issue-884-planning-constraints/decisions.md` for the full decision log (D1–D7).
