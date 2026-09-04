# Decisions — Planning Constraints (#884)

## D1: costBudget representation

**Choice:** `Map<String, Integer> costBudgets` on `PlanningConstraints` — keyed by cost dimension name (e.g., `"tokens"`, `"apiCalls"`)
**Alternatives:**
- Single `Integer costBudget` — abstract cost units with no defined semantics; cannot be rendered in the LLM prompt or matched by the enforcement layer without implicit coupling
- Richer `CostBudget` record with `tokenLimit`, `apiCallLimit` — couples PlanningConstraints to specific cost dimensions that vary by deployment
- `record CostBudget(CostUnit unit, int limit)` — typed single-dimension; restricts to one cost dimension per case
**Rationale:** Cost is genuinely multi-dimensional (tokens, API calls, cost units). A map keyed by dimension name provides: (1) natural-language rendering for the LLM prompt ("Token budget: 5000, API call budget: 10"), (2) enforcement layer matching (consume only keys it understands), and (3) extensibility without record changes. Follows the existing YAML structure pattern.
**Trade-offs:** Key semantics are convention-based rather than type-enforced. Acceptable because both the prompt renderer and enforcement layer are engine-internal — the contract is between engine components, not a public API.
**Exploration:** quick
**Status:** revised — R1-01 correctly identified that a unitless integer has no natural-language rendering and creates implicit coupling. Changed from `Integer costBudget` to `Map<String, Integer> costBudgets`.

## D2: Cost enforcement mechanism

**Choice:** Decomposition-time prompt hint only for v1 — `buildConstraintText()` renders cost dimensions from `costBudgets` map into the LLM prompt. No runtime enforcement in v1. When soft/hard enforcement is implemented, enforcement mode will be a per-case field on `PlanningConstraints`, not a global config property.
**Alternatives:**
- Global config property `casehub.engine.cost-enforcement=hint|soft` — creates semantic mismatch: per-case constraints with global enforcement mode
- Full `NamedStrategy` SPI with per-case selection — over-engineered for the current state
- Full runtime token metering — requires ChatModel wrapper, cross-module token accounting, mid-execution cancellation infrastructure that doesn't exist
**Rationale:** The only concrete enforcement point available today is the LLM prompt. `buildConstraintText()` already renders `timeBudget` and `resourceLimit` — extending it for `costBudgets` is consistent and deliverable. Runtime enforcement (soft iteration caps, hard token metering) requires infrastructure that doesn't exist. When that infrastructure arrives, enforcement mode belongs per-case on `PlanningConstraints` (co-located with constraint values), not as a global config property.
**Trade-offs:** v1 cost constraints influence planning only — no runtime enforcement. Acceptable as the first step; runtime enforcement is explicitly deferred, not accidentally missing.
**Exploration:** quick
**Depends on:** D1 (costBudgets field)
**Status:** revised — R1-02 and R1-03 correctly identified: (1) no concrete enforcement path existed for the "soft mode iteration cap," (2) global config creates per-case/global semantic mismatch. Changed from global config with hand-wavy modes to honest prompt-hint-only for v1 with per-case enforcement mode deferred.

## D3: Priority-based step pruning

**Choice:** Decomposition-time priority via the existing `weights` map on `PlanningConstraints` — wire weights into `buildConstraintText()` as priority signals in the LLM prompt
**Alternatives:**
- Separate `PriorityFunction` interface — adds a new SPI when the weights map already exists
- Post-decomposition filter that trims plans — risk of producing incoherent plans after trimming steps the LLM considered essential
- `List<String> capabilityPriority` (ranked from highest to lowest) — deterministic and testable, but less expressive than continuous weights for LLM-based decomposition
**Rationale:** The `weights` map exists on `PlanningConstraints` and is parsed correctly from YAML, but is currently dead code — no production code references `weights()` (only test assertions in `PlanningConstraintsTest` and `CaseDefinitionYamlMapperTest`). Wiring weights into `buildConstraintText()` is implementation work included in the scope of #884. Continuous weights (`Map<String, Double>`) are more expressive than ranked lists for LLM prompts — the LLM can reason about relative importance ("speed: 0.8, accuracy: 0.3" conveys that speed matters ~2.7x more than accuracy).
**Trade-offs:** LLM-interpreted weights are fuzzy, not deterministic. Acceptable — the weights are advisory signals to the decomposition LLM, not hard filters. Deterministic pruning can be layered post-decomposition if needed.
**Exploration:** quick
**Status:** revised — R1-04 correctly identified that `weights` is dead code: `buildConstraintText()` only handles `timeBudget` and `resourceLimit`; no production code reads `weights()`. Changed from "already wired" (false) to "needs wiring as part of #884 scope."

## D4: Static decomposition constraint awareness

