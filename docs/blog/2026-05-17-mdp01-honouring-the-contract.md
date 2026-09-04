---
layout: post
title: "Honouring the Contract"
date: 2026-05-17
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-engine]
tags: [java, quarkus, tdd, multi-repo]
---

`HumanTaskScheduleHandler` has had a stub in template mode for a while. The comment was honest: `// Template mode not yet implemented — leave PlanItem PENDING so binding stays eligible.` What was less obvious was that the API had already made a promise.

`HumanTaskTarget`'s javadoc says both inline and template modes support `inputMapping` — the JQ expression that maps case context to task payload. The promise was in the type, not the implementation. Closing the stub meant honouring the whole contract, not just the lookup part.

## The ref problem

The immediate question was `templateRef` resolution. The field accepts a UUID or a name — `HumanTaskTarget.template()` says so explicitly. `WorkItemTemplateService` only had `findById(UUID)`. Name-based lookup would require a new method, and name-based lookup without uniqueness guarantees is non-deterministic.

We added `findByName` with an application-level uniqueness check: if more than one template matches, throw `IllegalStateException`. A DB-level `UNIQUE` constraint is the cleaner long-term fix — filed separately. `findByRef` sits on top: try UUID parse, fall back to name if that throws `IllegalArgumentException`. One entry point for all template resolution.

## Honouring the contract

The initial design had `inputData` as a deferred concern. Claude proposed skipping it — the template's `defaultPayload` was already there, and merging the two was a separate problem.

I pushed back. If `HumanTaskTarget` documents that both modes support `inputMapping`, and `inputData` is the pre-evaluated output of that mapping, then a template mode implementation that ignores it is broken by definition. The javadoc is the contract.

We added `payloadOverride` to `WorkItemTemplateService.instantiate`: non-null and non-blank overrides `template.defaultPayload`; otherwise the template default takes over. The handler serializes `event.inputData()` and passes it through. Empty maps produce null, which falls back to the template default naturally.

## Two repos, one commit each

These changes spanned casehub-work and casehub-engine. TDD across two repos has a non-obvious requirement: after tests pass in casehub-work, run `mvn install -DskipTests` before writing the engine tests. The engine compiles against the installed JAR, not the source tree. Without the install, the engine test fails with `cannot find symbol` — and it looks exactly like the implementation is missing.

## Two things Claude caught

The `IllegalArgumentException` catch in `findByRef` was originally wrapping both `UUID.fromString` and `findById`. If `findById` threw for any unrelated reason, the catch would fire and silently fall through to name lookup with a UUID-shaped string.

```java
public Optional<WorkItemTemplate> findByRef(final String templateRef) {
    final UUID id;
    try {
        id = UUID.fromString(templateRef);
    } catch (IllegalArgumentException e) {
        return findByName(templateRef);
    }
    return findById(id);
}
```

Scoping the catch to the parse, and calling `findById` outside the try, makes the intent unambiguous and the exception propagation correct.

The atomicity concern was real but pre-existing. `markRunning()` modifies in-memory blackboard state; `instantiate()` runs in its own JPA transaction. A failure in `instantiate` after `markRunning` has already happened leaves the PlanItem stuck in RUNNING with no WorkItem. The same gap exists in inline mode. Filed, not fixed.
