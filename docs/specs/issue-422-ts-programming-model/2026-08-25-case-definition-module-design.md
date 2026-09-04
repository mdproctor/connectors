# CaseDefinitionModule — Externalized Jackson Deserialization

## Summary

A Jackson `SimpleModule` subclass that registers custom deserializers for all polymorphic types in `CaseDefinition`, enabling direct `objectMapper.readValue()` deserialization from YAML/JSON. Follows protocol PP-20260825-7ad4b1 — no behavior annotations on domain types.

## Context

`CaseDefinitionYamlMapper` currently handles all deserialization manually: reads raw `JsonNode` trees, resolves polymorphic types by inspecting property names, and calls builders/setters. This works but prevents direct Jackson deserialization — which blocks TypeScript round-trip (write YAML in TS, deserialize directly in Java) and makes the `io.casehub.model.*` generated POJOs the only path from YAML to `CaseDefinition`.

The `CaseDefinitionModule` is the first step toward retiring both the generated POJOs and the manual mapping logic in `CaseDefinitionYamlMapper`.

**Dependency:** This module depends on D5 (alignment refactoring) being complete. Property name mappings (§Property Name Mappings) only work once Java field names are aligned with the YAML schema. Until then, the module and the mapper coexist — the module handles what it can, the mapper continues to handle the rest.

## Module Location

`api/src/main/java/io/casehub/api/model/converter/CaseDefinitionModule.java`

The module accepts runtime dependencies via its constructor — Jackson modules are plain objects, not CDI beans:

```java
var registry = Arc.container().instance(ExpressionEngineRegistry.class).get();
ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
mapper.registerModule(new CaseDefinitionModule(registry));
CaseDefinition def = mapper.readValue(yaml, CaseDefinition.class);
```

Constructor parameters:
- `ExpressionEngineRegistry` (nullable) — for non-JQ expression resolution. When null, only JQ expressions are supported (sufficient for most YAML-first definitions).

## Custom Deserializers

### 1. TriggerDeserializer

Named-property dispatch. Reads the single property key and delegates:

| YAML key | Java type |
|----------|-----------|
| `contextChange:` | `ContextChangeTrigger` |
| `schedule:` | `ScheduleTrigger` |
| `scopeActivated:` | `ScopeActivatedTrigger` |

Error on zero or multiple keys. Unknown keys produce a clear error message naming the valid options.

### 2. BindingDeserializer

Full binding deserialization — not just target dispatch. A `Binding` has ~20 fields beyond the target type. The deserializer handles:

**Target dispatch** (exactly one of these YAML keys → `BindingTarget`):

| YAML key | Java type | Notes |
|----------|-----------|-------|
| `capability:` (string) | `CapabilityTarget` | Deferred resolution — stores the capability name as a string. `CapabilityTarget` construction requires resolving the name against `CaseDefinition.capabilities` to get the `Capability` record. This happens in a post-deserialization pass (see §Post-Deserialization Resolution), not during Jackson parsing. |
| `subCase:` (object) | `SubCaseTarget` | Delegates to Jackson for nested `SubCase` record |
| `humanTask:` (object) | `HumanTaskTarget` | Delegates to Jackson for nested `HumanTask` fields |
| `signal:` (object) | `SignalTarget` | Inline map payload |

`ExtensionTarget` excluded — engine-internal.

**Trigger field** — `on:` is the YAML key for the trigger. The deserializer reads it as a nested object and delegates to `TriggerDeserializer`.

**Expression fields** — `when`, `inputProjectionOverride`, and other expression-typed fields use `ExpressionEvaluatorDeserializer` via the registered module.

**All other fields** — `name`, `lifecycleScope`, `executionMode`, `participation`, `outcomePolicy`, `producedKeys`, `contextWrite`, `conflictResolverStrategy`, etc. — deserialized by Jackson's default property handling.

### 3. ExpressionEvaluatorDeserializer

Two-form pattern:
- String → `JQExpressionEvaluator(string)` when `expressionLang` is `"jq"` (default), or delegates to `ExpressionEngineRegistry.create(string, expressionLang)` for other languages
- `{lang: expr}` single-entry map → resolved via `ExpressionEngineRegistry` when available, or `JQExpressionEvaluator` when lang is `"jq"`

