---
layout: post
title: "The Params That Did Nothing"
date: 2026-05-22
type: phase-update
entry_type: note
subtype: log
projects: [casehub-work]
tags: [quarkus, cdi, testing, routing, jq]
---

There's a particular kind of bug that's easy to miss in review: code that compiles, ships, and runs fine — it just doesn't do what it says it does.

We spent a session clearing backlog items, and kept finding them.

`GET /workitems/inbox` had five query parameters — `status`, `priority`, `category`, `followUp`, `outcome` — declared in the method signature, documented in the API, and silently ignored. Every caller who passed `?status=PENDING` got back everything. The `scanRoots` method underneath didn't use them. We wired all five as stream post-filters, which is where they belonged all along.

Writing the status filter test revealed a second quiet surprise: items created with `candidateUsers` get auto-assigned immediately on creation. `LeastLoadedStrategy` resolves candidate users directly from the comma-separated field and pre-assigns to whoever has the lowest workload. Status goes PENDING → ASSIGNED before any REST response is written. I'd been thinking of `candidateUsers` as a pool for later claiming. It isn't — it's an immediate assignment queue. The test had to filter by ASSIGNED, not PENDING.

The routing config bug was simpler but instructive. `RoundRobinAssignmentStrategy` — the multi-instance batch distributor — selects its internal worker selection strategy at construction time. The check was binary: `"claim-first"` or else `leastLoaded`. When the system config said `"round-robin"`, it silently fell through to least-loaded. Three-way switch, one enum case missing, running wrong in production silence.

The most technically interesting silent failure came from consolidating the JQ evaluator in `casehub-work-queues` onto the platform's canonical `JQEvaluator`. The local `JqConditionEvaluator` compiled a new `JsonQuery` on every call — no caching, new allocation every time a filter was evaluated. The platform version caches compiled queries in a `ConcurrentHashMap`.

The migration itself was straightforward: add the dep, inject `JQEvaluator`, replace the manual compile with `jqEvaluator.eval(expr, input).isTrue()`. What wasn't straightforward was the test.

`@QuarkusComponentTest` boots a lightweight CDI container. It also silently auto-stubs any injected bean from an external module — not an error, not a warning, just a null-returning proxy. We called `jqEvaluator.eval()` in the test and got null back. The annotation's `value` attribute lists beans that should be wired as real instances rather than stubs — but this isn't in any documentation. We found it by bytecode-inspecting the annotation class directly.

```java
@QuarkusComponentTest({
    JqConditionEvaluator.class,
    JQEvaluator.class,
    MockSecretManager.class,
    MockConfigManager.class
})
```

Also: the artifact is `quarkus-junit-component` now, not `quarkus-junit5-component`. Renamed in Quarkus 3.31, Maven issues a relocation warning if you use the old name.

The platform's `MockSecretManager` and `MockConfigManager` are `@DefaultBean` in the expression module, so listing them in `value[]` is enough — they wire automatically without extra configuration.

One theme across all of it: things declared but not connected. Params signed, not applied. Config checked, not handled. Beans injected, not real. The code compiles. The system runs. You only find it when you ask what actually happens.
