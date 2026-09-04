---
layout: post
title: "One Model to Rule Them All"
date: 2026-08-25
entry_type: note
subtype: diary
projects: [casehubio/engine]
tags: [schema-generation, type-alignment, typescript, yaml-expansion, design]
series: issue-422-ts-programming-model
---

# One Model to Rule Them All

The [previous entry](2026-08-24-mdp01-two-models-of-the-same-system.md) ended with a question: should the post-processor shrink as the Java model accumulates the right annotations and naming? The answer turned out to be more direct — close the gap at the source and make the generated schema the only schema.

## Closing the two-class gap

Three fields had wrong names in the worker-api foundation types. `Worker.capabilityNames` when they ARE the capabilities. `Capability.inputSchema` when it's a JQ projection expression, not a schema definition. `Capability.outputSchema`, same issue. The YAML DSL had the right names all along — `capabilities`, `inputProjection`, `outputProjection`. The post-processor was bridging the mismatch with rename rules.

Pre-release platform. Breaking changes cost nothing. We renamed the record fields in worker-api, and IntelliJ propagated across both repos — 99 files, zero errors. The post-processor rename bridges were deleted. The generator now produces the correct names natively because the source types use the correct names.

## YAML expansion

The generated schema matched the hand-written one structurally, but the hand-written schema was missing 13 fields that existed on `CaseDefinition` in Java. The YAML mapper parsed most of them already — `adaptation`, `monitoring`, `reflection`, `planningConstraints`, `recoveryPolicy` — but the schema didn't declare them. Invisible to YAML authors and tooling.

The expansion added all 13 to both the generated schema and the post-processor: per-case reflection triggers, monitoring thresholds, adaptation presets, planning constraints with time budgets and cost budgets, recovery policies, portfolio decomposition config, GOAP action declarations, humanTask routing constraints, default quorum config. Four fields that the YAML mapper didn't parse yet — `goapActions`, `workerServiceAccountIds`, `humanTaskContextConstraints`, `humanTaskWorkloadConstraint` — got mapper parsing too.

Per-worker GOAP shorthand followed: `cost:`, `effect:`, `softDependency:` on worker definitions that auto-construct `GoapAction` objects at definition build time. A worker that declares its effects and costs is enough for the GOAP planner to build a plan.

## One schema

The hand-written `CaseDefinition.yaml` was 1344 lines of manually maintained JSON Schema. Every time a field was added to the Java model, someone had to remember to add it to the schema too. Drift was inevitable — the 13 missing fields proved it.

The generator now writes directly to `schema/src/main/resources/schema/CaseDefinition.yaml`. The committed file IS the generated output. A `SchemaDriftTest` compares the committed schema against a fresh generation on every build — any model change without regeneration fails CI. The old `StructuralEquivalenceTest` (which compared generated against hand-written) was retired. There is no hand-written schema to compare against.

`@JsonPropertyDescription` annotations on `CaseDefinition`, `Binding`, `Goal`, and `Milestone` fields feed descriptions through the generator into the schema. The same descriptions that appear in the YAML schema now originate from the Java source — single maintenance surface.

## The chain

The generated schema is a standard Draft 2020-12 JSON Schema. Anything that consumes JSON Schema can consume it. To prove this, `json-schema-to-typescript` produced 845 lines of TypeScript interfaces from the generated schema — type aliases, discriminated union types for enums, `required` vs optional markers, TSDoc comments from the `@JsonPropertyDescription` annotations.

```
Java model types (records, sealed interfaces, @JsonPropertyDescription)
  → victools/jsonschema-generator (8 custom modules + post-processor)
  → CaseDefinition.yaml (committed, jsonschema2pojo consumes it)
  → json-schema-to-typescript
  → CaseDefinition.ts (845 lines, type-safe interfaces)
```

The Java model is the single source. Changes to `CaseDefinition.java` flow automatically to the YAML schema, the jsonschema2pojo deserialization types, and the TypeScript interfaces. No manual synchronisation.

## What's next

The codegen module — `CasehubRuleFactory` with its Worker type reuse and typed `additionalProperties` rules — still stands between the generated schema and a clean build. Retiring it means refactoring the 1900-line `CaseDefinitionYamlMapper` to deserialize directly into API model types instead of the generated POJOs. That collapses the two-class problem entirely: one Java model, one schema, one TypeScript surface.

After that, the TS CDK — builder functions following the Pages DSL pattern that emit validated YAML from TypeScript. The generated types are the contract. The builder functions are the authoring surface.
