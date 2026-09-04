# Design: Replace FilterScope with Path-based scope hierarchy

**Issue:** casehubio/work#263  
**Branch:** issue-263-filterscope-to-path  
**Date:** 2026-06-16

## Problem

`FilterScope` (PERSONAL/TEAM/ORG) is a hardcoded parallel scope type in the queues module,
violating the platform boundary rule: "Do not define parallel path, scope, preference, or
principal types." `casehub-platform-api` owns `Path` as the canonical scope type.
`#236` eliminated `VocabularyScope` from the runtime module using the same fix.

## Decision

Delete `FilterScope`. Replace every usage with `Path` from `casehub-platform-api`.
This is identical in pattern to #236 (`VocabularyScope → Path`), applied to the queues module.

## Data Model

### `WorkItemFilter` and `QueueView` (JPA entities)

- `scope: FilterScope` → `scope: Path` with `@Convert(converter = PathAttributeConverter.class)`
- Column widened from `VARCHAR(20)` to `VARCHAR(500)`.
- `Path.root()` stores as `""` (empty string). `PathAttributeConverter` handles the roundtrip
  (`""` → `Path.root()`). The column is `NOT NULL`; empty string satisfies that constraint.
- `ownerId` field — **dropped**. Path encodes ownership: a personal filter for actor `alice`
  lives at `Path.of("alice")`, an org-wide filter at `Path.root()`. `ownerId` is not queried
  in any store SPI and is redundant once path carries the hierarchy.
- `PathAttributeConverter` is in `runtime/`, which queues already depends on — no module
  changes needed.
- **Scope is stored metadata with no current enforcement**: `findActive()`, `scanAll()`, and queue
  membership resolution all ignore it. Its intended future role is management visibility (who can
  list/create/delete a filter or queue view), not evaluation (which WorkItems a filter applies to).
  A future implementor adding a scope predicate to `findActive()` would be introducing a new
  execution semantic, not completing deferred enforcement.

### `WorkItemFilterBean` (SPI interface)

`scope()` is **deleted** from the interface. The method was never called: `FilterEngineImpl`
only invokes `bean.matches(workItem)` and `bean.actions()`; `LambdaFilterRegistry.all()` returns
all beans with no scope filtering. More fundamentally, `scope()` is architecturally incoherent on
a CDI lambda bean: `matches(WorkItem)` IS the scope discriminator — a filter that applies only to
certain WorkItems encodes that condition in its match logic. A separate `scope()` declaration has
no enforcement path and no semantic grounding.

The interface becomes:

```java
public interface WorkItemFilterBean {
    boolean matches(WorkItem workItem);
    List<FilterAction> actions();
}
```

### `FilterScope` enum

Deleted entirely.

## REST API

### Request bodies

`CreateFilterRequest` and `CreateQueueRequest` replace `(FilterScope scope, String ownerId)`
with `String scope` (nullable path string).

### Parsing in create endpoints

Both `FilterResource.create()` and `QueueResource.create()` apply the VocabularyResource
pattern from #236 — three cases:

```java
final Path scopePath;
try {
    scopePath = (req.scope() == null || req.scope().isBlank())
            ? Path.root()
            : Path.parse(req.scope());
} catch (IllegalArgumentException e) {
    return Response.status(400)
            .entity(Map.of("error", "invalid scope: " + e.getMessage())).build();
}
```

- Null or blank → `Path.root()` (widest scope; equivalent to the old ORG default)
- Valid non-blank string → `Path.parse(req.scope())`
- Malformed string → 400 with error message

### Scope is immutable after creation (FilterResource only)

`FilterResource.update()` (PUT `/{id}`) reuses `CreateFilterRequest` but only acts on
`name`, `conditionExpression`, and `actions`. After the change, the record has a `scope` field
that the update path must explicitly ignore. Scope is a creation-time property — it defines the
namespace the filter was created under, matching the vocabulary precedent from #236 (vocabularies
are found-or-created by scope, not updated to a different scope). Silently discarding the scope
field in PUT is the correct behaviour; no separate request type is needed.

