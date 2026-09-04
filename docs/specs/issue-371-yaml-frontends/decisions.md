## D1: Integration approach — SlaBreachPolicy implementation

**Choice:** Pure `SlaBreachPolicy` implementation (`DeclarativeSlaBreachPolicy`, id: `"declarative"`)
**Alternatives:**
- Extend `resolveBreachDecision` in `ExpiryLifecycleService` — embeds YAML logic in the service, breaking SPI separation
- PreferenceProvider-based — conflates deployment-level SLA declarations with runtime-configurable preferences
**Rationale:** Preserves the SPI contract, follows the established NamedStrategy + StrategyResolver pattern, doesn't touch ExpiryLifecycleService. The YAML policy is just another policy implementation — clean, testable, composable with per-item config.
**Trade-offs:** Requires a new config property to activate (`casehub.work.sla.breach-policy=declarative`).
**Sources:** ExpiryLifecycleService.java:75 (StrategyResolver resolution), NoOpSlaBreachPolicy.java, GE-20260810-724b82 (StrategyResolver is config-driven)
**Exploration:** quick
**Status:** captured

## D2: YAML action syntax — full BreachDecision coverage

**Choice:** Support all `BreachDecision` variants: `fail`, `extend`, `escalateTo` (with optional `deadline`), `exhausted`. Both string shorthand and object form. Skip `Chained` (YAML shouldn't express atomic same-event fallback — it's a programmatic concern).
**Alternatives:**
- Minimal (fail, extend, escalateTo only) — simpler parser but omits `exhausted`, which is a natural terminal action for deployment-wide config
- Minimal + exhausted — arbitrary line; if we support exhausted, there's no principled reason to omit it from "full"
**Rationale:** The YAML surface should map 1:1 to the `BreachDecision` sealed interface (minus `Chained`). This prevents users from hitting a wall where their deployment-wide policy can express some outcomes but not others. String shorthand (`fail`, `extend`) keeps simple cases readable.
**Trade-offs:** Slightly larger parser surface. `Exhausted` is rarely used in deployment-wide defaults (more common in per-scope overrides).
**Sources:** BreachDecision.java, GE-20260810-793376 (Chained is atomic same-event fallback, not suitable for YAML)
**Exploration:** quick
**Depends on:** D1 (integration approach)
**Status:** captured

## D4: Config property override surface — defaults only, colon-delimited syntax

**Choice:** Config properties for default actions: `casehub.work.sla.defaults.on-completion-expiry`, `.on-claim-expiry`, `.claim-extension-hours`, `.extension-hours`. Scopes always come from classpath YAML. Values use colon-delimited syntax: `fail`, `extend`, `exhausted:reason`, `escalateTo:group`, `escalateTo:group:PT4H`. When both config properties and classpath YAML exist, config properties win for the defaults section (logged at WARN level when override occurs).
**Alternatives:**
- No config overrides — all config from classpath YAML; tutorials must create a resource file even for basic defaults
- Full mirror — mirror entire YAML structure in config properties; SmallRye can't handle polymorphic values without custom converters
- String shorthands only (no colon syntax) — simple but can't express `escalateTo` with group/deadline, defeating the tutorial use case
**Rationale:** The simplest tutorials need "if unclaimed after 4 hours, escalate to team-leads" without writing a YAML file. The colon-delimited syntax enables `on-claim-expiry=escalateTo:team-leads:PT4H` as a single config property. Scopes with multiple overrides and complex config belong in the classpath YAML resource.
**Trade-offs:** Two config surfaces for the same concern (defaults). The colon-delimited syntax is less readable than YAML for compound actions. Override logging mitigates the "silent override" debugging hazard (R1-13).
**Sources:** WorkItemsConfig.java, issue #372 acceptance criteria ("Tutorial-ready example"), R1-12 (config surface must express primary tutorial case)
**Exploration:** quick
**Depends on:** D1, D2 (action syntax)
**Status:** revised (R1-12, R1-13)

## D3: Fallback chain mechanism — config property

