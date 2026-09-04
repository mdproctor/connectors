# Declarative SLA Policy Config — Design Spec

**Date:** 2026-08-30
**Status:** Draft
**Issue:** casehubio/work#372
**Decisions:** [decisions.md](decisions.md) D1-D6

## Motivation

Per-item escalation config landed in #362 (escalation fields on `WorkItemEntity`, V42), but deployment-wide SLA defaults still require a Java `SlaBreachPolicy` CDI bean. Tutorials need "if unclaimed after 4 hours, escalate to team-leads" without writing Java.

This spec adds a YAML-based `SlaBreachPolicy` implementation that reads deployment-wide defaults and scope-based overrides from a classpath resource, with application.yaml config property overrides for simple defaults.

## Breach Resolution Chain

After this work, the full breach resolution chain is:

| Priority | Mechanism | Variants | Source |
|----------|-----------|----------|--------|
| 1 | Per-item fields (#362) | `EscalateTo` only | `WorkItemEntity` columns (V42) |
| 2 | `DeclarativeSlaBreachPolicy` — scope match | All except `Chained` | YAML classpath resource |
| 3 | `DeclarativeSlaBreachPolicy` — defaults | All except `Chained` | YAML or config properties |
| 4 | Fallback CDI policy | All | `SlaBreachPolicy` bean by id |

Priority 1 is handled by `ExpiryLifecycleService.resolveBreachDecision()` (unchanged). Priorities 2-4 are handled inside `DeclarativeSlaBreachPolicy.onBreach()`.

**Per-item config gap:** Per-item fields (#362) can only express `EscalateTo`. Per-item `fail`, `extend`, or `exhausted` requires the deployment-wide YAML. This is acceptable — per-item config comes from engine YAML bindings (`humanTask.escalation`) which naturally produce `EscalateTo` decisions. The deployment-wide layer covers the broader set of actions.

**CallbackSlaBreachPolicyDecorator interaction:** The `@Decorator CallbackSlaBreachPolicyDecorator` wraps whichever `SlaBreachPolicy` is resolved. If a tenant registers a callback, the callback takes priority over ALL YAML config. This is intentional — tenant-level callbacks are an explicit opt-in that overrides deployment defaults.

## YAML Format (D1, D2)

Classpath resource: `META-INF/work-sla-defaults.yaml`

```yaml
sla:
  defaults:
    onCompletionExpiry: fail
    onClaimExpiry: extend
    extensionHours: 4
    claimExtensionHours: 8
  scopes:
    casehubio/clinical:
      onCompletionExpiry:
        escalateTo: senior-reviewers
        deadline: PT24H
      onClaimExpiry:
        escalateTo: team-leads
    casehubio/clinical/triage:
      onCompletionExpiry:
        exhausted: triage-sla-exceeded
```

### Action Syntax (D2)

Each `onCompletionExpiry` and `onClaimExpiry` value is either a string shorthand or an object:

| String shorthand | Maps to | Notes |
|-----------------|---------|-------|
| `fail` | `BreachDecision.Fail("sla-breach")` | Terminates the WorkItem |
| `extend` | `BreachDecision.Extend(Duration.ofHours(extensionHours))` | Uses `extensionHours` from same scope/defaults |
| `exhausted` | `BreachDecision.Exhausted("sla-exhausted")` | Terminal with default reason |

| Object form | Maps to | Notes |
|------------|---------|-------|
| `{fail: reason}` | `BreachDecision.Fail(reason)` | Custom reason string |
| `{extend: PT6H}` | `BreachDecision.Extend(Duration.parse("PT6H"))` | Explicit duration overrides `extensionHours` |
| `{escalateTo: group}` | `EscalateTo.to(group)` | Deadline from `config.defaultExpiryHours()` |
| `{escalateTo: group, deadline: PT4H}` | `EscalateTo.to(group).withDeadline(PT4H)` | Explicit deadline |
| `{exhausted: reason}` | `BreachDecision.Exhausted(reason)` | Custom reason string |

**`extensionHours`** can appear at scope level or defaults level. Scope-level `extensionHours` takes precedence for `extend` shorthands within that scope. If neither is set, falls back to `config.defaultExpiryHours()`.

**`claimExtensionHours` and `extensionHours` for `extend`:** Under `onClaimExpiry`, `extend` uses `claimExtensionHours` if defined at the resolved scope, falling back to `extensionHours` at the same scope, then walking to parent scopes and defaults. Under `onCompletionExpiry`, `extend` uses `extensionHours` directly.

**`Chained` is excluded** from YAML (#376). Cross-breach sequencing uses the candidateGroups-as-state pattern (GE-20260522-f7db12). Within a single breach event, atomic fallback is a programmatic concern that belongs in a CDI `SlaBreachPolicy` bean.

### Variable Interpolation

Following the `WorkItemTemplateYamlLoader` pattern, string values support `${env.VAR}` and `${sys.PROP}` interpolation. Primarily useful for group names that vary by environment:

```yaml
scopes:
  casehubio/clinical:
    onCompletionExpiry:
      escalateTo: ${env.ESCALATION_GROUP}
```

Variable interpolation does NOT support `:-default` syntax. If the referenced environment variable or system property is unset, the placeholder remains as a literal string. Default-value syntax is tracked for both loaders in #377.

## Scope Resolution (D6)

Hierarchical, most-specific match wins. The policy walks `Path.parent()` from the WorkItem's scope:

```
WorkItem scope: casehubio/clinical/triage
  1. Check scopes["casehubio/clinical/triage"] → exact match
  2. Check scopes["casehubio/clinical"]        → parent
  3. Check scopes["casehubio"]                 → grandparent
  4. Check defaults                            → deployment-wide
  5. Delegate to fallback policy               → CDI bean
```

At each scope level, the policy checks whether the relevant action (`onCompletionExpiry` or `onClaimExpiry`) is defined for that scope. If defined, it returns the corresponding `BreachDecision`. If not defined at any scope level or in defaults, it delegates to the fallback policy.

**Independent resolution:** Action (`onCompletionExpiry`/`onClaimExpiry`) and `extensionHours`/`claimExtensionHours` resolve independently through the scope hierarchy. A child scope's `extensionHours` applies even when the action is defined at a parent scope. This is intentional — it allows scopes to override duration without re-declaring the action.

**Root scope (`Path.root()`):** WorkItems without an assigned scope have `Path.root()`. These skip the scope walk entirely and go straight to defaults.

**Scope key format:** YAML scope keys are the full path string (e.g., `casehubio/clinical`). `Path.parse()` is used to normalize them at load time. If two YAML scope keys normalize to the same `Path`, the loader logs WARN and the last one wins.

## Config Property Overrides (D4)

Defaults-only override via application.yaml config properties. Values use colon-delimited syntax:

```properties
casehub.work.sla.declarative.defaults.on-completion-expiry=fail
casehub.work.sla.declarative.defaults.on-claim-expiry=escalateTo:team-leads:PT4H
casehub.work.sla.declarative.defaults.extension-hours=4
casehub.work.sla.declarative.defaults.claim-extension-hours=8
```

**Colon-delimited syntax:**
- `fail` → `Fail("sla-breach")`
- `fail:custom-reason` → `Fail("custom-reason")`
- `extend` → uses `extension-hours` / `claim-extension-hours` config
- `extend:PT6H` → explicit duration
- `escalateTo:group` → default deadline
- `escalateTo:group:PT4H` → explicit deadline
- `exhausted` → `Exhausted("sla-exhausted")`
- `exhausted:custom-reason` → `Exhausted("custom-reason")`

**Colon limitation:** Group names in config property values must not contain colons — `escalateTo:domain:admins` would misparse `admins` as a duration. Use the YAML surface for groups with colons: `escalateTo: "domain:admins"`.

**Override precedence:** Config properties win over classpath YAML defaults. Scopes always come from classpath YAML — config properties cannot define scopes. When a config property overrides a YAML default, the loader logs at WARN level:

```
SLA default for on-completion-expiry overridden by config property (config: fail; YAML had: escalateTo:senior-reviewers)
```

**When both surfaces are absent:** If no classpath YAML exists and no config properties are set, the declarative policy delegates everything to the fallback policy (default: `"no-op"` → `Fail("no-sla-breach-policy-configured")`).

**Config property access:** The declarative policy reads its config from `config.sla().declarative()` — scoped under the `DeclarativeConfig` sub-interface, not on the shared `SlaConfig`. See §WorkItemsConfig below.

## Changes

### WorkItemsConfig — Config Properties

Add `DeclarativeConfig` sub-interface to `SlaConfig`. Declarative-policy-specific config is scoped under `casehub.work.sla.declarative.*`, keeping the shared `SlaConfig` interface clean:

```java
interface SlaConfig {
    // ... existing breachPolicy(), claimPolicy() — unchanged

    DeclarativeConfig declarative();

    interface DeclarativeConfig {
        @WithDefault("no-op")
        String fallback();

        DefaultsConfig defaults();

        interface DefaultsConfig {
            Optional<String> onCompletionExpiry();
            Optional<String> onClaimExpiry();
            OptionalInt extensionHours();
            OptionalInt claimExtensionHours();
        }
    }
}
```

### SlaDeclarativeConfig — Immutable Config Record

New record in `io.casehub.work.runtime.service`:

```java
public record SlaDeclarativeConfig(
    BreachAction defaultOnCompletionExpiry,
    BreachAction defaultOnClaimExpiry,
    Integer extensionHours,
    Integer claimExtensionHours,
    Map<Path, ScopeConfig> scopes
) {
    public record ScopeConfig(
        BreachAction onCompletionExpiry,
        BreachAction onClaimExpiry,
        Integer extensionHours,
        Integer claimExtensionHours
    ) {}
}
```

`extensionHours` and `claimExtensionHours` at the defaults level use `Integer` (nullable), not `int`. A `null` value means "not configured" — `resolveExtensionHours` returns `null`, and `BreachAction.toBreachDecision` falls back to `config.defaultExpiryHours()`. This prevents a silent zero-duration extension when neither YAML nor config properties set `extensionHours`.

### BreachAction — Parsed Action Value

New sealed interface in `io.casehub.work.runtime.service`:

```java
sealed interface BreachAction {
    record FailAction(String reason) implements BreachAction {}
    record ExtendAction(Duration explicitDuration) implements BreachAction {}
    record EscalateToAction(String group, Duration deadline) implements BreachAction {}
    record ExhaustedAction(String reason) implements BreachAction {}

    static BreachAction parse(Object yamlValue) { /* ... */ }
    static BreachAction parseColon(String configValue) { /* ... */ }

    BreachDecision toBreachDecision(Integer fallbackExtensionHours, int defaultExpiryHours);
}
```

`BreachAction` is the intermediate representation between YAML/config parsing and `BreachDecision` construction. `toBreachDecision()` resolves durations: `ExtendAction` with null `explicitDuration` uses `fallbackExtensionHours` if non-null, otherwise `defaultExpiryHours`; `EscalateToAction` with null `deadline` uses `Duration.ofHours(defaultExpiryHours)`.

**Duration validation:** `BreachAction.parse()` and `parseColon()` reject zero or negative durations at parse time for both `extend` and `escalateTo`:
- `{extend: PT0S}`, `{extend: -PT1H}`, `extend:PT0S` → `IllegalArgumentException("extend duration must be positive")`
- `{escalateTo: group, deadline: PT0S}`, `{escalateTo: group, deadline: -PT1H}`, `escalateTo:group:PT0S` → `IllegalArgumentException("escalateTo deadline must be positive")`

A zero escalation deadline causes immediate re-expiry (`expiresAt = now.plus(Duration.ZERO)`) and infinite re-escalation — the declarative policy matches on `scope` (unchanged by escalation), not `candidateGroups`, so the same rule fires every tick. This validation is consistent with the #362 spec (`BindingDeserializer` escalation deadline validation).

### SlaDefaultsYamlLoader — Classpath Loader

New `@ApplicationScoped @Startup` bean in `io.casehub.work.runtime.service`:

```java
@ApplicationScoped
@Startup
public class SlaDefaultsYamlLoader {

    private static final String RESOURCE_PATH = "META-INF/work-sla-defaults.yaml";

    @Inject WorkItemsConfig config;

    SlaDeclarativeConfig loadedConfig;

    @PostConstruct
    void load() {
        SlaDeclarativeConfig yamlConfig = loadFromClasspath();
        this.loadedConfig = mergeConfigOverrides(yamlConfig);
    }
}
```

**Classpath scanning:** Uses `Thread.currentThread().getContextClassLoader().getResources(RESOURCE_PATH)` to discover all contributors. Scopes from multiple JARs are merged (last-writer-wins for duplicate scope keys, logged at WARN). **Conflicting defaults are an error:** if more than one resource contributes a `sla.defaults` section, the loader throws `IllegalStateException` at startup — deployment-wide defaults must come from a single source. This prevents silent, environment-dependent behavior when classpath ordering varies.

**Scope key validation:** When the loader encounters a scope key that fails `Path.parse()`, the exception is caught and re-thrown with a diagnostic message: "Scope key '{key}' is invalid — root scope cannot be addressed in YAML; use the defaults section instead." This guides users who try `""` or `"/"` as a scope key.

**Config property merge:** After classpath loading, config properties from `config.sla().declarative().defaults()` override the defaults section. WARN log emitted for each override.

**Duration validation:** `extensionHours` and `claimExtensionHours` values must be positive when present. The loader rejects values ≤ 0 with `IllegalArgumentException` at startup. This prevents the same infinite re-extension loop as the absent-extensionHours case (R1-04) — `Extend(Duration.ofHours(0))` advances `expiresAt` by zero, re-triggering on the next scheduler tick.

**Error handling:** Malformed YAML throws `RuntimeException` at startup — fail fast. Invalid action values (unparseable duration, unknown action type) throw `IllegalArgumentException` with the scope path and field name for diagnostics.

### DeclarativeSlaBreachPolicy — Policy Implementation

New `@ApplicationScoped @Unremovable` bean in `io.casehub.work.runtime.service`:

```java
@Unremovable
@ApplicationScoped
public class DeclarativeSlaBreachPolicy implements SlaBreachPolicy {

    @Inject SlaDefaultsYamlLoader loader;
    @Inject Provider<StrategyResolver> strategyResolverProvider;
    @Inject WorkItemsConfig config;

    private volatile SlaBreachPolicy fallbackPolicy;

    @Override
    public String id() { return "declarative"; }

    @PostConstruct
    void init() {
        if (id().equals(config.sla().declarative().fallback())) {
            throw new IllegalStateException(
                "casehub.work.sla.declarative.fallback cannot be '" + id() + "' — infinite recursion");
        }
    }

    private SlaBreachPolicy fallbackPolicy() {
        if (fallbackPolicy == null) {
            fallbackPolicy = strategyResolverProvider.get()
                    .resolve(SlaBreachPolicy.class, config.sla().declarative().fallback());
        }
        return fallbackPolicy;
    }

    @Override
    public BreachDecision onBreach(SlaBreachContext context) {
        SlaDeclarativeConfig cfg = loader.loadedConfig;
        if (cfg == null) return fallbackPolicy().onBreach(context);

        // 1. Walk scope hierarchy
        BreachAction action = resolveByScope(cfg, context.scope(), context.breachType());

        // 2. Fall back to defaults
        if (action == null) {
            action = switch (context.breachType()) {
                case COMPLETION_EXPIRED -> cfg.defaultOnCompletionExpiry();
                case CLAIM_EXPIRED -> cfg.defaultOnClaimExpiry();
            };
        }

        // 3. Delegate to fallback policy
        if (action == null) return fallbackPolicy().onBreach(context);

        Integer extHours = resolveExtensionHours(cfg, context.scope(), context.breachType());
        return action.toBreachDecision(extHours, config.defaultExpiryHours());
    }

    private BreachAction resolveByScope(SlaDeclarativeConfig cfg, Path scope, BreachType type) {
        Path current = scope;
        while (current != null && !current.equals(Path.root())) {
            SlaDeclarativeConfig.ScopeConfig sc = cfg.scopes().get(current);
            if (sc != null) {
                BreachAction action = switch (type) {
                    case COMPLETION_EXPIRED -> sc.onCompletionExpiry();
                    case CLAIM_EXPIRED -> sc.onClaimExpiry();
                };
                if (action != null) return action;
            }
            current = current.parent();
        }
        return null;
    }

    private Integer resolveExtensionHours(SlaDeclarativeConfig cfg, Path scope, BreachType type) {
        Path current = scope;
        while (current != null && !current.equals(Path.root())) {
            SlaDeclarativeConfig.ScopeConfig sc = cfg.scopes().get(current);
            if (sc != null) {
                Integer hours = type == BreachType.CLAIM_EXPIRED
                    ? (sc.claimExtensionHours() != null ? sc.claimExtensionHours() : sc.extensionHours())
                    : sc.extensionHours();
                if (hours != null) return hours;
            }
            current = current.parent();
        }
        return type == BreachType.CLAIM_EXPIRED
            ? (cfg.claimExtensionHours() != null ? cfg.claimExtensionHours() : cfg.extensionHours())
            : cfg.extensionHours();
    }
}
```

**Startup validation and lazy fallback:** `@PostConstruct init()` validates that the fallback id is not self-referential — this check uses config strings only and does not touch `StrategyResolver`. The fallback policy is resolved lazily via `Provider<StrategyResolver>` on first `onBreach()` call, following the `RoundRobinAssignmentStrategy` pattern (line 42). This avoids a circular dependency: `DefaultStrategyResolver`'s constructor eagerly iterates all `NamedStrategy` beans via `Instance<NamedStrategy>.toList()`, calling `strategy.id()` which triggers `@ApplicationScoped` bean creation through CDI proxies — if `@PostConstruct` accessed `StrategyResolver` directly, the singleton would be mid-construction. Invalid fallback ids surface on first breach as `IllegalArgumentException` from `StrategyResolver.resolve()`, caught by `ExpiryLifecycleService` as `BREACH_POLICY_MISCONFIGURED`.

### Activation

```properties
# Activate declarative SLA policy
casehub.work.sla.breach-policy=declarative

# Optional: chain to a custom CDI policy for unmatched scopes
casehub.work.sla.declarative.fallback=my-custom-policy
```

No changes to `ExpiryLifecycleService`. No Flyway migration needed (in-memory config, no persistence).

## Boundary: Templates vs SLA Defaults

`WorkItemTemplate.defaultExpiryHours` and `defaultClaimHours` set the **deadline** — when the SLA clock runs out.

SLA defaults (`onCompletionExpiry`, `onClaimExpiry`, `extensionHours`) define the **breach response** — what happens when the deadline passes.

These are independent concerns: a template says "this task expires in 4 hours"; the SLA default says "when it expires, escalate to team-leads." Both can be configured via YAML, but they live in separate files (`work-templates.yaml` vs `work-sla-defaults.yaml`) with separate loaders.

## Test Fixtures

### Unit Tests

- **`BreachActionTest`** — parse string shorthands (`fail`, `extend`, `exhausted`); parse object form (`{escalateTo: group, deadline: PT4H}`); parse colon-delimited config values; invalid values throw `IllegalArgumentException`; zero and negative extend durations rejected (`{extend: PT0S}`, `{extend: -PT1H}`); zero and negative escalation deadlines rejected (`{escalateTo: group, deadline: PT0S}`, `escalateTo:group:PT0S`); `toBreachDecision` resolves fallback extension hours correctly
- **`SlaDefaultsYamlLoaderTest`** — load from classpath resource; merge config property overrides; log WARN on override; multiple YAML resources merge scopes; conflicting defaults from multiple resources fail-fast; duplicate scope keys warn; malformed YAML fails fast; invalid scope key gives diagnostic error; zero and negative extensionHours rejected at load time; variable interpolation (`${env.X}`, `${sys.X}`)
- **`DeclarativeSlaBreachPolicyTest`** — scope hierarchy resolution (exact → parent → defaults → fallback); root scope goes straight to defaults; missing action at scope level walks to parent; missing defaults delegates to fallback policy via lazy `fallbackPolicy()`; startup validation rejects `fallback=declarative`; extensionHours inheritance through scope hierarchy; null extensionHours falls back to `config.defaultExpiryHours()`
- **`SlaDeclarativeConfigTest`** — immutable record construction; scope map with Path keys; nullable extensionHours

### Integration Tests

- **`DeclarativeSlaBreachPolicyIT`** — end-to-end: create a WorkItem with scope, let it expire, verify the declarative policy's breach decision is applied. Requires `casehub.work.sla.breach-policy=declarative` in test `application.properties` and a `META-INF/work-sla-defaults.yaml` test fixture.

## Deferred

### Runtime reconfiguration (#374)

The YAML is loaded at startup and immutable. Hot-reload of SLA defaults (e.g., via config change event) is deferred. For dev mode, Quarkus live-reload restarts the application, which re-triggers the loader.

### Multi-tenancy scoping (#375)

The YAML config is deployment-wide, not per-tenant. Per-tenant SLA overrides remain the domain of the `CallbackSlaBreachPolicyDecorator` or a custom `SlaBreachPolicy` bean. If per-tenant YAML config is needed, it would require a tenant-aware YAML discovery mechanism.

### YAML-declared Chained actions (#376)

`Chained` (atomic same-event fallback) is excluded from YAML. The candidateGroups-as-state pattern handles cross-breach sequencing without Chained. If a list-syntax for sequential fallback is needed in YAML, it can be added later without breaking the current schema.

## References

- `runtime/src/main/java/io/casehub/work/runtime/service/ExpiryLifecycleService.java:183` — `resolveBreachDecision()` per-item → policy chain
- `runtime/src/main/java/io/casehub/work/runtime/service/NoOpSlaBreachPolicy.java` — existing default policy
- `runtime/src/main/java/io/casehub/work/runtime/service/WorkItemTemplateYamlLoader.java` — classpath YAML loading pattern
- `runtime/src/main/java/io/casehub/work/runtime/config/WorkItemsConfig.java:153` — `SlaConfig` interface
- `api/src/main/java/io/casehub/work/api/spi/SlaBreachPolicy.java` — SPI interface (unchanged)
- `api/src/main/java/io/casehub/work/api/BreachDecision.java` — sealed decision interface (unchanged)
- `api/src/main/java/io/casehub/work/api/SlaBreachContext.java` — breach context with scope and preferences (unchanged)
- GE-20260810-4bccad — stateless escalation tier detection via candidateGroups
- GE-20260522-f7db12 — stateless multi-tier SLA escalation
- GE-20260810-724b82 — SlaBreachPolicy selection is config-driven via StrategyResolver
- GE-20260810-793376 — BreachDecision.Chained is atomic same-event fallback
- GE-20260810-878d00 — scope path encoding for metadata propagation
- specs/issue-362-escalation-skills-yaml/2026-08-28-escalation-skillmatch-yaml-design.md — per-item escalation config
- specs/issue-212-sla-breach-policy/2026-05-22-sla-breach-policy-design.md — SlaBreachPolicy SPI
