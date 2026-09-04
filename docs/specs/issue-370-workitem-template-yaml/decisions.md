## D1: Standalone classpath YAML files as template source

**Choice:** Templates are declared in standalone classpath files (`META-INF/work-templates.yaml`). A `@Startup` loader reads them at boot and persists via `WorkItemTemplateStore.put()`. CaseDefinition references templates by name via existing `templateRef`.
**Alternatives:**
- Classpath + inline in CaseDefinition — co-located but crosses the engine/work boundary (CaseDefinitionSpec is owned by casehub-engine; casehub-work parsing it violates §3 boundary rules)
- Inline only — co-located but can't share templates across cases and forces engine schema changes
**Rationale:** Standalone files let casehub-work scan its own classpath resources without crossing into engine territory. Templates are referenced by name from `humanTask.templateRef:`, which already exists. Tutorials can reference standalone template files — the ergonomic loss vs. single-file co-location is minor.
**Trade-offs:** Templates live in a separate file from CaseDefinition. Acceptable — this respects the engine/work module boundary.
**Sources:** WorkItemTemplateService.findByRef(), HumanTaskTarget.templateRef(), EndpointConfigLoader (endpoints-config pattern precedent). Note: issue #370 body cites casehubio/parent#247 as yaml-core but that issue is "docs: sync neural-text deep-dive for examples modules" — the reference is incorrect in the issue itself.
**Exploration:** quick
**Status:** revised (R1: dropped inline CaseDefinition option per R1-04 boundary concern; corrected parent#247 reference per R1-09)

## D2: Startup-loader pattern (replaces WorkItemTemplateProvider SPI)

**Choice:** A `@Startup @ApplicationScoped` bean reads `META-INF/work-templates.yaml` from the classpath at `@PostConstruct` and calls `WorkItemTemplateStore.put()` for each template. A `@BuildStep` in the deployment module registers the resource pattern for native image inclusion (`NativeImageResourcePatternsBuildItem`). No new SPI.
**Alternatives:**
- WorkItemTemplateProvider SPI in api/ with @BuildStep processor — over-engineered for a single implementation, and structurally broken: `WorkItemTemplate` is a JPA entity in `runtime/`, so the SPI in `api/` cannot reference it without circular dependency or type splitting
- Direct store injection — functionally equivalent to this pattern; the startup-loader IS direct store injection with YAML parsing
**Rationale:** Follows the proven `endpoints-config` precedent (`EndpointConfigLoader`): YAML loader reads files → calls registry/store. Templates become regular DB entities — same type, same store, same `findByRef`. Eliminates the type representation gap (no need for an api/ record wrapping a runtime/ entity), eliminates the SPI (no new abstraction for a single implementation), and eliminates the dual-source resolution priority problem (all templates are DB entities after boot). YAML files on classpath are included in native images via `NativeImageResourcePatternsBuildItem` (same pattern as `WorkItemsProcessor` for SQL migrations) — no build-time optimization is lost. Validation happens at startup (throw on malformed YAML), which catches errors before any request is served.
**Trade-offs:** YAML templates are persisted to DB at boot, not kept as immutable classpath artifacts. Acceptable — (a) endpoints-config proves this pattern works, (b) redeployment re-upserts from YAML source, (c) REST overrides work naturally because they're the same store.
**Sources:** EndpointConfigLoader, WorkItemsProcessor.registerMigrationResources(), WorkItemTemplateStore.put()
**Exploration:** quick
**Status:** revised (R1: adopted startup-loader pattern per R1-02 type gap and R1-05 alternative)

## D3: Variable resolution deferred; when added, uses platform expression infrastructure

**Choice:** The primary deliverable (issue #370) is the YAML declaration format. Load-time interpolation of environment variables and system properties uses a local implementation (same `${var}` pattern as `EndpointConfigLoader.interpolate()`). Per-instantiation context parameterization (e.g., resolving `${case.name}` at WorkItem creation time) is deferred to a follow-on issue.
**Alternatives:**
- yaml-core VariableResolver at instantiation time — introduces a new dependency on yaml-core, violating ARC42STORIES §2 constraint ("runtime/ depends only on casehub-platform-api")
- ExpressionEngine (JQ/MVEL) at instantiation time — available via casehub-platform-expression (already a runtime dependency), but designed for typed expression compilation against context objects, not simple string interpolation. Right mechanism for complex parameterization when that need arises, wrong tool for `${var.name}` substitution.
- Static only — no variable support at all. Acceptable for initial delivery.
**Rationale:** The yaml-core dependency violates the stated architectural constraint. The ExpressionEngine infrastructure solves a different problem (typed data transformation) than simple string interpolation (`${prefix.name}`). Load-time env/property interpolation is trivially implementable locally (EndpointConfigLoader already does this). Per-instantiation parameterization is a separable concern that can be addressed with ExpressionEngine when needed.
**Trade-offs:** Templates are static after load — no per-instantiation parameterization in initial delivery. Acceptable for YAML-only tutorial goal (issue #370). Parameterization is a follow-on.
**Depends on:** D2 (templates loaded as DB entities)
**Sources:** EndpointConfigLoader.interpolate(), casehub-platform-expression (ExpressionEngine SPI), ARC42STORIES §2 constraint
**Exploration:** quick
**Status:** revised (R1: deferred per-instantiation variable resolution, removed yaml-core dependency per R1-03)

## D4: Templates referenced from CaseDefinition by name only — no new CaseDefinition schema section

**Choice:** CaseDefinition YAML references templates via the existing `humanTask.templateRef:` field. No `workItemTemplates:` section is added to CaseDefinitionSpec.
**Alternatives:**
- Top-level workItemTemplates section in CaseDefinition — crosses engine/work boundary (CaseDefinitionSpec is owned by casehub-engine; casehub-work would need to parse engine-owned YAML or engine would need to know about work templates). Requires cross-repo schema change.
**Rationale:** `HumanTaskTarget.templateRef()` already exists and resolves templates by UUID or name. Standalone YAML template files are loaded by casehub-work at startup into the DB store. The engine doesn't need to know about the YAML loading mechanism — it just resolves `templateRef` as before. This respects the boundary: casehub-work does not interpret case structure (ARC42STORIES §3).
**Trade-offs:** Templates are not co-located with CaseDefinition. Acceptable — the `templateRef` indirection already exists and is the established pattern.
**Depends on:** D1 (standalone files as the only YAML source)
**Sources:** CaseDefinitionSpec (casehub-engine api/), HumanTaskTarget.templateRef(), ARC42STORIES §3 boundary rules
**Exploration:** quick
**Status:** revised (R1: dropped inline CaseDefinition option per R1-04 boundary crossing)

## D5: Load-time collision semantics replace dual-source resolution priority

**Choice:** At startup, the YAML loader upserts templates by name. On name collision with an existing DB template: log WARN with both source identifiers (YAML file path and existing DB template ID) and overwrite. After boot, all templates are regular DB entities with no dual-source ambiguity. REST-created templates between boots are normal DB operations. Redeployment re-upserts from YAML source with WARN on each overwrite.
**Alternatives:**
- Error on collision — strict but blocks legitimate override scenarios (operator customizes a YAML-declared template via REST, then redeploy restores the YAML version)
- Silent override — the original D5 choice. No logging, no audit trail. The Quarkus config analogy used to justify this doesn't hold: Quarkus logs every config override at WARN level.
- Skip on collision — YAML loader skips if a DB template with the same name exists. Preserves REST customizations but means redeployments can't update YAML-declared templates.
**Rationale:** Upsert-with-WARN is observable and auditable. It matches the Quarkus config precedent properly (Quarkus logs every override). The overwrite semantic means YAML is the deployment-time source of truth — operators who customize via REST know their changes will be overwritten on redeploy, which is the same mental model as Quarkus config properties.
**Trade-offs:** REST customizations are overwritten on redeploy. This is intentional — YAML is the declarative source of truth. Operators who need persistent customizations should modify the YAML source.
**Sources:** EndpointConfigLoader collision handling, Quarkus config override logging
**Exploration:** quick
**Status:** revised (R1: replaced dual-source resolution with load-time collision semantics per R1-05 and R1-06)

## D6: YAML templates loaded under default tenant with pre-stamped tenancyId

**Choice:** YAML templates are loaded at `@Startup @PostConstruct` — no request context exists. The loader pre-stamps `template.tenancyId = TenancyConstants.DEFAULT_TENANT_ID` on each entity before calling `WorkItemTemplateStore.put()`. This bypasses `CurrentPrincipal.tenancyId()` entirely, which is critical because `SecurityIdentityCurrentPrincipal` (`@RequestScoped @Alternative @Priority(100)` in `casehub-platform-oidc`) displaces `MockCurrentPrincipal @DefaultBean` when OIDC is on the classpath, and `@RequestScoped` beans throw `ContextNotActiveException` outside a request context. Pre-stamping avoids the scope problem for ALL deployments — tutorial, single-tenant-with-OIDC, and multi-tenant alike. The upsert collision check (D5) must also use a direct Panache query with an explicit tenancyId parameter rather than `getByName()` (which delegates to `CurrentPrincipal`).
**Alternatives:**
- `TenancyConstants.PLATFORM_TENANT_ID` — makes templates globally visible, but `WorkItemTemplateStore.getByName()` queries filter by `CurrentPrincipal.tenancyId()`, so platform-tenant templates would be invisible to tenant-specific queries unless the store is modified
- Per-template tenancyId in YAML — each template entry specifies its tenant. Complex, premature for initial delivery.
- Per-tenant file configuration — separate YAML files per tenant, loaded in tenant context. Complex, premature for initial delivery.
- Rely on `MockCurrentPrincipal @DefaultBean` at startup — works only when OIDC is NOT on the classpath. Any deployment with `casehub-platform-oidc` (even single-tenant production apps using OIDC for authentication only) would fail with `ContextNotActiveException`.
**Rationale:** Pre-stamping `tenancyId` makes the loader independent of `CurrentPrincipal` scope. `DEFAULT_TENANT_ID` is the single-tenant sentinel, and issue #370 targets YAML-only tutorials (single-tenant). The startup loader can be extended to support tenant-aware loading without architectural changes — a future YAML field or config property can override the stamped tenancyId per template.
**Trade-offs:** Multi-tenant deployments get all YAML templates under `DEFAULT_TENANT_ID`. Acceptable — multi-tenancy is a follow-on.
**Sources:** TenancyConstants.DEFAULT_TENANT_ID, JpaWorkItemTemplateStore.put() tenancyId stamping (line 29), SecurityIdentityCurrentPrincipal @RequestScoped @Alternative @Priority(100) (oidc module, line 54), MockCurrentPrincipal @DefaultBean @ApplicationScoped (platform module, line 30)
**Exploration:** quick (surfaced by reviewer R1-07, refined by reviewer R2-01)
**Status:** revised (R2: clarified deferred scope — "any deployment with @RequestScoped CurrentPrincipal", not just "multi-tenant"; added pre-stamping approach to avoid ContextNotActiveException)

## D7: YAML templates are mutable DB entities after loading

**Choice:** YAML templates become regular mutable DB entities via `WorkItemTemplateStore.put()`. No mutability boundary exists between YAML-declared and REST-created templates. `WorkItemTemplateStore.put()` works on them identically. REST updates, deletes, and patches all apply normally. Redeployment re-upserts from YAML source (see D5).
**Alternatives:**
- Immutable YAML templates (non-entity, read-only SPI artifacts) — introduces a type split (entity vs. non-entity template representation), a mutability boundary (`put()` semantics differ for YAML vs. DB templates), and accidental materialisation risk (calling `put()` on a YAML template silently creates a DB copy)
**Rationale:** The startup-loader pattern eliminates the mutability boundary entirely. Templates loaded from YAML ARE DB entities — same type, same lifecycle, same operations. No special-case handling needed anywhere in the stack. This is the same pattern as `endpoints-config`: YAML endpoints become registry entries, indistinguishable from programmatically registered ones.
**Sources:** WorkItemTemplateStore.put(), EndpointConfigLoader → EndpointRegistry.register()
**Exploration:** quick (surfaced by reviewer R1-08)
**Status:** captured