**Context propagation:** The definition-level `expressionLang` is set as a `DeserializationContext` attribute by `CaseDefinitionDeserializer` before delegating to nested deserializers. `ExpressionEvaluatorDeserializer` reads it via `ctxt.getAttribute(EXPRESSION_LANG_KEY)`, falling back to `"jq"` when absent.

When `ExpressionEngineRegistry` is null (module constructed without one), non-JQ languages throw `JsonMappingException` with a message explaining the registry is required.

### 4. CaseCompletionDeserializer

Two mutually exclusive patterns:
- `doneWhen: <expression>` → `PredicateBasedCompletion(ExpressionEvaluator)` — presence of `doneWhen` key selects this path
- Goal-kind map → `GoalBasedCompletion`:
  ```yaml
  completion:
    success:
      allOf: [goal-a, goal-b]
    failure: goal-c
  ```
  Keys are goal kind names. Built-in kinds (`success` → `StandardGoalKind.SUCCESS`, `failure` → `StandardGoalKind.FAILURE`) have implicit terminal status. Custom kinds require explicit `status:` declaration (per `GoalBasedCompletion` generalization, engine#582). Values are `GoalExpression`. `doneWhen` is reserved as a kind name.

Error when both `doneWhen` and goal-kind keys are present.

### 5. GoalExpressionDeserializer

Recursive composition:
- String → `SingleGoalExpression(name)`
- Array of strings → `AllOfGoalExpression` (shorthand)
- `{allOf: [...]}` → `AllOfGoalExpression` with recursive children
- `{anyOf: [...]}` → `AnyOfGoalExpression` with recursive children

Children can be strings or nested `{allOf/anyOf}` objects. Goal name validation (checking that named goals exist in the definition) is NOT done during deserialization — it's a post-deserialization validation concern.

### 6. SubCaseMappingDeserializer

Same two-form pattern as ExpressionEvaluator:
- String → `Expression(JQExpressionEvaluator(string))`
- `{lang: expr}` map → `Expression(resolved evaluator)`

`SubCaseMapping.Lambda` is Java-DSL-only and cannot be deserialized from YAML/JSON. The deserializer only produces `SubCaseMapping.Expression` variants.

## Worker Deserialization

`Worker` is a Java record with `function` as a canonical constructor parameter. Jackson records deserialization requires all constructor parameters. A mixin `@JsonIgnore` cannot suppress a record component — it only affects property serialization, not the canonical constructor.

Solution: `WorkerDeserializer` custom deserializer that:
1. Reads all YAML properties (`name`, `capabilities`, `executionPolicy`, `description`)
2. Constructs `Worker` via `Worker.builder()` (not the canonical constructor)
3. Skips `function` entirely — YAML workers get `WorkerFunction.NONE` (external workers)
4. Plugin-specific blocks (`agent:`, `do:`, `mcp:`, `a2a:`, `react:`, `pattern:`) are preserved as raw `JsonNode` on the `Worker`'s additional properties (via `additionalProperties: true` in the schema) for downstream `WorkerFunctionProvider` resolution

`GoapAction` mixin remains valid — `GoapAction` is NOT a record, so `@JsonIgnoreProperties({"costFunction"})` works.

## Property Name Mappings

Simple renames where YAML key ≠ Java field name. Handled via mixin `@JsonProperty` annotations:

| YAML key | Java field | On type |
|----------|-----------|---------|
| `cbr` | `cbrConfig` | `CaseDefinitionSpec` |
| `adaptation` | `adaptationConfig` | `CaseDefinitionSpec` |
| `reflection` | `reflectionTrigger` | `CaseDefinitionSpec` |
| `monitoring` | `monitoringConfig` | `CaseDefinitionSpec` |
| `layers` | `layerNames` | `CaseDefinition` |
| `episodic` | `episodicMemoryConfig` | `CaseDefinition` |

## Structural Mappings

These are NOT simple renames — they involve structural transformation:

| YAML structure | Java field | Handling |
|---------------|-----------|---------|
| `on: {contextChange: {...}}` | `Binding` trigger field | `BindingDeserializer` reads `on` key, delegates to `TriggerDeserializer` |
| `context: {storeFactory: "..."}` | `contextStoreFactory` (flat String) | `CaseDefinitionDeserializer` reads nested object, extracts `storeFactory` value |

## Post-Deserialization Resolution

Some fields require cross-references that aren't available during streaming deserialization:

1. **CapabilityTarget capability resolution** — `capability: "process"` in YAML stores the name during deserialization. After the full `CaseDefinition` is deserialized, a resolution pass matches capability names against `CaseDefinition.capabilities` to construct the `Capability` record reference. This mirrors what `CaseDefinitionYamlMapper` does today.

2. **Goal name validation** — `GoalExpression` goal names are validated against `CaseDefinition.goals` post-deserialization.

3. **Signal name validation** — `InboundSignalMapping.signalName` checked against `CaseDefinition.signals` post-deserialization.

These are the same validations that `CaseDefinition.Builder.build()` performs today.

## Structural Mapping: CaseDefinition ↔ YAML

The YAML structure has identity fields at root and a nested `spec:` block:
```yaml
namespace: acme
name: my-case
version: "1.0.0"
spec:
  capabilities: [...]
  workers: [...]
  bindings: [...]
```

Java has `CaseDefinition` with a `CaseDefinitionSpec spec` field. Jackson handles this naturally — `spec` is a regular field with its own type. `CaseDefinitionDeserializer` handles the `context` structural mapping and sets `expressionLang` as a deserialization context attribute before delegating.

## What the Module Does NOT Handle

- **Validation** — signal name dedup, inbound mapping validation, worker service account type checks. These run post-deserialization via the same validation logic used by `CaseDefinition.Builder.build()`.
- **Runtime wiring** — `ContextBridge<T>` creation from `contextType`, expression registry selection from `expressionLang`. These are call-site concerns.
- **`SubCaseMapping.Lambda`** — Java-DSL-only, not representable in YAML/JSON.
- **DAG/HTN execution types** — `DagPlan`, `TaskNode`, snapshot types. These have their own serialization paths and are out of scope for this iteration.

## Implementation Strategy

Incremental — each deserializer is independently testable:

1. Module skeleton + `ExpressionEvaluatorDeserializer` (simplest, used everywhere)
2. `GoalExpressionDeserializer` (recursive, foundation for CaseCompletion)
3. `CaseCompletionDeserializer` (depends on GoalExpression)
4. `TriggerDeserializer` (independent)
5. `SubCaseMappingDeserializer` (same pattern as ExpressionEvaluator)
6. `WorkerDeserializer` (record handling, plugin block passthrough)
7. `BindingDeserializer` (most complex — target dispatch, trigger, expressions)
8. `CaseDefinitionDeserializer` (top-level — context structural mapping, expressionLang propagation)
9. Property name mixins + `GoapAction` mixin
10. Post-deserialization resolution pass + validation
11. Full `CaseDefinition` integration test against existing YAML fixtures

## Testing

Each deserializer gets unit tests with:
- Happy path (each variant)
- Error cases (invalid input, missing required fields, mutual exclusion violations)
- Edge cases (empty objects, null values, unknown properties)

Integration test: load existing YAML fixtures via both `CaseDefinitionYamlMapper` (old path) and `CaseDefinitionModule` (new path), assert the resulting `CaseDefinition` instances are structurally equivalent. This is a comparison test — not a round-trip test, since some Java constructs (lambdas, `ContextBridge<?>`) cannot round-trip through YAML.

## References

- PP-20260825-7ad4b1 — Jackson externalized serialization protocol
- `api/src/main/java/io/casehub/api/model/converter/CaseDefinitionYamlMapper.java` — existing manual deserialization (1882+ lines)
- `api/src/main/java/io/casehub/api/model/CaseDefinition.java` — domain type
- `api/src/main/java/io/casehub/api/model/CaseDefinitionSpec.java` — spec type
- D6 in `decisions.md` — scope decision
- casehubio/parent#422 — epic
- Light design review findings (2026-08-25) — CloudEventTrigger removal, ExpressionEngineRegistry injection, BindingDeserializer scope expansion, Worker record handling, expressionLang context propagation, post-deserialization resolution pass, SubCaseMapping.Lambda exclusion, structural vs property mapping separation