**Choice:** New config property `casehub.work.sla.declarative.fallback=no-op`. The declarative policy resolves its fallback lazily via `Provider<StrategyResolver>` on first `onBreach()` call, following the `RoundRobinAssignmentStrategy` pattern. Self-reference (`fallback=declarative`) is rejected at `@PostConstruct` using config string comparison (no StrategyResolver access). Default fallback: `"no-op"` (Fail).
**Alternatives:**
- Hardcoded Fail — simpler but blocks hybrid YAML + Java deployments where custom policies handle exotic scopes
- `Instance<SlaBreachPolicy>` iteration — introduces a second resolution path, and CDI client proxies defeat reference-equality self-reference guards
- Direct `@Inject StrategyResolver` + `@PostConstruct` resolve — circular dependency: `DefaultStrategyResolver` eagerly iterates `Instance<NamedStrategy>`, calling `id()` which triggers `@ApplicationScoped` bean creation; if `@PostConstruct` accesses StrategyResolver, the singleton is mid-construction
- Inject-all + iterate — auto-discovers policies but ordering is undefined; implicit behavior is worse than explicit config
**Rationale:** Explicit config for the fallback preserves the NamedStrategy selection model. `Provider<StrategyResolver>` defers resolution until after CDI startup, avoiding the circular dependency while still using the canonical resolution mechanism. Hybrid deployments set `declarative.fallback=my-custom-policy` and get YAML for most scopes with a CDI policy for the rest. Invalid fallback ids surface on first breach as a clear `IllegalArgumentException`. The default `no-op` means standalone YAML works without additional config.
**Trade-offs:** One more config property. Invalid fallback ids surface on first breach rather than at startup (same trade-off as `RoundRobinAssignmentStrategy`). The fallback policy must be a valid `SlaBreachPolicy` bean in CDI.
**Sources:** GE-20260810-724b82 (StrategyResolver is config-driven), RoundRobinAssignmentStrategy.java:42 (Provider pattern precedent), DefaultStrategyResolver (bytecode — eager Instance iteration), WorkItemsConfig.SlaConfig
**Exploration:** quick
**Depends on:** D1 (integration approach)
**Status:** revised (R1-01, R1-02, R2 circular dependency)

## D5: Module placement — runtime

**Choice:** `DeclarativeSlaBreachPolicy` and `SlaDefaultsYamlLoader` live in the runtime module alongside `NoOpSlaBreachPolicy` and `ExpiryLifecycleService`.
**Alternatives:**
- New sla-yaml module — cleaner dependency separation but adds a module for ~3 classes
- api module — wrong; api is for interfaces and records, not implementations
**Rationale:** The declarative policy is a `SlaBreachPolicy` implementation, same as `NoOpSlaBreachPolicy`. Same module, same package (`io.casehub.work.runtime.service`). Jackson YAML is already a transitive dependency (used by `WorkItemTemplateYamlLoader`). No new module needed.
**Trade-offs:** None significant. The runtime module grows by ~3 classes.
**Sources:** NoOpSlaBreachPolicy.java, WorkItemTemplateYamlLoader.java, docs/MODULES.md
**Exploration:** quick
**Depends on:** D1 (integration approach)
**Status:** captured

## D6: Scope matching algorithm — hierarchical, most-specific wins

**Choice:** Hierarchical scope matching using `Path.parent()` walk. Most-specific match wins. Resolution order: exact scope match → `scope.parent()` → ... → `Path.root()` → YAML defaults → fallback policy.
**Alternatives:**
- Exact match only — simpler but forces users to enumerate every scope path; no inheritance
- Prefix matching (string-based) — fragile; `casehub/cli` would match `casehub/clinical`
**Rationale:** `Path.parent()` provides correct hierarchical matching. A WorkItem with scope `casehubio/clinical/triage` first checks `casehubio/clinical/triage`, then `casehubio/clinical`, then `casehubio`, then root, then defaults. This matches the PreferenceProvider scope-resolution model, preventing the platform from having two incompatible scope algorithms (R1-18). The YAML scopes are keyed by their full path string.
**Trade-offs:** Users must understand that a scope `casehubio/clinical` applies to ALL items under that path. A broad scope can unintentionally catch items that should use the default.
**Sources:** Path.class (parent(), isAncestorOf(), segments()), SlaBreachContext.java, R1-18 (scope matching is architecturally significant)
**Exploration:** quick
**Depends on:** D1 (integration approach)
**Status:** captured

---

# Issue #373 — Progress Definition YAML

## D7: Named definition templates — ProgressDefinition + registry

**Choice:** `ProgressDefinition` record (name, shapeType, definition JsonNode) in progress-api. `ProgressDefinitionRegistry` SPI for lookup. `ProgressDefinitionYamlLoader` in progress-runtime loads from `META-INF/work-progress-definitions.yaml`. `ProgressCreateRequest` gains optional `definitionName` field.
**Alternatives:**
- New `stage` shape type — adds a shape for what is essentially step + transitions
- Extend `StepDefinition` with transitions field — changes existing API, mixes concerns
**Rationale:** Named templates follow the `WorkItemTemplate` pattern. The YAML stages/transitions produce a definition JSON consumed by the step shape. No new shape type needed.
**Trade-offs:** The transitions map in YAML is stored as part of the definition JSON — not strongly typed at the API level.
**Sources:** ProgressInstance.java, ProgressCreateRequest.java, StepDefinition.java, WorkItemTemplateYamlLoader pattern
**Exploration:** quick
**Status:** captured

## D8: Module placement — progress-api + progress-runtime

**Choice:** `ProgressDefinition` record and `ProgressDefinitionRegistry` in progress-api. `ProgressDefinitionYamlLoader` in progress-runtime.
**Alternatives:**
- progress-runtime only — callers can't reference definitions without runtime dependency
- progress-core — definitions are domain types, not validation logic
**Rationale:** `ProgressDefinition` is a domain type used by `ProgressCreateRequest`. Following the api/runtime split pattern.
**Trade-offs:** None significant.
**Sources:** progress-api module structure, WorkItemTemplate pattern
**Exploration:** quick
**Depends on:** D7
**Status:** captured
