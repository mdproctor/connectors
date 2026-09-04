# Design: Migrate FilterEngine to platform LabelRule

**Issue:** casehubio/work#314
**Date:** 2026-07-20
**Branch:** issue-314-migrate-filterengine-labelrule

## Context

Work has two parallel filter systems that both observe `WorkItemLifecycleEvent` and apply INFERRED labels:

1. **Queues module** (`FilterEngine`/`FilterEngineImpl`) — REST-managed persisted filters with JEXL/JQ conditions, multi-pass evaluation, FilterChain inverse index, and CDI lambda filters (`WorkItemFilterBean`).
2. **Runtime module** (`FilterRegistryEngine`) — CDI-produced permanent filters + DB-persisted dynamic filters, single-pass with event-type filtering, extensible action SPI (`FilterAction`: APPLY_LABEL, OVERRIDE_CANDIDATE_GROUPS, SET_PRIORITY).

Platform now provides `LabelRule`, `LabelAction`, `CompiledExpression`, `LambdaExpression`, and `ExpressionEngineRegistry` — a generic label evaluation toolkit. This migration unifies both work filter systems into one LabelRule-based engine.

Platform prerequisites (landed on main):
- platform#189 — `LabelRule.triggerEvents` for event-scoped evaluation (`a3c1d94`)
- platform#191 — `JexlExpressionEngine` in platform expression module (`bb43338`)

## Design decisions

| Decision | Choice | Rationale |
|---|---|---|
| Scope | Unify both filter systems | Eliminates architectural duplication |
| Multi-pass | Drop (single-pass only) | No production usage of chain propagation. Platform's model is correct: rules evaluate against source data, not each other's outputs. |
| Non-label actions | Delete SPI, defer observer pattern | `OVERRIDE_CANDIDATE_GROUPS` and `SET_PRIORITY` have no production callers. Delete the SPI; file issue for future label-triggered observer pattern if needed. |
| Event-type filtering | Platform-level (`LabelRule.triggerEvents`) | Shared between work and engine for consistency |
| JEXL expression engine | Platform-level (`JexlExpressionEngine`) | Shared between repos. Uses `strict(false)` + `silent(false)` — errors surface as `ExpressionEvaluationException`. |
| JEXL error handling | Per-rule catch in `LabelRuleEngine` | Platform `JexlExpressionEngine` throws `ExpressionEvaluationException` on evaluation errors (unlike the current `silent(true)` which swallows them). `LabelRuleEngine` catches per-rule, logs the error with rule name and expression, and continues evaluating remaining rules. Better observability than silent swallowing; one bad rule never kills the evaluation loop. |
| Context map convention | Flat map, enum constants preserved | `WorkItemContextBuilder.toMap()` produces a flat map with all WorkItem fields. Enum fields (status, priority) are preserved as enum constants — not converted to `.name()` strings. Expressions use `status.name() == 'OPEN'` for string comparison. No `workItem.` prefix wrapping. |
| Engine location | `runtime/filter/` | Label evaluation is a runtime concern, not a queues concern |
| Persistence | Work-local `LabelRuleEntity` | Platform's `LabelRule` is an in-memory compiled rule, not serializable. Persistence is a consumer concern. |
| LabelAction serialization | Manual JSON in `LabelRuleEntity` | `LabelAction` is a sealed interface — Jackson requires `@JsonTypeInfo`/`@JsonSubTypes` for polymorphic deser. Rather than annotating the platform type, `LabelRuleEntity` handles serialization locally: `serializeActions()` writes `[{"type":"Add","label":"x"}]` and `parseActions()` switches on `type` to construct `LabelAction.Add`/`Remove`. Same pattern as current `WorkItemFilter.parseActions()`. |

## Delete — replaced entirely

