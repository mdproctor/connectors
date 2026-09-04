---
layout: post
title: "Config over CDI"
date: 2026-05-15
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-engine]
tags: [java, quarkus, cdi, arc]
---

The previous entry ended with a loose end: `JsonPatchContextDiffStrategy` still
using `@Alternative` while the no-op next door had just moved to `@DefaultBean`.
Before touching it I wanted to understand why the inconsistency existed at all.

The three diff strategy implementations — `NoOpContextDiffStrategy`,
`TopLevelContextDiffStrategy`, `JsonPatchContextDiffStrategy` — had accumulated
three different CDI activation mechanisms. The previous migration to `@DefaultBean`
was correct for the worker and channel no-ops: those exist to fill in until a
consumer provides a real implementation. `@DefaultBean` is designed for exactly
that — yield automatically to any non-default qualifying bean.

But `NoOpContextDiffStrategy` is not that kind of bean.

The diff strategies are engine-internal choices. All three implementations ship in
the engine runtime; no consumer is expected to provide a fourth. There's no external
extension happening here, just selection among bundled options. Applying `@DefaultBean`
to `NoOpContextDiffStrategy` made it structurally unreachable — `TopLevelContextDiffStrategy`
was plain `@ApplicationScoped` and always won.

The right tool is configuration, not annotations.

## A producer, not a pattern

Working with Claude, we replaced all CDI annotation games with a single
`@Produces @DefaultBean` producer:

```java
@ApplicationScoped
class ContextDiffStrategyProducer {

    @ConfigProperty(name = "casehub.engine.diff-strategy", defaultValue = "none")
    String strategy;

    @Produces
    @DefaultBean
    @ApplicationScoped
    ContextDiffStrategy produce() {
        return switch (strategy) {
            case "none"       -> new NoOpContextDiffStrategy();
            case "top-level"  -> new TopLevelContextDiffStrategy();
            case "json-patch" -> new JsonPatchContextDiffStrategy();
            default -> throw new IllegalStateException(
                "Unknown casehub.engine.diff-strategy: '"
                    + strategy + "'. Valid values: none, top-level, json-patch");
        };
    }
}
```

The three strategy classes dropped their CDI annotations entirely — plain POJOs
now, instantiated directly by the producer.

One placement detail matters: `@DefaultBean` goes on the `@Produces` method, not
the producer class. On the method, it makes the *produced bean* a default bean. On
the class, it would make the *producer class* itself default — leaving the
`ContextDiffStrategy` bean it produces as a regular non-default `@ApplicationScoped`
bean, which creates exactly the ambiguity we were trying to avoid.

Consumer overrides still work. A downstream module providing its own
`@ApplicationScoped ContextDiffStrategy` takes priority automatically. The config
property governs the built-in choices only.

## Why none?

I set `casehub.engine.diff-strategy=none` as the default. `TopLevelContextDiffStrategy`
had been the de facto active implementation only because it was the plainest
`@ApplicationScoped` bean present — not because anyone chose it. Context diffing is
optional analytics. The engine's job is coordination; diff overhead is opt-in.

## Podman

Testing the none-default end-to-end required a running container — Podman in this
case, not Docker. Testcontainers doesn't find Podman automatically; the socket path
needs to be explicit:

```bash
DOCKER_HOST=unix:///run/user/501/podman/podman.sock \
TESTCONTAINERS_RYUK_DISABLED=true \
mvn test
```

We added a `@QuarkusTestProfile` that overrides the diff strategy to `none` in a
separate application context, then asserted that `WORKER_EXECUTION_COMPLETED` entries
have no `contextChanges` field. It passed on the first run.

Claude caught two things in review: `TopLevelContextDiffStrategy` was missing the
`Active when casehub.engine.diff-strategy=top-level` javadoc note that the other two
classes had, and a reflection-based test asserting `compute(JsonNode, JsonNode)` exists
on the interface — something the compiler already guarantees. Both fixed.
