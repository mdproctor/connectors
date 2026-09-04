---
layout: post
title: "Testing the Handler, Not the Bus"
date: 2026-05-19
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-engine]
tags: [quarkus, cdi, testing, event-bus, transactional]
---

Most of the day was cleanup — small things that had accumulated since the atomicity work. A two-arg arity mismatch when casehub-work extended `SelectionContext` and `WorkItemCreateRequest` without updating the engine call sites. A missing `flush()` in `JpaReactivePlanItemStore.updateStatus()` that silently no-oped when called in the same session as a `save()`. A detached entity mutation in a template-mode test that let the test pass for the wrong reason — the `defaultPayload` assignment happened after the transaction committed, so the database never saw it.

I brought Claude in early and we worked through most of it quickly. What took longer was understanding why `HumanTaskScheduleHandlerTest` needed timing machinery at all.

## Testing the event bus is Quarkus's job

The tests called `eventBus.publish()` and waited — `Awaitility` for positive paths, `Thread.sleep(300)` for negative ones. The negative-path tests were the worst. Assert that nothing happened, after waiting long enough to be fairly sure the handler had fired. Brittle by design, and impossible to make rigorous without knowing when the handler actually ran.

The underlying question is what's actually being tested. `HumanTaskScheduleHandler` is an `@ApplicationScoped` bean. The `@ConsumeEvent` annotation is wiring. Testing that wiring is Quarkus's responsibility.

What we did instead: inject the handler bean directly and call `onHumanTaskSchedule()` synchronously. The bean is a CDI proxy, so `@Transactional` fires identically to production — the transaction commits before the call returns, and assertions follow immediately. Nine tests dropped their `await()` blocks. Four dropped their sleeps. One wiring test remains to confirm the route is registered.

The atomicity test needed a small addition. It checks that a WorkItem creation failure leaves the PlanItem PENDING — but the assertion could pass vacuously if the handler hadn't fired yet. Claude caught this: without some signal that `put()` was actually attempted, the test proves nothing. A `CountDownLatch` inside `FailingWorkItemStore.put()` solves it — the test awaits the latch before asserting, which proves the transaction ran and threw before the state is checked.

## One test double shouldn't follow you everywhere

`FailingWorkItemStore` was in `selected-alternatives` in `application.properties`, making it the active `WorkItemStore` for every `@QuarkusTest` class in the module. One atomicity test's dependency was leaking into all the happy-path tests.

The fix is `QuarkusTestProfile.getEnabledAlternatives()`:

```java
public static class Profile implements QuarkusTestProfile {
    @Override
    public Set<Class<?>> getEnabledAlternatives() {
        return Set.of(FailingWorkItemStore.class);
    }
}
```

`FailingWorkItemStore` moves to a dedicated `HumanTaskScheduleHandlerAtomicityTest` with this `Profile`. The store activates only for that class. Quarkus restarts once when the profile changes — right trade for real isolation.

## Three maps, one entry

`BlackboardRegistry` had three `ConcurrentHashMap` fields — `planModels`, `completionIndex`, `configured` — all keyed by the same `UUID caseId`. The smell: eviction called `remove()` three times in sequence, with a race window between removes.

The consolidation: one `ConcurrentHashMap<UUID, CaseEntry>` where `CaseEntry` holds all three. Eviction becomes a single `entries.remove(caseId)`. While working through the internals, Claude flagged that `markConfigured()` and `indexWorkerForCompletion()` were using `computeIfAbsent()` — meaning calling either on an unknown caseId silently created a ghost entry. Both switch to `entries.get()` with a null-check. No entry, no-op. The public API is unchanged.