| Current type | Module | Replacement |
|---|---|---|
| `FilterEngine` interface | queues | `LabelRuleEngine` |
| `FilterEngineImpl` | queues | `LabelRuleEngine` |
| `FilterEvaluatorRegistry` | queues | Platform `ExpressionEngineRegistry` |
| `WorkItemExpressionEvaluator` SPI | queues | Platform `CompiledExpression` |
| `ExpressionDescriptor` | queues | Language embedded in `LabelRuleEntity.conditionLanguage` |
| `FilterAction` record (`queues/model`) | queues | Platform `LabelAction` |
| `FilterChain` entity | queues | Dropped (single-pass, re-evaluate on delete) |
| `FilterChainStore` + `JpaFilterChainStore` | queues | Dropped |
| `WorkItemFilterBean` interface | queues | CDI-produced `LabelRule` |
| `LambdaFilterRegistry` | queues | CDI discovery via `Instance<LabelRule>` |
| `JexlConditionEvaluator` (queues) | queues | Platform `JexlExpressionEngine` |
| `JqConditionEvaluator` | queues | Platform `JQExpressionEngine` |
| `WorkItemFilter` entity | queues | `LabelRuleEntity` |
| `WorkItemFilterStore` + `JpaWorkItemFilterStore` | queues | `LabelRuleStore` + `JpaLabelRuleStore` |
| `FilterRegistryEngine` | runtime | `LabelRuleEngine` |
| `FilterDefinition` record | runtime | Platform `LabelRule` |
| `PermanentFilterRegistry` | runtime | CDI discovery via `Instance<LabelRule>` |
| `DynamicFilterRegistry` | runtime | `LabelRuleStore` loads directly |
| `JexlConditionEvaluator` (runtime) | runtime | Platform `JexlExpressionEngine` |
| `FilterRule` entity | runtime | `LabelRuleEntity` |
| `FilterRuleStore` + `JpaFilterRuleStore` | runtime | `LabelRuleStore` + `JpaLabelRuleStore` |
| `MongoFilterRuleStore` | persistence-mongodb | `MongoLabelRuleStore` |
| `InMemoryFilterRuleStore` | persistence-memory | `InMemoryLabelRuleStore` |
| `FilterEvent` enum | runtime | `String` trigger events on `LabelRule` |
| `ActionDescriptor` record | runtime | Platform `LabelAction` |
| `FilterAction` SPI interface (`runtime/filter`) | runtime | Deleted (no production callers for non-label actions; see §Non-label actions) |
| `ApplyLabelAction` | runtime | Label application is inside `LabelRuleEngine` |
| `OverrideCandidateGroupsAction` | runtime | Deleted (no production callers; see §Non-label actions) |
| `SetPriorityAction` | runtime | Deleted (no production callers; see §Non-label actions) |
| `FilterResource` | queues | `LabelRuleResource` (in `rest` module) |
| `FilterRuleResource` | rest | `LabelRuleResource` (consolidated) |

## Create — new types

### runtime/filter/

| Type | Purpose |
|---|---|
| `LabelRuleEngine` | Unified label evaluation engine. Collects rules from CDI (`Instance<LabelRule>`) and DB (`LabelRuleStore` → compiled to `LabelRule`). Strips INFERRED labels, iterates rules manually (not `LabelRule.evaluate()` batch API — see §Evaluation flow), applies resulting Add/Remove to WorkItem labels. `LabelAction.Add` creates labels with `LabelPersistence.INFERRED` and `appliedBy` = rule name. `LabelAction.Remove` only removes `INFERRED` labels (protects manually applied labels). ThreadLocal reentrancy guard. Per-rule `ExpressionEvaluationException` catch — logs error with rule name, continues evaluating remaining rules. |
| `LabelRuleEntity` | JPA entity (`label_rule` table). Stores name, conditionLanguage, conditionExpression, actionsJson, triggerEvents, scope (`Path` with `PathAttributeConverter`), enabled, tenancyId, createdAt. Compiled to `LabelRule` at load time via `ExpressionEngineRegistry.compile()`. Manual `serializeActions()`/`parseActions()` for `LabelAction` sealed interface JSON. |
| `LabelRuleStore` | Store SPI for `LabelRuleEntity`. Tenant-scoped CRUD. Same CDI priority ladder as `FilterRuleStore`: JPA (Tier 1), MongoDB (Tier 2), InMemory (Tier 3). |
| `JpaLabelRuleStore` | JPA implementation of `LabelRuleStore`. `@ApplicationScoped` (Tier 1 default). |

### persistence-mongodb/

| Type | Purpose |
|---|---|
| `MongoLabelRuleStore` | MongoDB implementation of `LabelRuleStore`. `@Alternative @Priority(1)` (Tier 2). Replaces `MongoFilterRuleStore`. |

### persistence-memory/

| Type | Purpose |
|---|---|
| `InMemoryLabelRuleStore` | In-memory implementation of `LabelRuleStore`. `@Alternative @Priority(100)` (Tier 3). Replaces `InMemoryFilterRuleStore`. |

### Reuse (unchanged)

| Type | Change |
|---|---|
| `WorkItemContextBuilder` | **Keep** in `runtime/event/`. Already exists as a 90-line class converting all 40+ WorkItem fields to a flat `Map<String, Object>`. Preserves enum constants (status, priority as enum values, not `.name()` strings). Used by both `WorkItemLifecycleEvent.context()` and `LabelRuleEngine` — a shared utility. Stays in `runtime/event/` because events are more fundamental than filters; filters importing from `runtime/event/` is a correct dependency direction. No code changes or import updates needed. |

### queues/

| Type | Change |
|---|---|
| `FilterEvaluationObserver` | Refactored: maps `WorkEventType` → event string, builds context via `WorkItemContextBuilder.toMap(workItem)`, calls `LabelRuleEngine.evaluate()`, then `SubjectViewOrchestrator.evaluateAndTrack()`. Fires `WorkItemQueueEvent` for each view event (preserving current behavior). ~20 lines. |

