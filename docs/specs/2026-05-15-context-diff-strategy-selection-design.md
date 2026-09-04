# ContextDiffStrategy Selection — Design Spec

**Issue:** casehubio/engine#258
**Date:** 2026-05-15
**Status:** approved

---

## Problem

The three `ContextDiffStrategy` implementations in the engine runtime use three different CDI activation mechanisms:

| Class | Annotation | Problem |
|-------|-----------|---------|
| `NoOpContextDiffStrategy` | `@DefaultBean @ApplicationScoped` | Never reachable — TopLevel always wins |
| `TopLevelContextDiffStrategy` | plain `@ApplicationScoped` | Always active, no way to disable |
| `JsonPatchContextDiffStrategy` | `@Alternative @ApplicationScoped` | Requires `selected-alternatives` config |

The root cause: `@DefaultBean` is designed for *consumer-replaceable SPIs* (engine ships a no-op fallback, consumer's bean wins). The diff strategies are *engine-internal choices* — all three implementations ship in the engine runtime and only one can be active. The `@DefaultBean` pattern does not map onto this problem.

---

## Design

### Activation mechanism

Replace CDI annotation games with a config-driven `@Produces` method. A single `ContextDiffStrategyProducer` bean reads `casehub.engine.diff-strategy` and constructs the chosen implementation.

**Config property:** `casehub.engine.diff-strategy`
**Default:** `none`
**Valid values:** `none`, `top-level`, `json-patch`

### Producer

```java
@DefaultBean
@ApplicationScoped
public class ContextDiffStrategyProducer {

    @ConfigProperty(name = "casehub.engine.diff-strategy", defaultValue = "none")
    String strategy;

    @Produces
    @ApplicationScoped
    ContextDiffStrategy produce() {
        return switch (strategy) {
            case "none"       -> new NoOpContextDiffStrategy();
            case "top-level"  -> new TopLevelContextDiffStrategy();
            case "json-patch" -> new JsonPatchContextDiffStrategy();
            default -> throw new IllegalStateException(
                "Unknown casehub.engine.diff-strategy: '" + strategy +
                "'. Valid values: none, top-level, json-patch");
        };
    }
}
```

The producer is `@DefaultBean` — a consumer's own `@ApplicationScoped ContextDiffStrategy` wins automatically, preserving the SPI's extensibility.

Unknown values throw `IllegalStateException` at boot — no silent fallback.

### Strategy classes

All three strategy classes lose their CDI annotations. They become plain, package-private, constructable POJOs. No `@ApplicationScoped`, no `@DefaultBean`, no `@Alternative`. The producer owns instantiation.

### `ContextDiffStrategy` javadoc

Updated to document:
- `casehub.engine.diff-strategy` config property and its three valid values
- That a consumer `@ApplicationScoped` implementation takes priority over the config-selected built-in

---

## Testing

### 1. Existing strategy unit tests (unchanged)
`NoOpContextDiffStrategyTest`, `TopLevelContextDiffStrategyTest`, `JsonPatchContextDiffStrategyTest` test POJO logic directly. No CDI involved — unaffected by this change.

### 2. Producer unit test (new)
`ContextDiffStrategyProducerTest` — plain JUnit, no Quarkus. Instantiates the producer directly, exercises each `case`, and verifies the unknown-value path throws `IllegalStateException` with the expected message.

### 3. Contract test (new)
`ContextDiffStrategyContractTest` in `api/src/test/java/io/casehub/api/spi/` — inline anonymous implementation verifies the interface signature and that returning `null` is a valid contract. Follows the `WorkerProvisionerContractTest` pattern.

### 4. End-to-end default verification (update)
`ContextDiffEndToEndTest` updated to assert that with no config, `contextChanges` is absent from the EventLog — confirming the `none` default is active.

### Out of scope
Consumer override integration test (a `@QuarkusTest` with a custom `@ApplicationScoped` strategy beating the producer). Captured as a follow-up issue.

---

## Protocol and doc updates

### `engine-spi-noops-defaultbean.md`
- Remove `NoOpContextDiffStrategy` from the beans table
- Add a note distinguishing:
  - *Consumer-replaceable SPI:* `@DefaultBean` no-op, consumer's `@ApplicationScoped` impl wins — the 8 worker/channel beans
  - *Engine-internal strategy selection:* `@DefaultBean` producer reads config, consumer's `@ApplicationScoped` impl still wins — `ContextDiffStrategy`

### No `PLATFORM.md` update needed
The platform "no-op defaults" rule remains correct for the 8 worker/channel SPIs. The nuance lives in the protocol.

---

## Files changed

| File | Change |
|------|--------|
| `runtime/src/main/java/io/casehub/engine/internal/diff/ContextDiffStrategyProducer.java` | new |
| `runtime/src/main/java/io/casehub/engine/internal/diff/NoOpContextDiffStrategy.java` | remove `@DefaultBean @ApplicationScoped` |
| `runtime/src/main/java/io/casehub/engine/internal/diff/TopLevelContextDiffStrategy.java` | remove plain `@ApplicationScoped` |
| `runtime/src/main/java/io/casehub/engine/internal/diff/JsonPatchContextDiffStrategy.java` | remove `@Alternative @ApplicationScoped` |
| `api/src/main/java/io/casehub/api/spi/ContextDiffStrategy.java` | javadoc update |
| `runtime/src/test/java/io/casehub/engine/internal/diff/ContextDiffStrategyProducerTest.java` | new |
| `api/src/test/java/io/casehub/api/spi/ContextDiffStrategyContractTest.java` | new |
| `runtime/src/test/java/io/casehub/engine/ContextDiffEndToEndTest.java` | update default assertion |
| `docs/protocols/casehub/engine-spi-noops-defaultbean.md` | update beans table + pattern note |