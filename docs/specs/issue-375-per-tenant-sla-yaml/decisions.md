## D1: Integration approach — Preferences system

**Choice:** Integrate per-tenant SLA config with the platform's existing `PreferenceProvider`/`Preferences` system. Define SLA-specific `PreferenceKey` types. A CDI `@Decorator` on `SlaBreachPolicy` checks `context.preferences()` for tenant-set SLA preferences before delegating to the underlying policy.
**Alternatives:**
- Standalone SLA config store (new entity, Flyway migration, REST API) — builds a parallel per-tenant config system when the platform already has one
- Tenant-keyed YAML files — still classpath-bound, doesn't solve "without deploying separate JARs"
- Remote callbacks only (`CallbackSlaBreachPolicyDecorator`) — already exists but requires running an external service
**Rationale:** `ExpiryLifecycleService.buildBreachContext()` already resolves preferences via `preferenceProvider.resolve(new SettingsScope(tenancyId, scope, now))` — the tenant+scope-aware infrastructure is wired in but unused for SLA decisions. Using it avoids building parallel infrastructure and gives tenants per-scope control through the platform's existing preference editor/API.
**Issue deviation:** Issue #375 asks for "per-tenant SLA defaults YAML" — a YAML-based mechanism. This decision uses preferences instead because boundary rules (PLATFORM.md) prohibit parallel path, scope, preference, or principal types. The platform already owns `PreferenceProvider`/`Preferences`/`PreferenceKey` as the tenant-aware configuration mechanism. Building a tenant-keyed YAML discovery mechanism would create a parallel per-tenant config system. The parent issue #372 delivered deployment-wide YAML; #375's intent is tenant-level overrides of that YAML — preferences are the platform's correct mechanism for that.
**Trade-offs:** Depends on `PreferenceProvider` implementation being correctly deployed. Tenants configure SLA via the generic preference API, not a dedicated SLA admin surface.
**Sources:** ExpiryLifecycleService.java:318 (buildBreachContext), SlaBreachContext.java, PreferenceProvider.class, WorkPreferenceKeys.java, CallbackSlaBreachPolicyDecorator.java, PLATFORM.md boundary rules
**Exploration:** quick
**Status:** revised (R1-03: integration point changed from embedding in DeclarativeSlaBreachPolicy to CDI decorator; R1-04: added issue deviation acknowledgment)

## D2: Scope interaction model — separate layer via decorator

**Choice:** Preferences are checked as a complete layer before the underlying policy. The `PreferenceSlaBreachPolicyDecorator` checks `context.preferences()` for tenant-set SLA action preferences. If a preference is found for the item's resolved scope, the decorator returns immediately — the underlying policy (YAML walk, custom, etc.) is never consulted. If no preference is set, the decorator delegates unchanged.
**Alternatives:**
- Overlay model — preferences merged with YAML per scope level during the hierarchy walk. Requires a `Preferences` API that distinguishes "set at this scope" from "inherited from parent" — no such API exists in `PreferenceProvider`/`Preferences`/`MapPreferences`. Would also require multiple `PreferenceProvider.resolve()` calls during the walk. More complex for unclear benefit.
**Rationale:** The separate-layer model is both simpler to reason about and consistent with how the infrastructure actually works. `ExpiryLifecycleService.buildBreachContext()` resolves preferences once (pre-resolved) and passes them as `SlaBreachContext.preferences()`. `MapPreferences` is a flat map lookup with no per-level scope awareness. A decorator that checks this pre-resolved result is the natural implementation. The model is clear: preferences override, or they don't. No per-level merge confusion.
**Trade-offs:** A preference set at scope `casehubio` will shadow YAML at all child scopes (`casehubio/clinical`, etc.) for that breach type. Tenants wanting fine-grained scope control must set preferences at fine-grained scopes. This is the intended semantic: a broad preference is a broad override.
**Sources:** ExpiryLifecycleService.java:318 (buildBreachContext — single resolve), MapPreferences.class (flat map lookup), PreferenceProvider.class (no scope-level discrimination), SettingsScope.class
**Exploration:** quick
**Depends on:** D1 (integration approach), D6 (decorator architecture)
**Status:** revised (R1-01: corrected overlay/separate-layer contradiction; model changed from aspirational overlay to implementable separate-layer)

