# Per-Tenant SLA Preferences — Design Spec

**Date:** 2026-09-01
**Status:** Draft
**Issue:** casehubio/work#375
**Parent:** casehubio/work#372 (closed — deployment-wide YAML defaults)
**Decisions:** [decisions.md](decisions.md) D1-D6

## Motivation

Issue #372 delivered deployment-wide SLA defaults via `META-INF/work-sla-defaults.yaml` and `DeclarativeSlaBreachPolicy`. The YAML config is global — all tenants share the same breach actions and scope hierarchy. Per-tenant SLA overrides require either:

- A remote callback service (`CallbackSlaBreachPolicyDecorator`) — heavyweight, requires the tenant to run a server
- A custom `SlaBreachPolicy` CDI bean — requires a JAR deployment

Neither is suitable for the common case: a tenant needs "when my items expire, extend by 8 hours instead of the deployment default of 4." This spec adds per-tenant SLA overrides via the platform's existing `PreferenceProvider`/`Preferences` system.

## Issue Deviation

Issue #375 asks for "per-tenant SLA defaults YAML." This design uses preferences instead of a YAML discovery mechanism because the platform's boundary rules prohibit parallel preference/configuration types. `PreferenceProvider`/`Preferences`/`PreferenceKey` is the platform's established tenant-aware configuration mechanism. #372 delivered deployment-wide YAML; #375's intent is tenant-level overrides of that YAML — preferences are the correct mechanism for that.

## Breach Resolution Chain (Updated)

After this work, the full breach resolution chain is:

| Priority | Mechanism | Layer | Source |
|----------|-----------|-------|--------|
| 1 | Per-item fields (#362) | `ExpiryLifecycleService.resolveBreachDecision()` | `WorkItemEntity` columns (V42) |
| 2 | Remote callback | `CallbackSlaBreachPolicyDecorator` (`APPLICATION + 100`) | `CallbackRegistry` per tenant |
| 3 | **Tenant preferences (NEW)** | **`PreferenceSlaBreachPolicyDecorator` (`APPLICATION + 200`)** | **`PreferenceProvider` per tenant+scope** |
| 4 | Config-selected policy | `DeclarativeSlaBreachPolicy` / custom / no-op | YAML, config properties, or CDI bean |

CDI decorator priority ordering is ascending (lower value = outermost, called first). The callback decorator at `APPLICATION + 100` is outermost; the preference decorator at `APPLICATION + 200` is next; the config-selected policy is innermost.

Priorities 1-2 are unchanged. Priority 3 is new. Priority 4 includes the existing YAML scope walk, YAML defaults, and fallback CDI policy chain — all unchanged.

## Changes

### BreachActionPreference — Typed Preference Value

New record in `io.casehub.work.runtime.preferences`:

```java
public record BreachActionPreference(BreachAction action) implements SingleValuePreference {

    public static final BreachActionPreference UNSET = new BreachActionPreference(null);

    public static BreachActionPreference parse(String raw) {
        return new BreachActionPreference(BreachAction.parseColon(raw));
    }

    @Override
    public String toSerializedValue() {
        if (action == null) return "";
        return toColonString(action);
    }

    private static String toColonString(BreachAction action) {
        return switch (action) {
            case BreachAction.FailAction f ->
                "sla-breach".equals(f.reason()) ? "fail" : "fail:" + f.reason();
            case BreachAction.ExtendAction e ->
                e.explicitDuration() == null ? "extend" : "extend:" + e.explicitDuration();
            case BreachAction.EscalateToAction e ->
                e.deadline() == null
                    ? "escalateTo:" + e.group()
                    : "escalateTo:" + e.group() + ":" + e.deadline();
            case BreachAction.ExhaustedAction e ->
                "sla-exhausted".equals(e.reason()) ? "exhausted" : "exhausted:" + e.reason();
            case BreachAction.ChainedAction c ->
                throw new UnsupportedOperationException(
                    "ChainedAction cannot be serialized as a preference — use CallbackSlaBreachPolicyDecorator for complex fallback chains");
        };
    }
}
```

Uses the existing `BreachAction.parseColon()` for deserialization — same colon-delimited syntax as config properties (`fail`, `extend:PT6H`, `escalateTo:team-leads:PT4H`, `exhausted:reason`).

**ChainedAction exclusion (D4):** `parseColon()` does not handle `ChainedAction`. Preferences are single-value tenant overrides. Complex fallback chains are a deployment-level YAML concern; tenants needing custom chains use the `CallbackSlaBreachPolicyDecorator`.

### WorkPreferenceKeys — New SLA Keys

Four new preference keys added to `io.casehub.work.runtime.preferences.WorkPreferenceKeys`:

```java
public static final PreferenceKey<BreachActionPreference> SLA_ON_COMPLETION_EXPIRY =
        new PreferenceKey<>("casehub.work", "sla.on-completion-expiry",
                BreachActionPreference.UNSET, BreachActionPreference::parse);

public static final PreferenceKey<BreachActionPreference> SLA_ON_CLAIM_EXPIRY =
        new PreferenceKey<>("casehub.work", "sla.on-claim-expiry",
                BreachActionPreference.UNSET, BreachActionPreference::parse);

public static final PreferenceKey<IntPreference> SLA_EXTENSION_HOURS =
        new PreferenceKey<>("casehub.work", "sla.extension-hours",
                IntPreference.of(0), IntPreference::parse);

public static final PreferenceKey<IntPreference> SLA_CLAIM_EXTENSION_HOURS =
        new PreferenceKey<>("casehub.work", "sla.claim-extension-hours",
                IntPreference.of(0), IntPreference::parse);
```

**Default values:** `PreferenceKey` constructor requires `defaultValue` to be non-null (`Objects.requireNonNull`). The decorator uses `Preferences.get(key)` — not `getOrDefault(key)` — which returns `null` when no preference is stored, regardless of the key's default value. The defaults are never used in practice; they exist solely to satisfy the non-null constraint.

- `BreachActionPreference.UNSET` — a sentinel instance with a null `BreachAction`. The decorator checks `pref == null` (not stored) to decide whether to delegate. The sentinel is never returned by `get()` — it exists only as the `PreferenceKey` default.
- `IntPreference.of(0)` — placeholder default. The decorator checks `get()` return for null, never calls `getOrDefault()`.

### PreferenceSlaBreachPolicyDecorator — CDI Decorator

New `@Decorator` in `io.casehub.work.runtime.preferences`:

```java
@Decorator
@Priority(jakarta.interceptor.Interceptor.Priority.APPLICATION + 200)
public class PreferenceSlaBreachPolicyDecorator implements SlaBreachPolicy {

    private final SlaBreachPolicy delegate;
    private final WorkItemsConfig config;

    @Inject
    PreferenceSlaBreachPolicyDecorator(@Delegate final SlaBreachPolicy delegate,
                                       final WorkItemsConfig config) {
        this.delegate = delegate;
        this.config = config;
    }

    @Override
    public String id() {
        return delegate.id();
    }

    @Override
    public BreachDecision onBreach(final SlaBreachContext context) {
        final Preferences prefs = context.preferences();

        final PreferenceKey<BreachActionPreference> actionKey = switch (context.breachType()) {
            case COMPLETION_EXPIRED -> WorkPreferenceKeys.SLA_ON_COMPLETION_EXPIRY;
            case CLAIM_EXPIRED -> WorkPreferenceKeys.SLA_ON_CLAIM_EXPIRY;
        };

        final BreachActionPreference actionPref;
        try {
            actionPref = prefs.get(actionKey);
        } catch (final Exception e) {
            LOG.warnf(e, "Failed to parse SLA preference for %s — falling back to policy",
                      actionKey.qualifiedName());
            return delegate.onBreach(context);
        }
        if (actionPref == null) {
            return delegate.onBreach(context);
        }

        // Self-escalation guard: if the action escalates to a group the item
        // already belongs to, skip this preference and delegate
        if (actionPref.action() instanceof BreachAction.EscalateToAction esc
                && context.task().candidateGroups().contains(esc.group())) {
            return delegate.onBreach(context);
        }

        final Integer extensionHours = resolveExtensionHours(prefs, context.breachType());
        return actionPref.action().toBreachDecision(extensionHours, config.defaultExpiryHours());
    }

    private Integer resolveExtensionHours(Preferences prefs, BreachType type) {
        if (type == BreachType.CLAIM_EXPIRED) {
            IntPreference claimExt = prefs.get(WorkPreferenceKeys.SLA_CLAIM_EXTENSION_HOURS);
            if (claimExt != null) return claimExt.value();
        }
        IntPreference ext = prefs.get(WorkPreferenceKeys.SLA_EXTENSION_HOURS);
        return ext != null ? ext.value() : null;
    }
}
```

**Design rationale (D6):**
1. Works with any `SlaBreachPolicy` — declarative, custom, or no-op. Not coupled to `DeclarativeSlaBreachPolicy`.
2. Follows the established `CallbackSlaBreachPolicyDecorator` pattern exactly.
3. `DeclarativeSlaBreachPolicy` is unchanged — no modification to proven YAML resolution logic.
4. Priority `APPLICATION + 200` places it after the callback decorator (`APPLICATION + 100`). CDI ascending ordering means lower value = outermost = called first. Chain: callback (outermost) → preferences → underlying policy (innermost).

**Separate-layer semantics (D2):** If a tenant has a preference set for the breach type at the item's resolved scope, the decorator returns immediately. The underlying policy (YAML walk, custom, etc.) is never consulted. If no preference is set, the decorator delegates unchanged. This is the correct semantic for a tenant override — preferences either win or they don't. No partial merge with YAML at specific scope levels.

**Resolution of extensionHours:** When the decorator handles a breach action, it must resolve `extensionHours` for `ExtendAction` and `EscalateToAction` conversions. The decorator checks the tenant's `SLA_EXTENSION_HOURS` / `SLA_CLAIM_EXTENSION_HOURS` preferences. If neither is set, `toBreachDecision()` receives `null` for `fallbackExtensionHours` and uses `config.defaultExpiryHours()` as the last-resort fallback — same value as `DeclarativeSlaBreachPolicy` uses.

**Self-escalation guard:** If the resolved action is `EscalateToAction` and the target group is already in `context.task().candidateGroups()`, the decorator delegates to the underlying policy. This prevents infinite re-escalation loops (same guard as `DeclarativeSlaBreachPolicy.unwrapSelfEscalation()`).

**Parse failure resilience:** Preference parsing (`BreachAction.parseColon()`) can throw `IllegalArgumentException` for malformed stored values. The decorator catches all exceptions from `prefs.get()`, logs a warning, and delegates to the underlying policy — same pattern as `CallbackSlaBreachPolicyDecorator`'s remote callback failure handling. This prevents a misconfigured preference from aborting batch expiry processing.

**extensionHours coupling:** Under the separate-layer model (D2), when a tenant sets an action preference, the YAML layer's `extensionHours` is never consulted. If a tenant sets `sla.on-completion-expiry=extend` without also setting `sla.extension-hours`, the extension duration falls back to `config.defaultExpiryHours()` — not the YAML's scope-level `extensionHours`. Tenants using `extend` or `escalateTo` actions should set `extensionHours` alongside the action preference:

| Preference set | Also set | Why |
|----------------|----------|-----|
| `sla.on-completion-expiry=extend` | `sla.extension-hours=N` | Otherwise falls back to deployment-wide `config.defaultExpiryHours()` |
| `sla.on-completion-expiry=escalateTo:group` | `sla.extension-hours=N` (optional) | Deadline defaults to `config.defaultExpiryHours()` if not set |
| `sla.on-completion-expiry=fail` | Nothing | No duration needed |
| `sla.on-completion-expiry=exhausted` | Nothing | No duration needed |

### Activation

No config property needed. The decorator is active whenever `casehub-work` is on the classpath. It is invisible (pure passthrough) when no SLA preferences are set for the current tenant.

This is consistent with `CallbackSlaBreachPolicyDecorator` — it is always active but transparent when no callbacks are registered.

## Boundary: Templates vs SLA Defaults vs Preferences

| Concern | Mechanism | Scope |
|---------|-----------|-------|
| **Deadline** (when the SLA clock runs out) | `WorkItemTemplate.defaultExpiryHours` | Per-template |
| **Breach response** (deployment-wide) | `META-INF/work-sla-defaults.yaml` | Deployment-wide, scope-hierarchical |
| **Breach response** (per-tenant) | `PreferenceProvider` SLA keys | Per-tenant, per-scope |
| **Breach response** (remote) | `CallbackSlaBreachPolicyDecorator` | Per-tenant, arbitrary logic |

## Test Fixtures

### Unit Tests

- **`BreachActionPreferenceTest`** — parse all colon-delimited variants (`fail`, `extend`, `extend:PT6H`, `escalateTo:group`, `escalateTo:group:PT4H`, `exhausted`, `exhausted:reason`); round-trip serialization (`parse → toSerializedValue → parse`); `ChainedAction` serialization throws `UnsupportedOperationException`; invalid strings throw `IllegalArgumentException`
- **`PreferenceSlaBreachPolicyDecoratorTest`** — preference set → decorator returns without delegating; no preference → decorator delegates unchanged; completion vs claim routes to correct key; `extensionHours` preference resolves correctly (claim extension → extension → `config.defaultExpiryHours()`); `claimExtensionHours` preference resolves independently; decorator works with non-declarative delegate (mock policy); `id()` always delegates; self-escalation guard — `escalateTo:team-leads` when `candidateGroups=team-leads` → delegates to underlying policy; malformed preference value → logs warning and delegates to underlying policy (not exception)
- **`WorkPreferenceKeysTest`** — key names follow `casehub.work.sla.*` namespace; parser round-trips; null default values (or sentinel check if null rejected)

### Integration Tests

- **`PreferenceSlaBreachPolicyIT`** — end-to-end: set a preference for tenant A at scope X, create a WorkItem for tenant A at scope X with `expiresAt`, let it expire, verify the preference's breach action is applied. Verify tenant B (no preference) gets the deployment-wide YAML default. Requires a `PreferenceProvider` implementation that supports programmatic preference setting in tests.
- **`PreferenceCallbackPriorityIT`** — tenant has both a callback and a preference. Verify the callback wins (priority 2 > priority 3).

## Deferred

### Preference admin UI

No dedicated UI for SLA preferences. Tenants configure via the platform's generic preference editor/API. A purpose-built "SLA Configuration" panel could be added later. File as a GitHub issue during implementation if needed.

### Preference validation at write time

The decorator validates at read time (parse on breach, with fallback on failure). Write-time validation (rejecting invalid preference values before they're stored) depends on the `PreferenceProvider` implementation supporting validators. Not in scope. File as a GitHub issue during implementation if needed.

### ChainedAction in preferences

`ChainedAction` is excluded from preferences by design. If list-syntax preferences are needed, extend `BreachActionPreference` to handle ordered lists. File as a GitHub issue during implementation if needed.

## References

- `runtime/src/main/java/io/casehub/work/runtime/service/DeclarativeSlaBreachPolicy.java` — unchanged, deployment-wide YAML policy
- `runtime/src/main/java/io/casehub/work/runtime/service/SlaDefaultsYamlLoader.java` — unchanged, classpath YAML loader
- `runtime/src/main/java/io/casehub/work/runtime/callback/CallbackSlaBreachPolicyDecorator.java` — pattern for CDI decorator on SlaBreachPolicy
- `runtime/src/main/java/io/casehub/work/runtime/service/ExpiryLifecycleService.java:318` — `buildBreachContext()` resolves preferences via `PreferenceProvider`
- `runtime/src/main/java/io/casehub/work/runtime/preferences/WorkPreferenceKeys.java` — existing SLA preference keys
- `runtime/src/main/java/io/casehub/work/runtime/service/BreachAction.java:79` — `parseColon()` reused for preference serialization
- `api/src/main/java/io/casehub/work/api/SlaBreachContext.java` — carries `Preferences` (unused before this change)
- `api/src/main/java/io/casehub/work/api/spi/SlaBreachPolicy.java` — SPI interface (unchanged)
- Platform: `PreferenceProvider.class`, `Preferences.class`, `PreferenceKey.class`, `SingleValuePreference.class`, `IntPreference.class`, `SettingsScope.class`
- specs/issue-371-yaml-frontends/2026-08-30-declarative-sla-policy-design.md — parent #372 design
- specs/issue-212-sla-breach-policy/2026-05-22-sla-breach-policy-design.md — SLA breach policy SPI
- specs/issue-256-multi-tenancy-tenantid/2026-06-08-multi-tenancy-design.md — multi-tenancy foundation
- Protocol: async-event-tenant-context-propagation (PP-20260609-fb6563)
- Protocol: store-tenancy-stamping-on-insert (PP-20260609-bdac7e)
- decisions.md D1-D6