`QueueResource` has no PUT endpoint — this point is `FilterResource`-only.

### list() serialization

`FilterResource.list()` and `QueueResource.list()` currently put `f.scope` (an enum) directly
into `Map<String, Object>`. After the change `f.scope` is a `Path` record; Jackson would
serialize it as an object, not a string. Both must call `.value()` explicitly:

```java
"scope", f.scope.value()   // or q.scope.value()
```

This matches `VocabularyResource.listAll()` from #236.

## Schema

No Flyway migration: there are no installs. Modify `V2000__queues_schema.sql` directly:

- Both `work_item_filter` and `queue_view`: widen `scope VARCHAR(20)` → `scope VARCHAR(500)`
- Remove `owner_id VARCHAR(255)` column from both table definitions

## Call Site Inventory

All 58 references to `FilterScope`, across 4 modules:

### `queues/` module

| File | Change |
|------|--------|
| `model/WorkItemFilter.java` | `scope: FilterScope` → `scope: Path` + `@Convert`; drop `ownerId` |
| `model/QueueView.java` | same |
| `service/WorkItemFilterBean.java` | delete `FilterScope scope()` entirely |
| `api/FilterResource.java` | request: `(FilterScope scope, String ownerId)` → `String scope`; parse with try/catch; `f.scope.value()` in list |
| `api/QueueResource.java` | same |
| `repository/jpa/JpaWorkItemFilterStoreTenancyTest.java` | `f.scope = FilterScope.ORG` → `f.scope = Path.root()` |
| `repository/jpa/JpaQueueViewStoreTenancyTest.java` | `qv.scope = FilterScope.ORG` → `qv.scope = Path.root()` |
| `repository/jpa/JpaFilterChainStoreTenancyTest.java` | `f.scope = FilterScope.ORG` → `f.scope = Path.root()` |

### `queues-dashboard/` module

| File | Change |
|------|--------|
| `SecurityWritersFilter.java` | remove `scope()` override entirely (method deleted from interface) |
| `ReviewStepService.java` | `f.scope = FilterScope.ORG` → `f.scope = Path.root()` |

### `queues-examples/` module

| File | Usages | Change |
|------|--------|--------|
| `security/SecurityEscalationScenario.java` | 6 | all `FilterScope.ORG` → `Path.root()` |
| `triage/SupportTriageScenario.java` | 7 | all `FilterScope.ORG` → `Path.root()` |
| `finance/FinanceApprovalScenario.java` | 4 | all `FilterScope.ORG` → `Path.root()` |
| `legal/LegalRoutingScenario.java` | 4 | all `FilterScope.ORG` → `Path.root()` |
| `lifecycle/QueueLifecycleScenario.java` | 1 | `FilterScope.ORG` → `Path.root()` |
| `review/DocumentReviewScenario.java` | 4 | all `FilterScope.ORG` → `Path.root()` |
| `review/SecurityWritersFilter.java` | 2 | remove `scope()` override entirely |

### `examples/` module (outside queues tree)

| File | Usages | Change |
|------|--------|--------|
| `queues/QueueModuleScenario.java` | 1 | `FilterScope.TEAM` → `Path.of("team")` (illustrative; callers choose their own paths) |

## Platform Coherence

- Removes a parallel scope type — brings queues into alignment with the boundary rule
- No new types introduced; no new module deps
- `PathAttributeConverter` reused as-is from `runtime/`
- `WorkItemFilterBean` SPI is cleaner: two methods, both enforced by the engine

## Testing

- Existing tenancy tests updated: `FilterScope.ORG` → `Path.root()`
- `PathAttributeConverter` roundtrip already covered by `PathAttributeConverterTest` in runtime
- No new test types needed; the scope field change is mechanical
