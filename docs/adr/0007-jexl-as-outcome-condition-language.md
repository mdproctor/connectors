# 0007 — Use JEXL as the condition expression language for outcomes

Date: 2026-06-06
Status: Accepted

## Context and Problem Statement

`Outcome.condition` requires an expression language to evaluate runtime
conditions against WorkItem context. Several expression languages are available
in the casehub-work classpath.

## Decision Drivers

* Must work within existing classpath without new dependencies.
* Must support property access on a `Map<String,Object>` context.
* Template authors need a human-readable, approachable syntax.
* Consistent with existing expression evaluation in the codebase.

## Considered Options

* **JEXL (Apache Commons JEXL 3)** — already used for filter rules in
  `JexlConditionEvaluator`; `silent(true).strict(false)` profile established.
* **JQ** — available in `queues/` module but not `runtime/`; JSON-focused,
  unfamiliar to most Java developers.
* **SpEL (Spring Expression Language)** — not in the classpath; would add a
  Spring dependency to a Quarkus-native project.
* **Custom DSL** — too much investment for two feature-level use cases.

## Decision Outcome

Chosen option: **JEXL**, because `runtime.filter.JexlConditionEvaluator` is
already a CDI bean used for filter rules with an established evaluation profile
(`silent(true).strict(false)`, `workItem.*` context map). Outcome conditions
reuse the same evaluator and the same context shape, adding only three
extra variables (`resolution`, `reason`, `actorId`). No new dependency; consistent
expression syntax across all template-author-facing evaluation points.

### Positive Consequences

* Zero new Maven dependencies.
* Same expression syntax for filter rules and outcome conditions — template authors
  learn one language.
* `JexlConditionEvaluator` (runtime.filter) is independently testable.

### Negative Consequences / Tradeoffs

* `silent(true)` swallows all errors including syntax mistakes and null navigation —
  both produce a silent false return. A broken condition permanently blocks the
  outcome with no diagnostic beyond "condition not satisfied (check template definition)".
* Syntax validation at template creation time is deferred (not in scope for this issue).

## Links

* Refs casehubio/work#177
* ADR-0006 — Evaluate outcome conditions at completion time
