# Design: @DefaultBean for Engine SPI No-Op Defaults

**Date:** 2026-05-14
**Issue:** casehubio/engine#257
**Protocol:** PP-20260514-engine-spi-noops-defaultbean

---

## Problem

Eight no-op/empty SPI default implementations in the engine runtime use `@ApplicationScoped`
or `@Alternative @ApplicationScoped`. Neither is correct for a platform fallback:

- **Bare `@ApplicationScoped`**: collides with consumer implementations (e.g. Claudony's
  `ClaudonyWorkerProvisioner`) when the engine is indexed alongside a consumer repo's
  `@QuarkusTest` classpath. CDI sees two qualifying beans for the same injection point and
  fails with an ambiguity error.
- **`@Alternative @ApplicationScoped`**: avoids collision but requires explicit activation via
  `quarkus.arc.selected-alternatives`. This is fragile: each consumer must know which no-ops
  exist and list them, and the list breaks silently when new no-ops are added.

## Decision

All nine beans get `@DefaultBean @ApplicationScoped` (`EmptyReactiveWorkerContextProvider` was discovered during implementation and added to scope).

`@DefaultBean` is the canonical CDI pattern for "I am a fallback — any non-default qualifying
bean for this type automatically takes precedence." It makes the intent self-documenting and
consumer-transparent: consumers provide their implementation, CDI wires it, no configuration
required.

No alternative architecture was seriously considered. The collision is a CDI bean resolution
problem; `@DefaultBean` is the CDI-specified solution. Approaches such as `Instance<>` injection
with `isUnsatisfied()` checks would require widespread changes to the engine's injection sites
with no benefit.

## Beans

All eight are in `casehub-engine/runtime/src/main/java/io/casehub/engine/internal/`:

| Class | Package | From | To |
|-------|---------|------|----|
| `NoOpWorkerProvisioner` | `worker/` | `@ApplicationScoped` | `@DefaultBean @ApplicationScoped` |
| `NoOpCaseChannelProvider` | `worker/` | `@ApplicationScoped` | `@DefaultBean @ApplicationScoped` |
| `NoOpWorkerStatusListener` | `worker/` | `@ApplicationScoped` | `@DefaultBean @ApplicationScoped` |
| `EmptyWorkerContextProvider` | `worker/` | `@ApplicationScoped` | `@DefaultBean @ApplicationScoped` |
| `NoOpContextDiffStrategy` | `diff/` | `@Alternative @ApplicationScoped` | `@DefaultBean @ApplicationScoped` |
| `NoOpReactiveWorkerProvisioner` | `worker/` | `@Alternative @ApplicationScoped` | `@DefaultBean @ApplicationScoped` |
| `NoOpReactiveCaseChannelProvider` | `worker/` | `@Alternative @ApplicationScoped` | `@DefaultBean @ApplicationScoped` |
| `NoOpReactiveWorkerStatusListener` | `worker/` | `@Alternative @ApplicationScoped` | `@DefaultBean @ApplicationScoped` |

## Reactive variants — behavioural note

The three reactive beans were `@Alternative` (inactive by default). Moving to `@DefaultBean`
makes them always-active. This is safe: each implements a distinct interface
(`ReactiveWorkerProvisioner`, `ReactiveCaseChannelProvider`, `ReactiveWorkerStatusListener`)
with no competing injection points from the blocking variants. No test properties currently
activate them via `selected-alternatives`, confirming the standard execution path does not
inject them — they will sit dormant but available.

## Import changes

- `@DefaultBean` is `io.quarkus.arc.DefaultBean` — a Quarkus Arc annotation, **not** standard CDI.
  Available via `io.quarkus.arc:arc` which is on compile scope in the runtime module.
- Beans gaining `@DefaultBean`: add `import io.quarkus.arc.DefaultBean;`
- Beans losing `@Alternative`: swap `import jakarta.enterprise.inject.Alternative;` →
  `import io.quarkus.arc.DefaultBean;`
- Javadoc referencing `@Alternative` in the reactive beans must be updated to reflect
  `@DefaultBean` semantics.

## Protocol update

`PP-20260514-engine-spi-noops-defaultbean` must be updated to include `EmptyWorkerContextProvider`
in its "Beans to fix" table — it was omitted from the original protocol.

## Testing

- `mvn test -pl runtime` must pass with no regressions.
- No new test cases required: this is an annotation change with no behavioural effect on the
  engine's own code paths. The engine tests already cover the no-op delegation paths.
- A test confirming `@DefaultBean` yields to a higher-priority bean is not in scope; the
  integration test suites in consumer repos (Claudony, devtown) provide that coverage end-to-end.