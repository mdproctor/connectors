# Retrofit Work SPIs to Extend NamedStrategy

**Issue:** casehubio/work#287
**Date:** 2026-07-12
**Status:** Approved

## Context

engine#634 established the universal routing strategy convention across the CaseHub platform: all per-case-selectable strategies extend `NamedStrategy` and are resolved by `id` via `StrategyResolver`.

casehub-work has 4 SPIs that use three different ad-hoc resolution mechanisms:

| SPI | Current mechanism | Problem |
|-----|------------------|---------|
| `WorkerSelectionStrategy` | CDI `Instance<>` scan filtering out built-ins + config string switch | Manual bean filtering, duplicated switch in RoundRobinAssignmentStrategy |
| `InstanceAssignmentStrategy` | CDI `@Named` qualifier + `Instance<>` iteration | Relies on annotation reflection at runtime |
| `ClaimSlaPolicy` | Pure CDI `@Alternative @Priority` | No config-based selection; requires CDI wiring knowledge |
| `SlaBreachPolicy` | `@DefaultBean` override (classpath-based) | No config-based selection; `@DefaultBean` is the override mechanism |

All four should adopt `NamedStrategy` + `StrategyResolver` — the platform convention.

## Design

### SPI Interface Changes (api/)

Each SPI extends `NamedStrategy`:

```java
public interface WorkerSelectionStrategy extends NamedStrategy { ... }
public interface InstanceAssignmentStrategy extends NamedStrategy { ... }
public interface ClaimSlaPolicy extends NamedStrategy { ... }
public interface SlaBreachPolicy extends NamedStrategy { ... }
```

`NamedStrategy` adds a single method: `String id()`. No other SPI method changes.

Javadoc on each SPI updates from `@Alternative @Priority` / `@Named` activation instructions to NamedStrategy/config-based selection.

### Implementation Changes

Each implementation adds `id()` and removes CDI selection annotations:

| Implementation | Module | `id()` | Removes |
|---------------|--------|--------|---------|
| `LeastLoadedStrategy` | core/ | `"least-loaded"` | — |
| `ClaimFirstStrategy` | core/ | `"claim-first"` | `@Alternative @Priority(0)` |
| `RoundRobinStrategy` | core/ | `"round-robin"` | — |
| `SemanticWorkerSelectionStrategy` | ai/ | `"semantic"` | `@Alternative @Priority(1)` |
| `ContinuationPolicy` | core/ | `"continuation"` | — |
| `FreshClockPolicy` | core/ | `"fresh-clock"` | `@Alternative` |
| `SingleBudgetPolicy` | core/ | `"single-budget"` | `@Alternative` |
| `PhaseClockPolicy` | core/ | `"phase-clock"` | `@Alternative` |
| `PoolAssignmentStrategy` | runtime/ | `"pool"` | `@Named("pool")` |
| `RoundRobinAssignmentStrategy` | runtime/ | `"round-robin"` | `@Named("roundRobin")` |
| `ExplicitListAssignmentStrategy` | runtime/ | `"explicit"` | `@Named("explicit")` |
| `CompositeInstanceAssignmentStrategy` | runtime/ | `"composite"` | `@Named("composite")` |
| `NoOpSlaBreachPolicy` | runtime/ | `"no-op"` | `@DefaultBean` |

All implementations keep `@ApplicationScoped`.

`NoOpSlaBreachPolicy` drops `@DefaultBean` because it conflicts with `StrategyResolver` discovery. When an application provides a custom `SlaBreachPolicy` bean, Quarkus Arc suppresses the `@DefaultBean` bean entirely — it never appears in `DefaultStrategyResolver`'s `@Any Instance<NamedStrategy>`, making the `"no-op"` id unresolvable even though config defaults to it. Without `@DefaultBean`, both implementations coexist in the resolver and config selects between them. This is the intended NamedStrategy model: classpath-based override (`@DefaultBean`, `@Alternative`) is replaced by config-based selection.

### Resolution Site Changes

**`WorkItemAssignmentService`** — the most complex change:

Before:
```java
@Inject Instance<WorkerSelectionStrategy> alternatives;
@Inject ClaimFirstStrategy claimFirst;
@Inject LeastLoadedStrategy leastLoaded;
@Inject RoundRobinStrategy roundRobin;

private WorkerSelectionStrategy activeStrategy() {
    // filter Instance<>, fall back to config switch
}
```

