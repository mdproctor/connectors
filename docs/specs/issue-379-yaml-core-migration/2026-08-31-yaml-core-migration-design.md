# Migrate YAML Variable Resolution to yaml-core

**Issue:** #379
**Date:** 2026-08-31

## Problem

`WorkItemTemplateYamlLoader` and `SlaDefaultsYamlLoader` contain identical
copy-pasted `interpolate()` methods — regex-based `${env.*}` / `${sys.*}`
variable resolution with `:-default` syntax. This is a subset of what
casehub/platform's `yaml-core` `VariableResolver` provides.

## Decision

### D1: Scope — yaml-core improvements in platform

**Choice:** Add `:-default` syntax and built-in env/sys sources to yaml-core.
**Rationale:** Every consumer needs default values and env/sys resolution.
Adding to yaml-core avoids duplication across casehub-work, desiredstate,
and future consumers.
**Exploration:** quick

### D2: Error behaviour — fail-fast vs silent passthrough

**Choice:** Fail-fast via `UnresolvedVariableException`.
**Rationale:** Current loaders silently leave `${env.FOO}` as a literal string
when unresolved and no default is provided. yaml-core throws with available
prefixes listed. Fail-fast at startup is strictly better — catches typos and
missing env vars before they cause runtime surprises. The `:-default` syntax
provides the opt-out for genuinely optional variables.
**Exploration:** quick

## Changes

### yaml-core (casehub/platform)

1. **`VariableResolver` — default-value syntax**
   Parse `${prefix.name:-fallback}` in `lookupVariable()`. When source
   returns null for `name`, use `fallback`. When no `:-` and no value,
   throw as today.

2. **`VariableSource` — built-in factories**
   Add `VariableSource.env()` → `System::getenv`
   Add `VariableSource.systemProperty()` → `System::getProperty`

### casehub-work

1. **Add `casehub-platform-yaml-core` dependency** to runtime pom.xml
2. **Delete `interpolate()` and `VAR_PATTERN`** from both loaders
3. **Construct shared `VariableResolver`** with `env` and `sys` sources
4. **Deep-resolve parsed YAML** — `resolver.resolve(yamlMap)` replaces
   per-field `interpolate()` calls

## Out of Scope

- `AsyncApiResource` — raw bytes passthrough
- `BreachAction.parse()` — post-parse domain dispatch
- YAML file format changes — no user-facing impact

## References

- `io.casehub.yaml.core.resolver.VariableResolver` — platform yaml-core
- `io.casehub.yaml.core.resolver.VariableSource` — functional interface
- `WorkItemTemplateYamlLoader:115-131` — current interpolate()
- `SlaDefaultsYamlLoader:222-238` — duplicated interpolate()
