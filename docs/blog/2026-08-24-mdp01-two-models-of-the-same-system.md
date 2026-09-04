---
layout: post
title: "Two Models of the Same System"
date: 2026-08-24
entry_type: note
subtype: diary
projects: [casehubio/engine]
tags: [schema-generation, victools, json-schema, design]
series: issue-422-ts-programming-model
---

Reflection-generated JSON Schema and hand-written JSON Schema encode different mental models of the same type system. The generated schema sees the Java type graph — every field, every transitive dependency, every enum as a `$ref`. The hand-written schema sees the YAML DSL — a curated surface area where enums are inline, internal types are invisible, and property names match the authoring convention rather than the Java convention.

We started this session with 217 structural differences between the two. The obvious approach would have been to chip away at each one individually — fix diff 1, run test, fix diff 2, repeat. Instead I categorised all 217 into root causes and traced them to their sources.

Six root causes accounted for everything:

1. **Enum inlining.** Victools emits `$ref: "#/$defs/OutcomeAction"` for enum fields. The YAML schema inlines them as `{type: string, enum: [REROUTE, FAULT]}`. One module — `EnumInliningModule` — eliminated 32 diffs and 16 phantom `$defs` in one pass.

2. **Naming mismatches.** The `ExpressionEvaluator` Java interface maps to `ExpressionOrOverride` in the YAML schema. A single rename in post-processing, plus a tree walk to update all `$ref` targets, closed 8 diffs.

3. **Missing type definitions.** The hand-written schema defines 20 types — `GoalExpression`, `HumanTask`, `SubCase`, the trigger subtypes, all five LLM provider models — that victools never encounters because they're referenced by custom modules but not in the Java type graph. Post-processing adds them as hand-crafted `$defs`.

4. **Unwanted type definitions.** Victools reflects through every field type. `Map(String,Object)`, `CostFunction`, `PlanningConstraints`, `CompiledExpression(Map(String,Object),Boolean)` — 32 types that exist in Java but have no place in the YAML schema. Post-processing removes them.

5. **Property naming.** Java uses `replanHint`, `completionCriteria`, `inputProjection`. The YAML schema uses `replanAfter`, `condition`, `inputProjection`. Some are pending cross-repo renames in worker-api; the rest are bridged by post-processing renames.

6. **Validation constraints.** `title`, `minLength`, `maxLength`, `pattern`, `default`, `required` — metadata the hand-written schema carries but the Java model doesn't annotate. Post-processing handles them.

The `SchemaPostProcessor` grew into a single-pass transformer: rename defs, remove unwanted defs, add missing defs, extract the `spec` block as a `$ref`, clean properties, fix names, add constraints, set root metadata. It's mechanical JSON tree manipulation, but it bridges the entire gap between the two models.

The structural equivalence test is now fully green — zero diffs, `@Disabled` removed. The generator produces schema that is structurally identical to the 1344-line hand-written `CaseDefinition.yaml`.

What makes this interesting is the design question underneath: should the post-processor shrink over time as the Java model accumulates the right annotations and naming? Probably. Jakarta Validation annotations on `CaseDefinition` fields would let victools produce `minLength`, `maxLength`, `pattern` natively. `@JsonProperty` annotations would fix naming. The post-processor would contract to handling only the structural divergences — the things that are genuinely different between a type graph and a DSL surface.