After:
```java
@Inject StrategyResolver strategyResolver;
@Inject WorkItemsConfig config;

private WorkerSelectionStrategy activeStrategy() {
    return strategyResolver.resolve(WorkerSelectionStrategy.class, config.routing().strategy());
}
```

The `fixedStrategy` / dual-constructor pattern for unit tests is replaced by injecting a `StrategyResolver`. Unit tests mock the `StrategyResolver` interface (Mockito is already used throughout) or create a test helper in package `io.casehub.platform.routing` to access `DefaultStrategyResolver`'s package-private list constructor.

**`RoundRobinAssignmentStrategy`** (InstanceAssignmentStrategy in runtime/multiinstance/) — currently duplicates the config switch to select a WorkerSelectionStrategy internally:

Before:
```java
@Inject
public RoundRobinAssignmentStrategy(WorkItemsConfig config,
        ClaimFirstStrategy claimFirst, LeastLoadedStrategy leastLoaded,
        RoundRobinStrategy roundRobin) {
    this.workerSelectionStrategy = switch (config.routing().strategy()) { ... };
}
```

After:
```java
@Inject
public RoundRobinAssignmentStrategy(StrategyResolver strategyResolver,
        WorkItemsConfig config) {
    this.workerSelectionStrategy = strategyResolver.resolve(
            WorkerSelectionStrategy.class, config.routing().strategy());
}
```

**`MultiInstanceSpawnService`** — currently iterates `Instance<InstanceAssignmentStrategy>` checking `@Named`:

Before:
```java
@Inject @Named("pool") InstanceAssignmentStrategy defaultStrategy;
@Inject @Any Instance<InstanceAssignmentStrategy> strategies;

private InstanceAssignmentStrategy resolveStrategy(String name) {
    if (name == null || name.isBlank() || "pool".equals(name)) return defaultStrategy;
    for (InstanceAssignmentStrategy s : strategies) {
        Named named = s.getClass().getAnnotation(Named.class);
        if (named != null && name.equals(named.value())) return s;
    }
    return defaultStrategy;
}
```

After:
```java
@Inject StrategyResolver strategyResolver;

private InstanceAssignmentStrategy resolveStrategy(String name) {
    if (name == null || name.isBlank()) {
        return strategyResolver.resolve(InstanceAssignmentStrategy.class, "pool");
    }
    return strategyResolver.resolve(InstanceAssignmentStrategy.class, name);
}
```

**`WorkItemService`** — currently injects `ClaimSlaPolicy` via constructor:

Before:
```java
@Inject
public WorkItemService(final WorkItemStore workItemStore,
        final AuditEntryStore auditStore,
        final WorkItemsConfig config,
        final WorkItemAssignmentService assignmentService,
        final ClaimSlaPolicy claimSlaPolicy,
        final ExclusionPolicy exclusionPolicy,
        final BlockedAttemptAuditService blockedAuditService,
        final CapabilityValidator capabilityValidator,
        final WorkItemTimerService timerService) {
    ...
    this.claimSlaPolicy = claimSlaPolicy;
    ...
}
```

After:
```java
@Inject
public WorkItemService(final WorkItemStore workItemStore,
        final AuditEntryStore auditStore,
        final WorkItemsConfig config,
        final WorkItemAssignmentService assignmentService,
        final StrategyResolver strategyResolver,
        final ExclusionPolicy exclusionPolicy,
        final BlockedAttemptAuditService blockedAuditService,
        final CapabilityValidator capabilityValidator,
        final WorkItemTimerService timerService) {
    ...
    this.claimSlaPolicy = strategyResolver.resolve(ClaimSlaPolicy.class, config.sla().claimPolicy());
    ...
}
```

Tests that construct `WorkItemService` directly (e.g. `WorkItemServiceTest`) pass a mock `StrategyResolver` instead of a `ClaimSlaPolicy`.

**`ExpiryLifecycleService`** — currently injects both `ClaimSlaPolicy` and `SlaBreachPolicy` via field injection:

Before:
```java
@Inject
SlaBreachPolicy slaBreachPolicy;

@Inject
ClaimSlaPolicy claimSlaPolicy;
```

After (`WorkItemsConfig config` is already injected — only `StrategyResolver` is new):
```java
@Inject StrategyResolver strategyResolver;

SlaBreachPolicy slaBreachPolicy;
ClaimSlaPolicy claimSlaPolicy;

@PostConstruct
void init() {
    this.slaBreachPolicy = strategyResolver.resolve(SlaBreachPolicy.class, config.sla().breachPolicy());
    this.claimSlaPolicy = strategyResolver.resolve(ClaimSlaPolicy.class, config.sla().claimPolicy());
}
```

