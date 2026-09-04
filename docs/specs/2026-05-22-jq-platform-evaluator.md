# Replace JqConditionEvaluator with JQEvaluator delegation
**Date:** 2026-05-22  
**Branch:** issue-207-jq-platform-evaluator  
**Issue:** casehubio/work#207

## Problem

`JqConditionEvaluator` calls `JsonQuery.compile()` on every invocation — no caching. Platform ships `JQEvaluator` (`casehub-platform-expression`) with a `ConcurrentHashMap` query cache and a shared root `Scope`. The local evaluator is now redundant.

## Change

**`queues/pom.xml`** — add compile dep:
```xml
<dependency>
  <groupId>io.casehub</groupId>
  <artifactId>casehub-platform-expression</artifactId>
</dependency>
```

**`JqConditionEvaluator`** — inject `JQEvaluator`, delegate execution:
- Remove `static ROOT_SCOPE` block and all `net.thisptr.jackson.jq.*` imports
- Add `@Inject JQEvaluator jqEvaluator`
- Replace `JsonQuery.compile(...) / scope / apply` with `jqEvaluator.eval(expr, input).isTrue()`
- `toMap(WorkItem)` stays unchanged — domain serialisation belongs here

**`JqConditionEvaluatorTest`** — replace `new JqConditionEvaluator()` with `@QuarkusComponentTest` wiring so `JQEvaluator.init()` runs correctly via CDI.

## What doesn't change

- `WorkItemExpressionEvaluator` SPI contract (`language()`, `evaluate()`)
- `language()` return value `"jq"` 
- CDI discovery via `FilterEvaluatorRegistry`
- `toMap(WorkItem)` serialisation

## Engine follow-up

casehubio/engine#314 and #320 track the same consolidation for engine's JQ evaluators.
