# Progress Model Enhancements — Schema Shapes, Visualisation Modes, Rollback Control

**Date:** 2026-08-04
**Status:** Draft
**Epic:** casehubio/work#329
**Covers:** #307 (custom shape), #309 (visualisation modes), #308 (rollback control)
**Parent spec:** `specs/issue-237-structured-progress/2026-08-01-progress-model-design.md`

## Context

The progress subsystem (shipped via #237) provides ProgressInstance, typed union shapes (percentage, count, step), rollup, events, SSE, and REST. Three enhancements were deferred from the original spec. This design extends the existing foundation without changing its core model.

**Scope change from original spec:** Multi-instance coordinated rollback (tree-wide undo, saga coordination) is moved to epic #92 (Distributed WorkItems — clustering + federation). This spec covers single-instance rollback controls only.

---

## 1. Custom JSON Schema Shape Type (#307)

### Summary

A new `shapeType = "custom"` that accepts an arbitrary JSON Schema in the `definition` field and validates `state` against it. Demonstrates platform extensibility — consumers can define domain-specific progress shapes without waiting for built-in support.

### API Changes (progress-api)

**No changes to ProgressInstance or ProgressCreateRequest.** The existing `shapeType` (String) and `definition` (JsonNode) fields already support this. `shapeType = "custom"` uses `definition` to carry the schema and rollback configuration.

**New SPI — `CustomRollbackDetector`:**

```java
public interface CustomRollbackDetector extends NamedStrategy {
    boolean isRollback(JsonNode previousState, JsonNode currentState, JsonNode definition);
}
```

Extends `NamedStrategy` for resolution via the existing `StrategyResolver`. The `definition` is passed so detectors can read shape-specific configuration.

### Definition Structure

The `definition` field for `shapeType = "custom"` carries:

```json
{
  "schema": {
    "type": "object",
    "properties": {
      "score": {"type": "number", "minimum": 0, "maximum": 100},
      "label": {"type": "string"}
    },
    "required": ["score"]
  },
  "rollbackField": "score"
}
```

| Key | Required | Description |
|-----|----------|-------------|
| `schema` | yes | JSON Schema object — state is validated against this on create and every update |
| `rollbackField` | no | Names a numeric field in state for regression detection (value decreased = rollback) |
| `rollbackDetectorId` | no | Resolves a pluggable `CustomRollbackDetector` via `StrategyResolver` |

`rollbackField` and `rollbackDetectorId` are mutually exclusive — HTTP 400 if both set.

### Core Changes (progress-core)

**New: `CustomShapeValidator`** — implements `ShapeValidator` with `shapeType() = "custom"`.

Creation validation:
- `definition` must contain a `schema` key with a valid JSON Schema object
- `rollbackField` validation: must be a top-level field name (no dot-notation nesting), must exist in the `schema.properties`, must be declared as `"type": "number"` or `"type": "integer"` in the schema. This catches misconfiguration at creation time rather than silently failing at runtime.
- `rollbackDetectorId` validation: must be a non-empty string
- `rollbackField` and `rollbackDetectorId` are mutually exclusive (400 if both set)
- `state` validated against the embedded JSON Schema
- Schema complexity limit: maximum nesting depth of 10 levels, maximum schema size of 64KB (serialized). These limits prevent pathological schemas from causing unbounded validation time on the state update hot path.

State update validation:
- `state` validated against the stored JSON Schema from `definition`

**Dependency:** `com.networknt:json-schema-validator` added to `progress-core/pom.xml`. Pure Java, no framework dependencies.

**RollbackDetector signature change:**

```java
// Before
boolean isRollback(String shapeType, JsonNode previousState, JsonNode currentState)

// After
boolean isRollback(String shapeType, JsonNode previousState, JsonNode currentState, JsonNode definition)
```

All callers updated. Existing built-in shape cases ignore `definition`.

**Three-tier custom rollback detection:**

```
case "custom" -> isCustomRollback(previousState, currentState, definition)
```

1. If `definition` has `rollbackDetectorId` → resolve `CustomRollbackDetector` via `StrategyResolver`, delegate
2. If `definition` has `rollbackField` → extract that top-level numeric field from both states via `state.get(rollbackField)`. If either field is missing or not numeric, return `false` (no rollback detected — missing data is not regression). Compare with strict `<` (equal values are not rollbacks).
3. Otherwise → return `false` (no rollback detection for custom shapes)

**Interaction with rollbackPolicy:** When `rollbackPolicy = "denied"` is set on a custom shape with no `rollbackField` or `rollbackDetectorId`, the policy is silently ineffective — `RollbackDetector` never flags a rollback, so the policy check never fires. This is documented behaviour, not a bug. Consumers who want rollback protection on custom shapes must configure rollback detection.

`RollbackDetector` receives a `Function<String, CustomRollbackDetector>` resolver parameter in its constructor. In `progress-runtime`, the Quarkus CDI layer provides this via `StrategyResolver::resolve`. In pure-Java tests, a lambda or test double is passed directly. This keeps `progress-core` free of CDI/framework dependencies. For built-in shapes the resolver is never called.

### Testing

| What to prove | Approach |
|---|---|
| Custom shape validates state against schema | Valid state passes, invalid state rejected (missing required field, wrong type, out of range) |
| Definition requires `schema` key | Creation without `schema` → 400 |
| `rollbackField` and `rollbackDetectorId` mutual exclusion | Both set → 400 |
| Rollback detection with `rollbackField` | Numeric decrease detected, increase not flagged |
| Rollback detection with `rollbackDetectorId` | Custom detector called, result honoured |
| No rollback detection without hints | State change always emits `STATE_UPDATED`, never `ROLLED_BACK` |
| Schema validation on update | Updated state that violates schema → rejected |

---

## 2. Visualisation Modes (#309)

### Summary

A `visualisationMode` field on ProgressInstance — a creation-time hint telling consumers how to render the progress data. Open vocabulary with platform-defined constants.

### API Changes (progress-api)

**ProgressInstance** — add field:

```java
public record ProgressInstance(
    UUID id,
    String tenancyId,
    String scopeType,
    String scopeId,
    UUID parentProgressId,
    UUID rootProgressId,
    String shapeType,
    JsonNode definition,
    JsonNode state,
    ProgressStatus status,
    String rollupStrategyId,
    String rollbackPolicy,       // §3
    String visualisationMode,    // this section
    Instant createdAt,
    Instant updatedAt
)
```

**ProgressCreateRequest** — add optional `visualisationMode` field.

**Platform constants:**

```java
public final class VisualisationModes {
    public static final String GAUGE = "gauge";
    public static final String PROGRESS_BAR = "progress-bar";
    public static final String STEP_LIST = "step-list";
    public static final String TIMELINE = "timeline";
    public static final String TREE_MAP = "tree-map";
    public static final String COUNT_BADGE = "count-badge";

    private VisualisationModes() {}
}
```

Not an enum — consumers can use any string. Platform constants are conventions, not enforcement.

**Default mapping convention** (documented, not enforced in code):

| shapeType | Default visualisationMode |
|-----------|--------------------------|
| `percentage` | `gauge` |
| `count` | `progress-bar` |
| `step` | `step-list` |
| `custom` | none — must be explicit |

Consumers apply defaults when `visualisationMode` is null. The platform does not auto-populate.

### Runtime Changes (progress-runtime)

**ProgressInstanceEntity:**

```java
@Column(name = "visualisation_mode")
public String visualisationMode;
```

**ProgressInstanceMapper** — map new field both directions.

**ProgressService.create()** — thread `visualisationMode` from request to instance. No validation.

### REST Changes (progress-rest)

**CreateProgressRequest** — add optional `visualisationMode` field. Threaded to `ProgressCreateRequest`.

### SSE / Events

No changes. `visualisationMode` is a creation-time hint — consumers read it from the instance when they connect, not from per-event data.

### Testing

| What to prove | Approach |
|---|---|
| Created with visualisationMode, returned on query | Round-trip via service |
| Null visualisationMode accepted | Create without it, verify null in response |
| Arbitrary string accepted | Create with `"gantt"` (not a platform constant), verify stored |
| Persisted across updates | Update state, verify visualisationMode unchanged |

---

## 3. Lean Single-Instance Rollback Controls (#308)

### Summary

Three capabilities: a rollback policy field that denies accidental backward movement, a convenience rollback endpoint that restores previous state from the event trail, and a snapshot query that projects the state history.

### API Changes (progress-api)

**ProgressInstance** — add field:

```java
String rollbackPolicy,  // nullable — null and "allowed" are equivalent
```

Position: after `rollupStrategyId`, before `visualisationMode`.

**Policy constants:**

```java
public final class RollbackPolicies {
    public static final String ALLOWED = "allowed";
    public static final String DENIED = "denied";

    private RollbackPolicies() {}
}
```

**ProgressCreateRequest** — add optional `rollbackPolicy` field.

**New record — state history projection:**

```java
public record ProgressSnapshot(
    UUID eventId,
    JsonNode state,             // currentState from the event — the state AT that point in time
    ProgressStatus status,
    ProgressChangeType changeType,
    Instant timestamp
)
```

`eventId` is included so consumers can pass it to `POST /progress/{id}/rollback?toEvent={eventId}`.

### Event Identity (prerequisite for rollback-to-event and snapshots)

The existing `ProgressUpdatedEvent` record has no `id` field, and the `ProgressEventStore` SPI has no `findById()` method. The rollback-to-event feature and snapshot projection both require event identity. Changes:

1. **Add `UUID id` to `ProgressUpdatedEvent`** — generated in `ProgressService.emitEvent()` before passing to stores and the CDI event bus.

2. **Add `Optional<ProgressUpdatedEvent> findById(UUID eventId)` to `ProgressEventStore`** — required for `rollbackToEvent()`.

3. **Fix `JpaProgressEventStore.toDomain()`** — currently discards the entity's `@Id` field. Must map `entity.id` to the new `ProgressUpdatedEvent.id` field.

4. **Update `InMemoryProgressEventStore`** — index events by ID for `findById()` lookup.

This is a breaking change to `ProgressUpdatedEvent` (record gains a field). All callers constructing the event must be updated — primarily `ProgressService.emitEvent()` and test code.

### Runtime Changes (progress-runtime)

**ProgressInstanceEntity:**

```java
@Column(name = "rollback_policy")
public String rollbackPolicy;
```

**ProgressService changes:**

1. **Policy enforcement in `updateState()`:** After `RollbackDetector` flags a rollback, check `instance.rollbackPolicy()`. If `"denied"` (case-insensitive), throw `IllegalStateException("Rollback denied by policy")`.

2. **New: `rollback(UUID id)`** — finds the rollback target by scanning events in reverse chronological order, skipping events with `changeType` in {`COMPLETED`, `FAILED`, `REACTIVATED`, `CHILD_ATTACHED`, `ROLLED_BACK`}. Takes the first event with `changeType` in {`STATE_UPDATED`, `CREATED`} and applies its `previousState` (for `STATE_UPDATED`) or throws if only `CREATED` remains (no previous state). Applies via an internal method that bypasses the rollback policy check.

   **Oscillation prevention:** `ROLLED_BACK` events are skipped when scanning for the rollback target. This means consecutive `POST /rollback` calls go further back in history rather than undoing each other. Example: state goes S0→S1→S2, first rollback restores S1 (skipping the ROLLED_BACK event), second rollback restores S0 (skipping both ROLLED_BACK events). This converges to the earliest state rather than oscillating.

3. **New: `rollbackToEvent(UUID id, UUID eventId)`** — reads the specific event via `ProgressEventStore.findById(eventId)`, verifies `event.progressId == id`, takes its `currentState` (the state *at* that point in time), applies via the internal bypass method. No oscillation concern — the target is explicitly chosen.

4. **New: `getSnapshots(UUID id)`** — queries `ProgressEventStore.findByProgressId()`, projects each to `ProgressSnapshot` (using `currentState` as the `state` field), returns ordered by timestamp ascending. Supports optional `limit` query parameter (default: 100, max: 1000) to prevent unbounded result sets on long-running instances.

**Internal structure — policy bypass:**

Both `rollback()` and `rollbackToEvent()` are deliberate undo actions. The rollback policy protects against *accidental* backward movement via `PUT /state`. Explicit rollback endpoints bypass the policy.

Implementation: extract two internal methods rather than a boolean flag. `updateStateChecked(instance, newState)` validates policy and auto-detects changeType (used by `updateState()`). `applyRollbackState(instance, newState)` bypasses policy and forces `changeType = ROLLED_BACK` (used by `rollback()` and `rollbackToEvent()`). Two methods with clear names are better than one method with a boolean flag that does double duty.

**Explicit rollback always emits `ROLLED_BACK`** regardless of `RollbackDetector` output. The auto-detection principle ("auto-detected, not caller-specified") applies to `updateState()`. The explicit rollback endpoint is a deliberate undo action — the changeType should reflect intent, not direction. A rollback that happens to restore a numerically higher state (e.g., 60→80 after 80→60→rollback) still emits `ROLLED_BACK` because it is semantically an undo.

### REST Changes (progress-rest)

**New endpoints:**

```
POST   /progress/{id}/rollback              — undo last state change
POST   /progress/{id}/rollback?toEvent={id} — restore to state at specific event
GET    /progress/{id}/snapshots             — state history projection
```

`POST /rollback` returns the updated `ProgressInstance`. `GET /snapshots` returns `List<ProgressSnapshot>` with optional `?limit=N` (default 100, max 1000) and `?since=<instant>` parameters.

**CreateProgressRequest** — add optional `rollbackPolicy` field.

### Event Emission

Rollback via the explicit endpoint always emits `ROLLED_BACK` regardless of whether the state change is numerically forward or backward. This is a deliberate override of auto-detection — the explicit rollback endpoint signals intent ("this is an undo"), not direction. No new `ProgressChangeType` values needed.

### Interaction: rollbackPolicy and reactivate

Independent concerns. `rollbackPolicy = "denied"` blocks state regression (the `state` JsonNode moving backward). `reactivate()` changes `status` (COMPLETED/FAILED → ACTIVE) without modifying `state`. An instance can deny state regression but allow reactivation for retry scenarios.

### Testing

| What to prove | Approach |
|---|---|
| Policy `"denied"` blocks backward state update | Update percentage 80 → 60 → rejected with 409 |
| Policy `"denied"` allows forward state update | Update percentage 60 → 80 → accepted |
| Policy null/`"allowed"` allows backward update | Update percentage 80 → 60 → accepted, `ROLLED_BACK` event |
| Explicit rollback bypasses `"denied"` policy | Set policy denied, update forward twice, POST `/rollback` → succeeds |
| Rollback restores previous state | Update 3 times, POST `/rollback` → state equals second update's state |
| Rollback to specific event | Update 3 times, POST `/rollback?toEvent=firstEventId` → state equals first update's state |
| Rollback with no previous state | Create instance, POST `/rollback` → error (only CREATED event) |
| Snapshots returns ordered history | Update 3 times, GET `/snapshots` → 4 entries (CREATED + 3 updates) ordered by timestamp |
| Reactivation independent of rollback policy | Set policy denied, complete instance, reactivate → succeeds |
| Consecutive rollbacks go further back | S0→S1→S2, rollback→S1, rollback→S0 (no oscillation) |
| Rollback skips non-state events | Update, complete, rollback → restores pre-update state (skips COMPLETED event) |
| Explicit rollback emits ROLLED_BACK even when forward | 50→80→60, rollback restores 80, changeType is ROLLED_BACK not STATE_UPDATED |
| Snapshots with limit | Create 200 events, GET `/snapshots?limit=50` → exactly 50 entries |

---

## 4. Cross-Cutting Concerns

### Flyway Migration

Single migration for the new columns:

```sql
-- V6001__progress_enhancements.sql
ALTER TABLE progress_instance ADD COLUMN visualisation_mode VARCHAR(50);
ALTER TABLE progress_instance ADD COLUMN rollback_policy VARCHAR(20);
```

No migration for custom shape — uses existing `shape_type` and `definition` columns.

### Record Constructor Impact

`ProgressInstance` gains `rollbackPolicy` and `visualisationMode`. `ProgressCreateRequest` gains both fields plus threading through `attachChild()`. `ProgressUpdatedEvent` gains `UUID id`. Every call site constructing these records must be updated:

- `ProgressService` — `create()`, `withState()`, `emitEvent()`, `attachChild()`
- `ProgressInstanceMapper` — entity ↔ record
- `InMemoryProgressInstanceStore` — if it constructs records
- `InMemoryProgressEventStore` — index by event ID
- `JpaProgressEventStore.toDomain()` — preserve entity ID
- REST request mappers in `progress-rest`
- All test code constructing `ProgressInstance`, `ProgressCreateRequest`, or `ProgressUpdatedEvent` directly

Pre-release project — breaking constructor changes are correct.

### Dependencies

| Module | New dependency |
|--------|---------------|
| `progress-core` | `com.networknt:json-schema-validator` |

No new dependencies for #309 or #308.

### New GitHub Issue

Before implementation: create "Multi-instance coordinated rollback" under epic #92 (Distributed WorkItems), referencing #308 as the single-instance foundation.

### Documentation Updates

| Document | Update |
|----------|--------|
| `ARC42STORIES.MD` | New chapter for progress enhancements |
| `docs/api-reference.md` | New endpoints, new fields |
| Consumer guide | Default visualisation mode mapping, rollback policy semantics |
| Parent spec (2026-08-01) | Mark #307, #309, #308 as implemented; add new deferred item for multi-instance rollback |

### Scope Boundaries

**In scope:** custom shape with JSON Schema validation, three-tier rollback detection, visualisation mode field, rollback policy, convenience rollback endpoint, snapshot query.

**Out of scope:** schema registry (URI-referenced schemas), tree-level visualisation config, multi-instance coordinated rollback (→ #92), rollback policy on step-level state changes (policy governs instance-level state only).