## D3: Preference granularity — per-scope

**Choice:** Tenants can set SLA preferences at any scope level, not just tenant-wide defaults. `PreferenceProvider.resolve(SettingsScope)` already takes `(tenancyId, scope)` and handles hierarchical resolution — per-scope preferences come from the platform infrastructure.
**Alternatives:**
- Tenant defaults only — one flat set of SLA preferences per tenant, applied before the YAML scope walk. Simpler but prevents tenants from customizing different organizational units differently.
**Rationale:** Full parity with the YAML scope model. A clinical division and a finance division within the same tenant can have different SLA policies. The `PreferenceProvider` already supports this — restricting to tenant-wide defaults would discard existing capability.
**Trade-offs:** More preference entries to manage per tenant. Under the separate-layer model (D2), a preference set at a parent scope cascades to all child scopes via `PreferenceProvider` resolution. Tenants with complex scope hierarchies must understand that a broad preference is a broad override. The separate-layer model simplifies reasoning compared to overlay: preferences either win or they don't.
**Sources:** SettingsScope.class (tenancyId + scope + effectiveAt), PreferenceProvider.class, DeclarativeSlaBreachPolicy.java:80-100 (resolveByScope walk)
**Exploration:** quick
**Depends on:** D1 (integration approach), D2 (scope interaction model)
**Status:** captured

## D4: Preference value type — BreachActionPreference

**Choice:** New `BreachActionPreference` record implementing `SingleValuePreference`, wrapping `BreachAction`. Serialization uses the existing `BreachAction.parseColon()` format (same colon-delimited syntax as config properties: `fail`, `extend:PT6H`, `escalateTo:group:PT4H`, `exhausted:reason`). Four preference keys in `WorkPreferenceKeys`: `SLA_ON_COMPLETION_EXPIRY`, `SLA_ON_CLAIM_EXPIRY` (BreachActionPreference), `SLA_EXTENSION_HOURS`, `SLA_CLAIM_EXTENSION_HOURS` (IntPreference).
**Alternatives:**
- Raw string preference + lazy parsing — no type safety, validation deferred to use-time
- Composite JSON preference — single key holding a JSON blob per scope, different from key-per-setting pattern used by other preferences
**Rationale:** Follows the `IntPreference` pattern exactly (typed-preference-keys protocol PP-20260517-2cd5f0). Reuses `BreachAction.parseColon()` which handles all simple (non-chained) action variants. Type-safe at parse time. The four YAML-configurable fields get preference counterparts for simple action parity.
**ChainedAction exclusion:** `parseColon()` does not handle `ChainedAction` — this is intentional. Preferences are single-value tenant overrides ("on completion expiry, extend"). Complex fallback chains (`[{escalateTo: surgery, deadline: PT24H}, fail]`) are a deployment-level YAML concern expressing operational policy with multiple contingencies. Tenants needing custom fallback chains use the `CallbackSlaBreachPolicyDecorator` (remote callback) which can return any `BreachDecision` including `Chained`.
**Trade-offs:** New preference type in casehub-work (not platform). The colon-delimited format has the same limitation as config properties — group names cannot contain colons. ChainedAction is not expressible via preferences — by design.
**Sources:** BreachAction.java:79-115 (parseColon), IntPreference.class, SingleValuePreference.class, PreferenceKey.class, WorkPreferenceKeys.java
**Exploration:** quick
**Depends on:** D1 (integration approach)
**Status:** revised (R1-02: corrected "all action variants" to "all simple (non-chained) action variants"; documented ChainedAction exclusion as design choice)

## D5: Breach resolution chain — preferences at priority 3