### rest/

| Type | Purpose |
|---|---|
| `LabelRuleResource` | Consolidated REST API at `/label-rules`. CRUD for persisted rules, enable/disable for permanent rules, ad-hoc evaluation. Replaces both `FilterResource` and `FilterRuleResource`. |

### Migrated producers

| Current | New | Notes |
|---|---|---|
| `LowConfidenceFilterProducer` (`@Produces FilterDefinition`) | `@Produces LabelRule` with `triggerEvents = Set.of("ADD")` and `LabelAction.Add("ai/low-confidence")` | `conditionContext Map.of("threshold", threshold)` → `ExpressionEngineRegistry.compile("jexl", expression, Map.class, Boolean.class, Map.of("threshold", threshold))`. Bound variables merged at eval time — semantically equivalent. Expression updated: `confidenceScore != null && confidenceScore < threshold` (flat map, no `workItem.` prefix). |
| `SecurityWritersFilter` (`implements WorkItemFilterBean`) — **in `queues-examples`** | `@Produces LabelRule` with `LambdaExpression` condition and `LabelAction.Add("review/urgent")` | Uses platform `LambdaExpression<Map<String,Object>, Boolean>` wrapping the matching lambda. |
| `SecurityWritersFilter` (`implements WorkItemFilterBean`) — **in `queues-dashboard`** | `@Produces LabelRule` with `LambdaExpression` condition and `LabelAction.Add("review/urgent")` | Identical duplicate — both copies migrated. Consider deduplicating into a shared module post-migration. |

## Evaluation flow

```
WorkItemLifecycleEvent
  → FilterEvaluationObserver (queues, CDI observer)
    → maps WorkEventType → event string ("ADD", "UPDATE", "REMOVE")
    → builds context via WorkItemContextBuilder.toMap(workItem)
        Context is a flat Map<String, Object> with all WorkItem fields.
        Enum fields (status, priority) are enum constants, not strings.
        No "workItem." prefix wrapping — fields are top-level keys.
    → calls LabelRuleEngine.evaluate(workItem, context, event)
      1. Strip all INFERRED labels from workItem
      2. Collect rules:
         - Instance<LabelRule> (CDI-produced, including LambdaExpression-based)
         - LabelRuleStore.findEnabled() → compile each to LabelRule
           via ExpressionEngineRegistry.compile(language, expression, Map.class, Boolean.class, variables)
      3. Iterate rules manually (NOT LabelRule.evaluate() batch API):
         for each rule:
           a. Skip if rule.triggerEvents is non-empty and doesn't contain event
           b. try { rule.condition().eval(context) } catch ExpressionEvaluationException:
              - Log error with rule name + expression, continue to next rule
              - One bad rule never prevents other rules from evaluating
           c. If condition is true, collect rule.actions()
         Note: LabelRule.evaluate() is a bare stream pipeline — if any
         condition().eval() throws, the entire batch fails. Manual iteration
         gives per-rule error isolation.
      4. Apply collected LabelActions to workItem.labels:
         - LabelAction.Add(label) → new WorkItemLabel(label, INFERRED, ruleName)
           Labels added by rules MUST have LabelPersistence.INFERRED so they
           are stripped on the next evaluation cycle (step 1). appliedBy is
           set to the rule name for traceability.
         - LabelAction.Remove(label) → remove only if persistence == INFERRED
           Protects manually applied (MANUAL) labels from rule-based removal.
           A rule can only remove what rules created.
         - Deduplication: skip Add if label already present with INFERRED
           persistence (same pattern as current ApplyLabelAction line 49-51)
      5. Persist workItem
    → SubjectViewOrchestrator.evaluateAndTrack() (unchanged)
    → Fire WorkItemQueueEvent for each view event (preserved from current behavior)
```

## Non-label actions

`OVERRIDE_CANDIDATE_GROUPS` and `SET_PRIORITY` are `FilterAction` SPI implementations with **no production callers**. Evidence:
- `LowConfidenceFilterProducer` uses `APPLY_LABEL` only
- `SecurityWritersFilter` uses `FilterAction.applyLabel()` only
- No other CDI-produced `FilterDefinition` exists in production code
- Pre-release: no persisted `FilterRule` or `WorkItemFilter` rows with non-label `actionsJson`

**Decision:** Delete the `FilterAction` SPI, `ApplyLabelAction`, `OverrideCandidateGroupsAction`, and `SetPriorityAction`. Label application moves inside `LabelRuleEngine`. The SPI was a speculative extension point with zero adoption.

