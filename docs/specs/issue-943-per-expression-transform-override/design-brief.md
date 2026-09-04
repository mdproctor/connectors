# Design Brief — Per-Expression Transform Override (#943)

**Issue:** casehubio/engine#943
**Branch:** `issue-943-per-expression-transform-override`
**Status:** Closed, landed on main

## Problem

Issue #925 added per-expression language override for boolean condition expressions (triggers, guards, goals, milestones), routing them through `ExpressionEngineRegistry.create()`. Data transform projections (`inputProjection`, `outputProjection`) were excluded — they were hardcoded to JQ via `JQEvaluator.eval()` calls scattered across handler classes. This meant projections could not use alternative expression languages (CEL, JavaScript, etc.) even though the registry infrastructure supported them.

## Decision

Migrate all projection fields from `String` (implicitly JQ) to `ExpressionEvaluator` (polymorphic, registry-resolved). The migration is bottom-up through the type system:

1. **CapabilityTarget** — gains `ExpressionEvaluator inputProjection` and `outputProjection` fields (was bare `String` from `Capability`). 1-arg convenience constructor wraps Capability strings in `JQExpressionEvaluator` for backward compat.

2. **Binding** — `inputProjectionOverride` changes from `String` to `ExpressionEvaluator`. `effectiveInputProjection()` takes `CapabilityTarget` and returns `ExpressionEvaluator`.

3. **SubCaseMapping.Expression** — changes from `String` to `ExpressionEvaluator`. `SubCaseMapping.of(String)` wraps in `JQExpressionEvaluator` for backward compat.

4. **WorkerScheduleEvent** — carries `ExpressionEvaluator` (was `String`).

5. **CaseDefinitionYamlMapper** — all projection fields resolve through `resolveExpression()`, enabling `{lang: expr}` map syntax.

6. **AgentConverter** — gains 4-arg overload with `ExpressionEngineRegistry` for agent projection resolution.

7. **Runtime handlers** — migrated from direct `JQEvaluator.eval()` / `evalJqAsMap()` to `ExpressionEngineRegistry.transform()` / `transformAsMap()` / `transformSingle()`.

## Alternatives Considered

**Wrapper approach** — keep `String` fields, wrap at the call site. Rejected: duplicates wrapping logic across ~10 sites, easy to miss one, and the type system can't enforce that all projections are registry-routed.

**ExpressionEvaluator on Capability (foundation tier)** — push the type change down to worker-api's `Capability` record. Rejected: Capability is foundation tier and should not depend on the expression engine; the wrapping belongs at CapabilityTarget (engine-api tier).

## Impact

- 21 files changed, 419 insertions, 172 deletions
- 7 commits (6 feat/refactor + 1 chore)
- Zero new test classes — existing tests updated for new constructor signatures
- Backward compatible: all String-based APIs wrap in `JQExpressionEvaluator`
- Dead code removed: `evalJqAsMap` methods deleted from 3 handler classes