**Choice:** Tenant preferences slot in at priority 3, after per-item fields and remote callbacks, before the config-selected policy. The full chain: (1) per-item fields → (2) remote callback decorator (`APPLICATION + 100`) → (3) preference decorator (`APPLICATION + 200`) → (4) config-selected policy (declarative, no-op, or custom) → (5) policy-internal fallback.
**Alternatives:**
- Preferences before callbacks — contradicts the principle that tenant-registered callbacks are explicit opt-ins that override deployment defaults
- Preferences after the config-selected policy — the policy would always run before preferences, defeating the purpose
- Preferences embedded inside DeclarativeSlaBreachPolicy — couples preferences to one specific policy implementation; dead code for non-declarative deployments
**Rationale:** Callbacks are an explicit, tenant-managed override mechanism (the tenant runs a service and registers it). Preferences are a configuration mechanism (the tenant sets a value). Explicit runtime behavior should override static configuration. The decorator architecture makes each layer independent and composable. CDI decorator priority ordering is ascending (lower value = outermost, called first): `CallbackSlaBreachPolicyDecorator` at `APPLICATION + 100` is outermost; `PreferenceSlaBreachPolicyDecorator` at `APPLICATION + 200` is next; the config-selected policy is innermost.
**Trade-offs:** A tenant with both a callback and preferences will have the callback win — preferences are redundant in that case. This is intentional.
**Sources:** ExpiryLifecycleService.java:183-206 (resolveBreachDecision — per-item priority), CallbackSlaBreachPolicyDecorator.java:24 (@Priority APPLICATION + 100), DeclarativeSlaBreachPolicy.java:62-76 (YAML priority)
**Exploration:** quick
**Depends on:** D1 (integration approach), D6 (decorator architecture)
**Status:** revised (R1-05: chain updated to reflect decorator architecture; preferences now work with any SlaBreachPolicy, not just declarative; R2-01: preference decorator priority corrected from APPLICATION + 50 to APPLICATION + 200 — CDI ascending ordering means lower values are outermost)

## D6: Integration architecture — CDI decorator

**Choice:** Implement preference-based SLA overrides as a CDI `@Decorator` on `SlaBreachPolicy`, following the established `CallbackSlaBreachPolicyDecorator` pattern. `PreferenceSlaBreachPolicyDecorator` at `@Priority(APPLICATION + 200)` intercepts `onBreach()`, checks `context.preferences()` for tenant-set SLA action keys, and returns immediately if found. If no preferences are set, delegates to the underlying policy unchanged.
**Alternatives:**
- Embed preference checks inside `DeclarativeSlaBreachPolicy.onBreach()` — preferences consulted only when the declarative policy is the active strategy. Custom or no-op policies never see preferences. Tighter coupling.
**Rationale:**
1. **Works with any `SlaBreachPolicy`.** A deployer using a custom policy still gets tenant preference overrides. The entire preference infrastructure is not dead code for non-declarative deployments.
2. **Follows an established platform pattern.** `CallbackSlaBreachPolicyDecorator` demonstrates the exact approach — a decorator that checks for tenant-managed configuration and delegates if absent.
3. **Preserves `DeclarativeSlaBreachPolicy` unchanged.** No modification to proven YAML resolution logic.
4. **Priority ordering is explicit.** CDI decorator priority (ascending — lower value = outermost) sets the chain order unambiguously: callback (`+100`, outermost) → preferences (`+200`, inner) → underlying policy.
5. **Eliminates overlay/layer confusion.** The decorator IS a separate layer by design — there is no question about whether preferences "merge" with YAML per scope level.
**Trade-offs:** Preferences become a separate layer, not an overlay. A preference set at any scope fully overrides the underlying policy for that scope — no partial merge with YAML at specific scope levels. This is the correct semantic for a tenant override.
**Sources:** CallbackSlaBreachPolicyDecorator.java (established pattern), SlaBreachPolicy.java javadoc (SPI designed for preference-aware policies), SlaBreachContext.java (carries Preferences)
**Exploration:** quick (surfaced by reviewer R1-03 as implicit decision)
**Depends on:** D1 (integration approach)
**Status:** revised (R2-01: priority corrected from APPLICATION + 50 to APPLICATION + 200 — CDI ascending ordering means lower values are outermost; callback at +100 must remain outermost to be checked first)
