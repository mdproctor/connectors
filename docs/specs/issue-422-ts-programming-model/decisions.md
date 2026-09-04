## D1: Reflection over JavaParser

**Choice:** Use Java reflection for the model-canonical generator (both SchemaWriter and future TypeScriptWriter)
**Alternatives:**
- JavaParser — parses source files for richer info (Javadoc, builder patterns), but adds complexity (source file paths, CombinedTypeSolver for cross-repo types) without capability reflection doesn't already provide
- Hybrid (JavaParser for engine, reflection fallback for cross-repo) — two code paths for no gain
**Rationale:** Java 17+ reflection handles everything: records via `getRecordComponents()`, sealed interfaces via `getPermittedSubclasses()`, generics via `getGenericType()`, Jakarta Validation annotations (runtime-retained). Cross-repo types resolve naturally from the classpath. Descriptions come from annotations (`@JsonPropertyDescription`), not Javadoc.
**Trade-offs:** No Javadoc access — but descriptions via annotations are explicit, versioned, and don't rot. Acceptable trade.
**Sources:** CaseDefinition.java (1019 lines, references types from 4 repos), CasehubRuleFactory.java (existing codegen), spec 2026-08-23-typescript-programming-model-design.md
**Exploration:** quick
**Status:** captured

## D2: victools/jsonschema-generator as foundation

**Choice:** Use victools/jsonschema-generator library for Java → JSON Schema generation
**Alternatives:**
- Custom reflection walker — full control but reimplements what victools already handles (records, sealed interfaces, generics, Jakarta Validation, Jackson annotations). More code, more edge cases.
**Rationale:** Battle-tested library purpose-built for this. Custom modules for domain-specific rules (Worker extension point, CaseCompletion typed map, expression evaluator pattern, trigger/target naming). Handles most work out of the box once types are aligned.
**Trade-offs:** Library dependency. Acceptable — victools is well-maintained and widely used.
**Sources:** victools/jsonschema-generator GitHub, CasehubRuleFactory.java
**Exploration:** quick
**Status:** captured

## D3: @JsonPropertyDescription for schema descriptions

**Choice:** Add `@JsonPropertyDescription` annotations to model type fields to source JSON Schema `description` values
**Alternatives:**
- Companion metadata file — separate YAML/JSON file with descriptions; keeps model clean but creates a second maintenance surface
- Exclude descriptions from equivalence — defer descriptions, test structure only; faster initial delivery but weaker schema output
**Rationale:** Jackson annotation read automatically by victools. Descriptions stay with the code (single source of truth). One-time migration: copy existing hand-written schema descriptions to annotations.
**Trade-offs:** Adds annotations to model types — some noise, but the annotations are the description, not boilerplate.
**Depends on:** D2 (victools reads these annotations natively)
**Sources:** CaseDefinition.yaml (1344 lines of hand-written schema with descriptions), CaseDefinition.java
**Exploration:** quick
**Status:** captured

## D4: New 'generator' module alongside codegen

**Choice:** Create `engine/generator/` as a new Maven module. Both old codegen and new generator run during the parallel validation period.
**Alternatives:**
- Replace codegen in-place — simpler module structure but no parallel run period; must be correct immediately
**Rationale:** Parallel run catches edge cases the structural equivalence test might miss. Clean retirement: delete codegen/ when ready. Generator module depends on api module — Maven compiles api first, generator runs reflection on compiled classes.
**Trade-offs:** Two modules temporarily. Short-lived — codegen is deleted once equivalence is proven.
**Sources:** codegen/ (2 files), schema/pom.xml (exec-maven-plugin integration)
**Exploration:** quick
**Status:** captured

## D5: One-off alignment refactoring — close the two-class gap

**Choice:** Refactor Java model types to align with the YAML schema structure as a prerequisite. For each naming or structural conflict, choose the semantically most correct name regardless of which side it originated on. After alignment, Java is the single canonical source.
**Alternatives:**
- Custom modules for every mismatch — functional but makes the generator a thin wrapper around hand-crafted schemas (~9 modules covering ~70% of schema surface)
- Walk the generated POJOs instead of API model types — circular (schema → POJOs → schema) and preserves the two-class gap
**Rationale:** The two-class gap is the root problem. Both reflection and JavaParser fail equally against misaligned types. Fixing the root makes the generator trivial. Pre-release platform — breaking changes cost nothing.
**Trade-offs:** Cross-repo changes required (worker-api: Worker, Capability). One-off mechanical work via IDE refactoring.
**Depends on:** D1 (reflection needs aligned types to work)
**Sources:** Design review R1-01, R1-03 (structural mismatch findings), Worker.java (worker-api), Capability.java (worker-api), CaseDefinition.java (engine api)
**Exploration:** deep-analysis (surfaced by design review)
**Status:** captured

## D6: CaseDefinitionModule — full CaseDefinition deserialization

**Choice:** Single `CaseDefinitionModule extends SimpleModule` with custom deserializers for all polymorphic types in CaseDefinition. Full scope — covers both spec-level and top-level deserialization. Post-deserialization validation stays separate. Runtime wiring (ContextBridge creation, expression registry) stays at the call site.
**Alternatives:**
- Spec-only scope — stop at CaseDefinitionSpec deserialization; leaves top-level CaseDefinition to the mapper. Partial implementation that delays the eventual goal.
- Per-type modules — separate modules per polymorphic type (TriggerModule, BindingTargetModule, etc.). Over-engineered for a single consumer.
**Rationale:** The goal is full round-trip fidelity for CaseDefinition through YAML/JSON. A single module centralizes all structural mapping rules. The protocol (PP-20260825-7ad4b1) mandates externalized deserialization — no behavior annotations on domain types.
**Trade-offs:** Large module surface (6 custom deserializers + mixins + property name mappings). Acceptable — the complexity is inherent in the polymorphic types, not in the module structure.
**Depends on:** D5 (alignment refactoring — types must be aligned for direct deserialization)
**Sources:** PP-20260825-7ad4b1 (Jackson externalized serialization protocol), CaseDefinitionYamlMapper.java (existing manual deserialization logic), CaseDefinition.java, CaseDefinitionSpec.java
**Exploration:** quick
**Status:** captured