**Deferred:** If non-label side-effects are needed in future (e.g., "when label `priority/urgent` is applied, also set `workItem.priority = URGENT`"), the label-triggered CDI observer pattern is the right architecture: `LabelRuleEngine` fires a `LabelChangeEvent` after applying labels, and independent CDI observers react. This is tracked as follow-up issue casehubio/work#316 and not part of this migration scope.

## REST API

| Endpoint | Purpose |
|---|---|
| `GET /label-rules` | List all rules (persisted + permanent CDI). `source` field distinguishes. |
| `POST /label-rules` | Create persisted rule. Validates expression via `ExpressionEngineRegistry.validate()`. |
| `PUT /label-rules/{id}` | Update persisted rule. 404 if permanent. |
| `DELETE /label-rules/{id}` | Delete persisted rule. Re-evaluates all enabled tenant WorkItems (full re-evaluation — no inverse index). Rule deletion is a rare administrative operation; O(all-tenant-items) cost is acceptable. |
| `PUT /label-rules/{name}/enabled` | Toggle enabled. Works for both persisted and permanent (in-memory override). |
| `POST /label-rules/evaluate` | Ad-hoc expression evaluation against a test WorkItem. |

## Database migration

**V5004** — pre-release, no data migration needed:

```sql
DROP TABLE IF EXISTS filter_chain_work_item;
DROP TABLE IF EXISTS filter_chain;
DROP TABLE IF EXISTS work_item_filter;
DROP TABLE IF EXISTS filter_rule;

CREATE TABLE label_rule (
    id                   UUID PRIMARY KEY,
    tenancy_id           VARCHAR(255) NOT NULL,
    name                 VARCHAR(255) NOT NULL,
    description          VARCHAR(500),
    condition_language   VARCHAR(20)  NOT NULL,
    condition_expression TEXT,
    actions_json         TEXT         NOT NULL DEFAULT '[]',
    trigger_events       VARCHAR(100) DEFAULT '',
    scope                VARCHAR(500),
    enabled              BOOLEAN      DEFAULT true,
    created_at           TIMESTAMP    NOT NULL
);
```

`LabelRuleEntity.scope` is typed as `Path` with `@Convert(converter = PathAttributeConverter.class)` — the same converter used by `WorkItemFilter.scope`. The DB column remains `VARCHAR(500)`; the JPA converter handles `Path.value()` ↔ `Path.parse()` mapping.

All four dropped tables share the same Flyway schema (`db/work/migration/`). Quarkus Flyway collects migrations from all modules on the classpath into a single migration history — there is no per-module migration history. Cross-module drops in V5004 are safe.

Actions JSON format: `[{"type":"Add","label":"x"}, {"type":"Remove","label":"y"}]`. `LabelRuleEntity` handles serialization locally via `serializeActions(List<LabelAction>)` and `parseActions(String json)` — manual JSON matching on the `type` discriminator to construct `LabelAction.Add`/`Remove`. No Jackson annotations needed on the platform sealed interface.

`conditionLanguage` values: `"jexl"`, `"jq"`. CDI-produced `LabelRule` instances use `LambdaExpression` (type `"lambda"`) directly and are never persisted — no `conditionLanguage = 'lambda'` rows exist in the `label_rule` table. The current `WorkItemFilter.findActive()` guard (`conditionLanguage != 'lambda'`) is therefore unnecessary on `LabelRuleStore.findEnabled()`.

## Maven dependencies

Add to `runtime/pom.xml`:
- `casehub-platform-expression` (compile) — `ExpressionEngineRegistry`, `JexlExpressionEngine`

Remove from `queues/pom.xml`:
- `commons-jexl3` (now transitive via platform-expression)

Remove from `runtime/pom.xml`:
- `commons-jexl3` (now transitive via platform-expression)

## Protocol compliance

- **queue-filter-scope-management-only** — `scope` on `LabelRuleEntity` remains management metadata (typed as `Path`), not an execution predicate. `LabelRuleStore.findEnabled()` returns all enabled rules regardless of scope. Scope filtering belongs above the store layer.
- **store-tenancy-stamping-on-insert** — `JpaLabelRuleStore.put()` stamps tenancyId from `CurrentPrincipal` on insert.
- **persistence-backend-cdi-priority** — `LabelRuleStore` follows the same three-tier CDI priority ladder as `FilterRuleStore`: JPA (Tier 1, `@ApplicationScoped`), MongoDB (Tier 2, `@Alternative @Priority(1)`), InMemory (Tier 3, `@Alternative @Priority(100)`).

## Risks

- **Platform SNAPSHOT dependency** — platform#189 and platform#191 are on main but not released. Work already depends on platform SNAPSHOT, so this is the existing pattern.
- **Expression convention change** — CDI producers must update expressions from `workItem.fieldName` to flat `fieldName`. This is a code-level change (not data migration) and is part of the migration task.
