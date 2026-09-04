---
layout: post
title: "The Mapper That Wasn't Needed"
date: 2026-08-25
entry_type: note
subtype: diary
projects: [casehubio/engine]
tags: [jackson, deserialization, type-system, case-definition, design]
series: issue-422-ts-programming-model
---

# The Mapper That Wasn't Needed

The [previous entry](2026-08-25-mdp01-one-model-to-rule-them-all.md) ended with a clear next step: retire the 1900-line `CaseDefinitionYamlMapper` by teaching Jackson to deserialize `CaseDefinition` directly. The mapper existed because Jackson couldn't handle the polymorphic types — triggers, binding targets, expression evaluators, goal compositions — without help. But "help" had calcified into a hand-written conversion layer between generated POJOs and the real API model.

The question was whether you could give Jackson the help it needed without polluting the domain types with serialization annotations.

## The protocol

I wanted a clean separation: domain types carry metadata annotations (`@JsonPropertyDescription` for schema generation, `@JsonIgnore` for non-serializable fields) but never behavior annotations (`@JsonCreator`, `@JsonDeserialize`, `@JsonTypeInfo`). Behavior annotations couple the type to a specific serialization strategy. When the type IS the schema source, that coupling is a liability — every format or toolchain change forces changes to the domain types.

The alternative: a Jackson `SimpleModule` that registers custom deserializers externally. The domain types don't know they're being deserialized. The module knows how.

## Eight deserializers, one ordering constraint

The interesting design problem wasn't any individual deserializer — each follows the same pattern (read tree, dispatch on structure, delegate to registered deserializers for nested types). The interesting part was the dependency between them.

A `Binding` carries a `CapabilityTarget` that references a `Capability` by name. The capability must exist before the binding can resolve it. In the old mapper, this was trivial — it parsed capabilities first, built a lookup map, and passed the map to the binding conversion method. In a Jackson module, deserializers don't know about each other.

The solution: `DeserializationContext.setAttribute()`. The `CaseDefinitionDeserializer` parses capabilities first, builds the `CapabilityTarget` map, and stores it as a context attribute. When Jackson reaches the bindings array, `BindingDeserializer` reads the map from the same context. If the map isn't there (standalone binding deserialization), it falls back to a placeholder capability. The ordering constraint is expressed through data flow, not control flow.

## CaseDefinitionSpec — the extraction that cleaned up

The model had accumulated 32 spec-level field declarations on `CaseDefinition` that duplicated what lived on `CaseDefinitionSpec`. The spec object existed but half its fields were shadowed by direct declarations on the parent. Every getter delegated to spec, but the dead fields sat there creating confusion about which was authoritative.

Removing them was 125 lines of dead code. Three fidelity gaps surfaced in the process — `contextType`, `expressionLang`, and `goalToEffectKeys` — fields that existed in the YAML schema but had no path from YAML to the Java model. Closing them was straightforward once visible.

## What one line buys you

```java
CaseDefinition def = yamlMapper.readValue(yaml, CaseDefinition.class);
```

That line replaces a pipeline that went: YAML → ObjectMapper → generated `io.casehub.model.CaseDefinition` → `CaseDefinitionYamlMapper.convertToApiModel()` → `io.casehub.api.model.CaseDefinition`. Two class hierarchies, a 1900-line mapper, and a build-time code generator. The module collapses all of it into a single Jackson registration.

The old path still works — nothing was removed. The module is additive. But it's the path that TypeScript definitions need: write YAML in TypeScript, send it to the engine, and the engine deserializes directly into its API types. No intermediate POJOs, no mapper, no generated code in the hot path.

The npm package for `@casehub/engine-sdk` is next — the TypeScript types exist, the deserialization path exists, now the two need to meet.
