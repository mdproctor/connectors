# Model-Canonical Schema Generator — Implementation Design

## Summary

Build a reflection-based generator that walks `io.casehub.api.model.CaseDefinition` and emits JSON Schema, reversing the current source-of-truth direction. Prerequisite: a one-off alignment refactoring to close the structural gap between the Java model types and the YAML schema, choosing the semantically best name for each conflict regardless of which side it originated on. After alignment, victools/jsonschema-generator produces the schema with minimal custom modules. Simultaneously expand YAML coverage to ~15 Java-only fields.

## Context

**Current flow (schema-canonical):**
```
CaseDefinition.yaml (hand-written, 1344 lines)
  → jsonschema2pojo (CasehubCodegen + CasehubRuleFactory)
  → io.casehub.model.* (~40 generated POJOs)
  → CaseDefinitionYamlMapper (1882 lines)
  → io.casehub.api.model.CaseDefinition (hand-written, 1019 lines)
```

**New flow (model-canonical):**
```
io.casehub.api.model.* (hand-written, aligned with schema structure)
  → victools/jsonschema-generator (reflection)
  → CaseDefinition.yaml (generated)
```

Two issues: engine#975 (alignment + generator + structural equivalence), engine#976 (YAML expansion).

## Decisions

- **D1: Reflection over JavaParser** — Java 17+ reflection handles records, sealed interfaces, generics, Jakarta Validation annotations. Cross-repo types resolve naturally from the classpath. No source file management needed.
- **D2: victools/jsonschema-generator** — Mature library for Java → JSON Schema via reflection. Custom modules for domain-specific rules. Handles most of the work out of the box once types are aligned.
- **D3: @JsonPropertyDescription** — Schema `description` values come from Jackson annotations on model type fields. One-time migration from existing hand-written schema descriptions.
- **D4: New generator module** — `engine/generator/` alongside `codegen/`. Parallel run until structural equivalence is proven, then retire codegen.
- **D5: One-off alignment refactoring** — Close the two-class gap between runtime model types and schema types. For each naming or structural conflict, choose the semantically most consistent, most intuitive, and most correct name — regardless of which side (Java or YAML) it originated on. After alignment, Java is the canonical source and schema is generated.

## Phase 0: Alignment Refactoring

The two-class gap must be closed before the generator can work. Today there are two parallel type hierarchies:

- `io.casehub.model.*` — generated from schema, field names match YAML
- `io.casehub.api.model.*` — hand-written, field names and structure differ from YAML

The refactoring aligns the API model types with the schema structure, choosing the semantically best name for each conflict.

### Naming Conflicts

For each conflict, the chosen name is the one that most accurately describes what the field IS:

| Java field | YAML property | Chosen name | Rationale |
|-----------|--------------|-------------|-----------|
| `Worker.capabilityNames` (`Set<String>`) | `capabilities` (array of strings) | `capabilities` | They ARE the capabilities this worker provides. "Names" is redundant — the type (string set) already says they're identifiers. |
| `Capability.inputSchema` | `inputProjection` | `inputProjection` | It's a JQ expression that PROJECTS data, not a JSON Schema. "Schema" is misleading. |
| `Capability.outputSchema` | `outputProjection` | `outputProjection` | Same rationale — it's a projection, not a schema. |

### Missing Fields

Fields that exist in the YAML schema but not on the corresponding Java type. These are declarative properties that belong on the type:

| Type | Field to add | Type | Why |
|------|-------------|------|-----|
| `Worker` | `sequence` | `List<String>` | Sequential composition of other workers — declarative, belongs on the type |
| `Worker` | `contextType` | `String` | Typed POJO input class name — declarative configuration |
| `Worker` | `outputType` | `String` | Typed POJO output class name — declarative configuration |

### Fields to Exclude from Schema

Fields that exist on Java types but are runtime constructs with no declarative representation:

| Type | Field | Exclusion | Why |
|------|-------|-----------|-----|
| `Worker` | `function` | `@JsonIgnore` | `WorkerFunction<?, ?>` is a Java lambda — runtime-only. YAML declares function type via plugin blocks (`agent:`, `do:`, `mcp:`, etc.) |

### Structural Nesting

The YAML schema groups ~20 fields under a `spec:` block. `CaseDefinition.java` has them flat. This isn't arbitrary — there IS a semantic distinction:

- **Identity** (top-level): `dsl`, `namespace`, `name`, `version`, `title`, `summary` — what this case IS
- **Specification** (under `spec:`): `capabilities`, `workers`, `bindings`, `goals`, `milestones`, `completion`, strategy IDs, etc. — what this case DOES
- **Configuration** (top-level): `context`, `episodic`, `signals`, `labelRules`, `inboundMappings`, `layers`, `use`, `semanticData`, `types`, `labels` — how this case is configured