Policy resolution happens once at startup. The four methods that call `slaBreachPolicy.onBreach()` — `checkExpired()`, `checkClaimDeadlines()`, `expireItem()`, `processClaimDeadline()` — continue to use the resolved field with no behavioral change. `ExpiryLifecycleServiceTest` updates to provide a mock or test `StrategyResolver` returning both test policies.

### Config Changes

New properties in `WorkItemsConfig`:

```java
@io.smallrye.config.WithName("sla")
SlaConfig sla();

interface SlaConfig {
    @WithDefault("continuation")
    String claimPolicy();

    @WithDefault("no-op")
    String breachPolicy();
}
```

Yielding:
- `casehub.work.sla.claim-policy=continuation` (default)
- `casehub.work.sla.breach-policy=no-op` (default)

Existing `casehub.work.routing.strategy=least-loaded` is unchanged.

### Behavioral Change

`SemanticWorkerSelectionStrategy` currently auto-activates when `quarkus-work-ai` is on the classpath (via `@Alternative @Priority(1)`). After this change, it requires explicit config: `casehub.work.routing.strategy=semantic`. This is the right design — implicit classpath activation is surprising; explicit config selection is the platform convention.

Invalid strategy config values now fail fast. The current `activeStrategy()` switch has `default -> leastLoaded` — a typo in `casehub.work.routing.strategy` silently falls back to least-loaded. After this change, `StrategyResolver.resolve()` throws `IllegalArgumentException` for unknown ids with the message `"No strategy with id 'X' for type WorkerSelectionStrategy. Available: [...]"`. This applies to all four SPIs, not just WorkerSelectionStrategy. The same fail-fast applies to `MultiInstanceSpawnService` (InstanceAssignmentStrategy) — the current code falls back to pool on unknown names; the new code throws.

### Dependencies

No dependency changes needed:
- `api/` already depends on `casehub-platform-api` (provides `NamedStrategy`, `StrategyResolver`)
- `casehub-platform` (provides `DefaultStrategyResolver`) is already test-scoped in `runtime/` and on the classpath in all real deployments
- `DefaultStrategyResolver` auto-discovers all `NamedStrategy` CDI beans — no registration code needed

### What Does NOT Change

- **`WorkBroker`** — receives the resolved strategy as a parameter; no changes needed
- **SPI method signatures** — `select()`, `assign()`, `computePoolDeadline()`, `onBreach()` are unchanged
- **`ExclusionPolicy`, `WorkerRegistry`, `WorkloadProvider`**, etc. — infrastructure SPIs with single implementations; not per-case selectable; not part of this retrofit
- **`triggers()` default method** on `WorkerSelectionStrategy` — orthogonal to NamedStrategy, kept as-is

### Test Strategy

- **Unit tests** that construct services with raw strategy instances: mock `StrategyResolver` (e.g., `when(resolver.resolve(WorkerSelectionStrategy.class, "least-loaded")).thenReturn(strategy)`) and pass the mock to the service. `DefaultStrategyResolver`'s list constructor is package-private in `io.casehub.platform.routing` — a test helper in that package can access it for tests needing real resolution validation, but mocking is preferred for standard unit tests
- **Integration tests** that set `casehub.work.routing.strategy`: no change needed — config property name is the same, values are the same
- **Tests relying on @Alternative auto-activation** of SemanticWorkerSelectionStrategy: update to set config explicitly
- **ClaimSlaPolicy tests** (e.g., `ClaimSlaPolicyTest`): no change — they test policy logic directly, not resolution
- **`ExpiryLifecycleServiceTest`**: update to provide a StrategyResolver with the test policy
- **New tests**: verify StrategyResolver-based resolution for each SPI — correct id resolves to correct impl, unknown id throws, default resolution works

### Files Changed (estimated)

| Area | Files | Nature |
|------|-------|--------|
| SPI interfaces | 4 | Add `extends NamedStrategy` |
| Strategy implementations | 13 | Add `id()`, remove annotations |
| Resolution sites | 5 | Replace CDI scanning with StrategyResolver |
| Config | 1 | Add SlaConfig group |
| Unit tests | ~8 | Refactor constructors, add resolution tests |
| Integration tests | ~2 | Explicit config for semantic strategy |
| Javadoc | 4 | Update activation instructions |