**Choice:** Blocks companion issue casehubio/blocks#100 adds `PlanningConstraints` to `AgenticDecompositionContext`. Engine-side `GoalDecompositionContext` already overrides `constraints()` and works correctly.
**Alternatives:**
- Add `estimatedCost`/`estimatedDuration` metadata to `DecompositionMethod` — changes the blocks API, requires authors to estimate costs upfront
- Change `DecompositionMethod.guard` from `Predicate<T>` to `BiPredicate<T, PlanningConstraints>` — enables guard-based constraint rejection but is a blocks API break; defer to blocks#100
**Rationale:** The original D4 contained two factual errors: (1) it claimed constraints "flow through `DecompositionContext.constraints()`" to sub-strategies via pass-through — in reality, `AgenticDecompositionContext` does NOT override `constraints()` and inherits the default `unconstrained()`, silently dropping constraints; (2) it claimed "sub-strategies can reject themselves if constraints are infeasible" via guards — `DecompositionMethod.guard` is `Predicate<T>` receiving only state `T`, not constraints, making guard-based constraint rejection structurally impossible. The engine's `GoalDecompositionContext` correctly overrides `constraints()` (line 47), so goal decomposition in the engine works. The gap is blocks-only.
**Trade-offs:** Methods can't be pre-filtered by cost before guard evaluation. Guard signature change is deferred to blocks#100.
**Exploration:** quick
**Depends on:** D1 (costBudgets field)
**Status:** revised — R1-05 correctly identified both factual errors: AgenticDecompositionContext returns unconstrained(), and guards cannot see constraints. Corrected rationale; overall direction (blocks#100) unchanged.

## D5: Scope — engine + blocks companion issue

**Choice:** Engine-only implementation with blocks companion issue (casehubio/blocks#100) for `AgenticDecompositionContext` constraint threading
**Alternatives:**
- Engine-only, defer blocks entirely — leaves the agentic path without constraint awareness
**Rationale:** The `DecompositionContext.constraints()` default method means engine code works today. Blocks#100 closes the gap so agentic strategies actually receive constraints.
**Trade-offs:** Blocks change must land before agentic patterns see constraints in practice. In the intermediate state, goal decomposition (`DefaultGoalDecomposer` path) respects constraints because it uses `GoalDecompositionContext` which overrides `constraints()`. The agentic pattern path (`PatternWorkerFunctionHandler`) enforces `timeBudget` and `resourceLimit` at execution time but will not enforce `costBudgets` until blocks#100 lands — consistent with D2's v1 scope where cost constraints are prompt hints, not runtime-enforced.
**Exploration:** quick
**Status:** captured

## D6: Constraint feasibility validation

**Choice:** Emit a `CONSTRAINTS_INFEASIBLE` audit event when decomposition produces an empty plan and non-trivial constraints are active. No pre-decomposition feasibility check.
**Alternatives:**
- Pre-decomposition feasibility check — requires estimating minimum cost before decomposition, which depends on the strategy and available capabilities; the LLM is better positioned to determine feasibility during decomposition
- Silent failure (current behavior) — `DefaultGoalDecomposer` returns early and logs a warning, but there's no signal distinguishing "constraints made this infeasible" from "decomposition simply failed"
- Return a `FeasibilityResult` from `DecompositionStrategy.decompose()` — changes the return type, ripples through all implementations
**Rationale:** The distinction between "decomposition failed" and "constraints made decomposition infeasible" is diagnostically valuable. A post-decomposition check is simpler than pre-decomposition estimation: if the plan is empty and constraints are non-trivial (not `unconstrained()`), emit a specific event. The existing `EventLog` infrastructure and `CaseHubEventType` enum support this naturally.
**Trade-offs:** Can't distinguish "constraints made this infeasible" from "decomposition failed for unrelated reasons with constraints present." Acceptable for v1 — the event carries constraint metadata for human diagnosis.
**Exploration:** quick (surfaced by R1-10)
**Status:** captured

## D7: ForwardReplanRevision constraint awareness

**Choice:** `ForwardReplanRevision` must include constraint text in its LLM prompt, sourced from `context.adaptationContext().definition().getPlanningConstraints()`
**Alternatives:**
- Add `PlanningConstraints` as a field on `RevisionContext` or `AdaptationContext` — duplicates data already available via `definition().getPlanningConstraints()`
- Leave ForwardReplanRevision constraint-unaware — creates inconsistency where initial decomposition respects constraints but plan adaptation ignores them
**Rationale:** `LlmDecompositionStrategy.replan()` already includes constraint text via `buildConstraintText()`. `ForwardReplanRevision` is a parallel adaptation path that should maintain the same constraint awareness. The constraint data is already accessible through the existing `AdaptationContext.definition()` chain — no new fields needed, just prompt construction.
**Trade-offs:** Adds a dependency from ForwardReplanRevision to PlanningConstraints prompt rendering. Acceptable — the rendering logic can be shared or duplicated (simple string builder).
**Exploration:** quick (surfaced by R1-07)
**Status:** captured