Add a `Spec` inner class (or `CaseSpec` record) to `CaseDefinition` that groups the specification fields. Reflection then naturally produces the nested schema structure.

### Sealed Interface Mapping

The Java model uses sealed interfaces and marker interfaces for polymorphism. victools maps these to `oneOf` automatically:

| Java type | Schema pattern | Notes |
|-----------|---------------|-------|
| `CaseCompletion` (sealed: `GoalBasedCompletion`, `PredicateBasedCompletion`) | `oneOf` with discriminated variants | victools handles sealed interfaces natively |
| `Trigger` (marker interface, impls: `ContextChangeTrigger`, `ScheduleTrigger`, `ScopeActivatedTrigger`) | `oneOf` with named property branches | Needs `@JsonTypeInfo` or custom module for the existing naming pattern (`contextChange:`, `schedule:`, etc.) |
| `BindingTarget` (sealed: `CapabilityTarget`, `SubCaseTarget`, `HumanTaskTarget`, `SignalTarget`, `ExtensionTarget`) | `oneOf` with named property branches | `ExtensionTarget` excluded from schema (engine-internal). Custom module for existing naming pattern. |
| `GoalExpression` (sealed: `AllOfGoalExpression`, `AnyOfGoalExpression`, `SingleGoalExpression`) | `oneOf: [allOf array, anyOf array]` | Maps naturally |

### Expression Evaluator Mapping

Multiple fields across `Binding`, `Goal`, `Milestone`, `Trigger` are typed as `ExpressionEvaluator`. The schema represents these as `ExpressionOrOverride` — either a plain string or a `{lang: expr}` map for per-expression language override.

Custom victools module: when encountering `ExpressionEvaluator` fields, emit the `ExpressionOrOverride` `oneOf` pattern. This is a type-level override, not per-field.

### Cross-Repo Changes

Alignment requires changes to types in other repos:

| Repo | Type | Change |
|------|------|--------|
| worker-api | `Worker` | Rename `capabilities` → `capabilities`. Add `sequence`, `contextType`, `outputType`. `@JsonIgnore` on `function`. |
| worker-api | `Capability` | Rename `inputProjection` → `inputProjection`, `outputProjection` → `outputProjection`. |

These are the only cross-repo changes. All other type alignments are within the engine repo.

### Alignment Scope

The alignment is a one-off prerequisite. It touches:
- `io.casehub.worker.api.Worker` and `Capability` (worker-api repo)
- `io.casehub.api.model.CaseDefinition` — extract `Spec` inner class
- All callers of renamed fields (mechanical rename via IDE refactoring)
- `CaseDefinitionYamlMapper` — simplified where names now match directly

After alignment, the two type hierarchies converge. `io.casehub.model.*` (generated POJOs) becomes redundant and is retired with the codegen module.

## Module: engine/generator

### Dependencies

```xml
<dependencies>
  <!-- Reflects on these compiled classes -->
  <dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-engine-api</artifactId>
  </dependency>
  <!-- Generator library -->
  <dependency>
    <groupId>com.github.victools</groupId>
    <artifactId>jsonschema-generator</artifactId>
  </dependency>
  <dependency>
    <groupId>com.github.victools</groupId>
    <artifactId>jsonschema-module-jakarta-validation</artifactId>
  </dependency>
  <dependency>
    <groupId>com.github.victools</groupId>
    <artifactId>jsonschema-module-jackson</artifactId>
  </dependency>
  <!-- Test: structural equivalence + YAML validation -->
  <dependency>
    <groupId>com.networknt</groupId>
    <artifactId>json-schema-validator</artifactId>
    <scope>test</scope>
  </dependency>
</dependencies>
```

Maven compiles `api` first (dependency ordering), then `generator` reflects on compiled classes.

### Components

#### CaseHubSchemaGenerator

Main entry point. Configures victools:

- Schema version: Draft 2020-12 (matches existing schema)
- Modules: JakartaValidationModule, JacksonModule, plus custom modules below
- Entry type: `CaseDefinition.class`
- Output: YAML file (JSON Schema in YAML format, matching existing convention)

Configuration:
- `unevaluatedProperties: false` as the default for all object types (existing schema convention). Worker overrides with `additionalProperties: true`. `Spec` overrides with `unevaluatedProperties: true` (extension point for plugin modules). victools defaults to `additionalProperties` — a custom type attribute resolver emits `unevaluatedProperties` instead, matching the existing schema's Draft 2020-12 convention.
- Top-level required fields: `[dsl, namespace, name, version, spec]`
- `$schema`, `$id`, `title`, `description` set on the root schema to match existing values.

#### Custom Modules

After alignment, the custom modules needed are:

| Module | What it does | Why victools can't handle it alone |
|--------|-------------|-----------------------------------|
| `WorkerSchemaModule` | Emits `additionalProperties: true` for Worker | Worker is an extension point — plugin blocks (`agent:`, `do:`, `mcp:`) are open-ended |
| `CaseCompletionSchemaModule` | Emits `additionalProperties: { $ref: GoalExpression }` | Typed map pattern — `Map<String, GoalExpression>` needs specific `$ref`, not generic object |
| `ExpressionEvaluatorModule` | Maps `ExpressionEvaluator` fields to `oneOf: [string, {lang: expr}]` | Interface type needs schema-level pattern, not reflection of interface methods |
| `TriggerModule` | Maps `Trigger` sealed hierarchy to named-property `oneOf` | Existing YAML uses `contextChange:`, `schedule:` property names, not JSON discriminator |
| `BindingTargetModule` | Maps `BindingTarget` sealed hierarchy to named-property `oneOf` | Same named-property pattern. Excludes `ExtensionTarget` (engine-internal). |
| `UnevaluatedPropertiesModule` | Emits `unevaluatedProperties` instead of `additionalProperties` | Draft 2020-12 convention not default in victools |

The simple scalar fields, records, enums, validation annotations, nested objects, and sealed interfaces with standard discriminators are all handled by victools + JakartaValidationModule + JacksonModule.

### Build Integration

- exec-maven-plugin at `generate-resources` phase runs `CaseHubSchemaGenerator`
- Generated schema written to `generator/src/main/resources/schema/CaseDefinition.generated.yaml`
- Generated file committed to git (deterministic output)
- CI: regenerate + `git diff --exit-code` on generated file. Non-zero = model changed without regenerating.

### Structural Equivalence Test

`StructuralEquivalenceTest` compares the generated schema against the existing `schema/src/main/resources/schema/CaseDefinition.yaml`:

**Exclusions:** The existing schema has `_codegenAgent`, `_codegenAgentModel`, `_codegenOpenAi`, etc. — fake properties that force jsonschema2pojo to generate POJO types. These are codegen artifacts with no runtime meaning. The generated schema does not include them. The equivalence test strips `_codegen*` properties before comparison.

1. Parse both as Jackson `JsonNode` trees
2. Walk recursively, comparing:
   - Property names and nesting
   - Type declarations (`type`, `$ref`, `oneOf`, `allOf`, `anyOf`)
   - Required arrays (order-independent)
   - Validation constraints: `pattern`, `minimum`, `maximum`, `minLength`, `maxLength`, `enum`, `minItems`, `uniqueItems`
   - `additionalProperties` / `unevaluatedProperties`
   - `default` values
3. Description text compared after `@JsonPropertyDescription` migration is complete
4. Meaningful diff output on failure — shows the path, expected value, and actual value

### YAML Fixture Validation Test

`GeneratedSchemaValidationTest` runs all existing example YAML files against the generated schema using networknt json-schema-validator. Same approach as the existing `SchemaValidationTest` but loading the generated schema instead of the hand-written one.

## @JsonPropertyDescription Migration

One-time copy of existing schema descriptions onto model type fields. Scope:

- `CaseDefinition` / `Spec` — ~30 fields
- `Binding` — ~20 fields
- `Capability` — ~5 fields
- `Goal` — ~4 fields
- `Milestone` — ~6 fields
- `CaseCompletion` types — ~2 fields
- `OutcomePolicy` — ~4 fields
- `SubCase` — ~10 fields
- `HumanTaskTarget` — ~15 fields
- `Trigger` types — ~10 fields
- LLM model types (OpenAI, Anthropic, etc.) — ~40 fields total
- `GoalExpression`, `Use`, `LabelRule`, `InboundSignalMapping` — ~15 fields
- `Worker`, `Capability` (in worker-api after alignment) — ~10 fields

Total: ~170 annotations. Mechanical work — copy description text from YAML schema to Java annotation.

## YAML Expansion (engine#976)

### Fields to Add

Fields that exist on `CaseDefinition` Java model but have no YAML schema entry. All are declaratively expressible:

| Field | Type | YAML key |
|-------|------|----------|
| `goapActions` | `List<GoapAction>` | `goapActions:` (without `CostFunction` — static `cost` only) |
| `goalToEffectKeys` | `Map<String, Set<String>>` | `goalToEffectKeys:` |
| `planningConstraints` | `PlanningConstraints` | `planningConstraints:` |
| `monitoringConfig` | `MonitoringConfig` | `monitoring:` |
| `portfolioConfig` | `PortfolioConfig` | `portfolioConfig:` |
| `workerServiceAccountIds` | `Map<String, String>` | `workerServiceAccountIds:` |
| `defaultQuorum` | `QuorumConfig` | `defaultQuorum:` |
| `reflectionTrigger` | `ReflectionTriggerConfig` | `reflection:` |
| `humanTaskContextConstraints` | `List<ContextConstraint>` | `humanTaskConstraints:` |
| `humanTaskWorkloadConstraint` | `WorkloadConstraint` | `humanTaskWorkload:` |
| `maxAdaptations` | `Integer` | `maxAdaptations:` |
| `recoveryPolicy` | `RecoveryPolicy` | `recoveryPolicy:` |
| `memoryRetrieval` | `MemoryRetrievalConfig` | `memoryRetrieval:` |
| `adaptationConfig` | `AdaptationConfig` | `adaptation:` |

### Per-Worker/Per-Capability Annotations (from engine#976 acceptance criteria)

| YAML field | On type | Type | Description |
|-----------|---------|------|-------------|
| `cost:` | Worker/Capability binding | `double` | Static GOAP action cost |
| `effect:` | Worker/Capability binding | `Map<String, Boolean>` | GOAP action effects |
| `softDependency:` | Worker/Capability binding | `List<String>` | Optional preconditions |
| `customize:` | Case definition | `List<String>` | Named customiser IDs |

### Fields That Stay Java-Only

| Field | Why |
|-------|-----|
| `defaultWorkerBridge` | `ContextBridge<?>` — runtime object, not serializable |
| `GoapAction.costFunction` | `CostFunction` — `@FunctionalInterface`, requires code |
| `agentDescriptors` | `AgentDescriptor` from eidos — tracked in eidos#143 |

### Per-Field Work

For each field:
1. Add `@JsonPropertyDescription` annotation with description text
2. Update `CaseDefinitionYamlMapper` to parse from YAML raw node
3. Add YAML test fixture exercising the field
4. Verify existing tests still pass (backward compatible)

The generator automatically includes new fields in the generated schema.

## Issue Ordering

1. **Phase 0: Alignment** — Cross-repo renames (worker-api), `Spec` extraction (engine), `@JsonIgnore` on runtime-only fields. Mechanical IDE refactoring.
2. **engine#975: Generator** — Generator module + custom modules + structural equivalence test + `@JsonPropertyDescription` migration.
3. **engine#976: YAML expansion** — Add fields incrementally, one at a time. Generator picks them up automatically.

Phase 0 gates #975: the types must be aligned before reflection produces correct output.
#975 gates #976: the generator must produce a structurally equivalent schema before expanding it.

## What Stays

- `json-schema-validator` (networknt) — more valuable than before: validates the *generated* schema
- `CaseDefinitionYamlMapper` — simplified after alignment, but remains until full direct-deserialization migration (longer-term)

## What Gets Retired (after equivalence proven)

- `codegen/` module (2 files)
- exec-maven-plugin + build-helper-maven-plugin in `schema/pom.xml`
- ~40 generated `io.casehub.model.*` POJOs (replaced by aligned `io.casehub.api.model.*`)
- Hand-written `Worker.java` + `WorkerMarshaller.java` in schema module
- Hand-written `CaseDefinition.yaml` in `schema/src/main/resources/schema/` (replaced by generated)

## References

- `codegen/src/main/java/io/casehub/codegen/CasehubRuleFactory.java` — two domain rules to reproduce
- `codegen/src/main/java/io/casehub/codegen/CasehubCodegen.java` — existing codegen entry point
- `schema/src/main/resources/schema/CaseDefinition.yaml` — existing hand-written schema (1344 lines)
- `api/src/main/java/io/casehub/api/model/CaseDefinition.java` — canonical model (1019 lines)
- `api/src/main/java/io/casehub/api/model/converter/CaseDefinitionYamlMapper.java` — YAML mapper (1882 lines)
- `schema/src/test/java/io/casehub/model/SchemaValidationTest.java` — existing schema tests
- `specs/main/2026-08-23-typescript-programming-model-design.md` — roadmap spec (blocks workspace)
- `reviews/casehub-slots/issue-422-schema-generator-20260824-044035/` — design review (R1-01 through R1-12)
- victools/jsonschema-generator: https://github.com/victools/jsonschema-generator
- casehubio/parent#422 — epic
- casehubio/engine#975 — SchemaWriter issue
- casehubio/engine#976 — YAML expansion issue
