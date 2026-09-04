# Design: Progress API Docs, WorkItemStore SPI Extraction, resolvedScope Fix

**Branch:** issue-333-progress-api-docs-spi-fix
**Covers:** #333, #337, #340
**Date:** 2026-08-13

---

## Issue Triage

| # | Title | Action | Rationale |
|---|-------|--------|-----------|
| #340 | HumanTaskScheduleHandler ignores resolvedScope | **Close** | Already fixed — `HumanTaskScheduleHandler` lines 132-135 and 235-243 use `event.resolvedScope()` / `event.resolvedTitle()` with fallback to `target.scope()` / `target.title()` |
| #333 | Add progress REST API to api-reference.md | **Document** | Mechanical — transcribe 18 ProgressResource endpoints into existing docs format |
| #337 | Extract WorkItemStore SPI + WorkItem POJO into casehub-work-api | **Implement** | Multi-module SPI extraction; detailed design below |

---

## #337 — WorkItemStore SPI Extraction

### Problem

`WorkItemStore` (the query SPI interface), `WorkItemEntity` (a JPA `@Entity`), and `WorkItemQuery` all live in the `casehub-work` runtime module alongside 60+ JPA-backed services. Consumers that only need to query WorkItems must depend on the full runtime, pulling JPA entities that require a `default` Hibernate persistence unit and datasource — even when the consumer only needs the query SPI.

`casehub-work-persistence-memory` depends on runtime for the store interface, transitively pulling JPA entities. Claudony (casehubio/claudony#200) cannot embed WorkItem querying without 80+ CDI deployment errors.

### Design Decisions

#### D1: WorkItem SPI boundary type — immutable record

All existing SPI boundary types in `api/` are immutable: `WorkItemRef` (record), `WorkItemSummary` (record), `WorkItemCreateRequest` (final class with builder), `WorkItemStatusEvent` (record). The progress module's `ProgressInstance` in `progress-api/` proves the immutable record pattern at scale (~14 fields).

The JPA entity stays mutable — `WorkItemService` continues to load and mutate `WorkItemEntity` internally. The SPI boundary returns immutable records via the mapper. The record includes a `toBuilder()` method for copy-and-modify patterns where consumers need to construct modified copies (e.g., `store.get(id).toBuilder().status(NEW).build()`). This is the `ProgressInstanceMapper.toDomain()` pattern.

#### D2: JPA entity relationship — composition with standalone mapper

Following the `ProgressInstanceMapper` precedent in `progress-runtime/repository/`: a standalone `WorkItemMapper` class with static `toDomain()`, `toEntity()`, and `updateEntity()` methods. The JPA entity owns its persistence concerns; the mapper isolates the field-mapping coupling.

#### D3: WorkItemRef coexistence

Both types coexist with distinct roles:
- `WorkItemRef` (11 fields) — lightweight projection for events, observers, and cross-module callbacks
- `WorkItemEntity` (46 fields) — full store SPI return type for query and persistence operations

#### D4: Field scoping

All domain fields cross the SPI boundary except `version` (leaks OCC semantics). JPA collection types get clean api/ representations:
- `List<WorkItemLabel>` (JPA `@Embeddable`) → `List<WorkItemLabel>` (record in api/)
- `Set<WorkItemType>` (JPA `@Embeddable` wrapping a path string) → `Set<String>` (the path directly)

**OCC mechanics with version excluded:** `version` stays on the entity only. `JpaWorkItemStore.put()` loads the managed entity by ID (L1 cache hit within the same transaction), copies fields from the domain record via `WorkItemMapper.updateEntity()`, and lets Hibernate check `@Version` on flush. The OCC guarantee is preserved — it's enforced at the entity layer, not the domain layer. `MongoWorkItemStore` uses the same pattern: load document (with its version), apply domain record fields, version-checked `replaceOne`.

### What Moves to api/

#### 1. `WorkItemEntity` — immutable record with builder

**Package:** `io.casehub.work.api`

**Fields** (46 — everything from the entity except `version`):

| Field | Type | Notes |
|-------|------|-------|
| `id` | `UUID` | |
| `tenancyId` | `String` | |
| `title` | `String` | |
| `description` | `String` | |
| `formKey` | `String` | |
| `status` | `WorkItemStatus` | Already in api/ |
| `priority` | `WorkItemPriority` | Already in api/ |
| `assigneeId` | `String` | |
| `owner` | `String` | |
| `candidateGroups` | `String` | |
| `candidateUsers` | `String` | |
| `requiredCapabilities` | `String` | |
| `createdBy` | `String` | |
| `delegationChain` | `String` | |
| `delegationDeclineTarget` | `DeclineTarget` | Already in api/ |
| `priorStatus` | `WorkItemStatus` | |
| `payload` | `String` | JSON TEXT |
| `resolution` | `String` | JSON TEXT |
| `claimDeadline` | `Instant` | |
| `expiresAt` | `Instant` | |
| `followUpDate` | `Instant` | |
| `createdAt` | `Instant` | |
| `updatedAt` | `Instant` | |
| `assignedAt` | `Instant` | |
| `startedAt` | `Instant` | |
| `completedAt` | `Instant` | |
| `suspendedAt` | `Instant` | |
| `accumulatedUnclaimedSeconds` | `long` | Claim SLA tracking |
| `lastReturnedToPoolAt` | `Instant` | |
| `labels` | `List<WorkItemLabel>` | New record in api/ |
| `types` | `Set<String>` | Path strings directly |
| `confidenceScore` | `Double` | Nullable |
| `callerRef` | `String` | |
| `parentId` | `UUID` | |
| `scope` | `String` | |
| `templateId` | `UUID` | |
| `templateVersion` | `Long` | |
| `permittedOutcomes` | `String` | JSON array |
| `excludedUsers` | `String` | |
| `outcome` | `String` | |
| `inputDataSchema` | `String` | JSON Schema |
| `outputDataSchema` | `String` | JSON Schema |
| `payloadTypeName` | `String` | |
| `resolutionTypeName` | `String` | |
| `candidateScores` | `String` | Opaque JSON |
| `routingExperiences` | `String` | Opaque JSON |

**Builder:** static inner `Builder` class following the `WorkItemCreateRequest` pattern. All fields settable, `build()` returns the immutable record. Includes `toBuilder()` instance method for copy-and-modify patterns — essential for consumers that need to construct modified copies without manually specifying all 46 fields.

#### 2. `WorkItemLabelEntity` — new record in api/

**Package:** `io.casehub.work.api`

```java
public record WorkItemLabel(String path, LabelPersistence persistence, String appliedBy) {}
```

`LabelPersistence` is already in api/. The JPA `@Embeddable` `WorkItemLabelEntity` in runtime/ is renamed to `WorkItemLabelEntity`.

#### 3. `WorkItemStore` — interface

**Package:** `io.casehub.work.api.spi`

All method signatures unchanged. References to `io.casehub.work.runtime.model.WorkItem` become `io.casehub.work.api.WorkItem`. Default method **bodies** change: six methods access entity public fields (`wi.callerRef`, `wi.status`, `wi.parentId`, `wi.createdAt`) which become record accessors (`wi.callerRef()`, `wi.status()`, etc.). The `summaryByQuery` default method's dependency on `WorkItemSummaryBuilder` is resolved by also moving `WorkItemSummaryBuilder` to api/ (see below).

`CrossTenantWorkItemStore` (sibling interface in `runtime/repository/`) also moves to `api/spi/`. Its `findActiveWithDeadlines()` return type changes from the entity to the api/ record. Both `JpaCrossTenantWorkItemStore` and `MongoCrossTenantWorkItemStore` implementations update accordingly.

#### 4. `WorkItemQuery` — value object

**Package:** `io.casehub.work.api`

Zero code changes — only depends on `WorkItemPriority` and `WorkItemStatus`, both already in api/. Pure package move.

#### 5. `WorkItemRootView` — record

**Package:** `io.casehub.work.api`

References the api/ `WorkItemEntity` record instead of the runtime entity. Otherwise unchanged.

#### 6. `LabelPatternMatcher` — utility extracted from LabelVocabularyService

**Package:** `io.casehub.work.api`

`LabelVocabularyService.matchesPattern()` is a static method in `runtime/service/` that `InMemoryWorkItemStore` depends on for label filtering. The pattern-matching logic (exact, `*`, `**` glob) has no JPA or CDI dependencies — it's pure string matching. Extract to a standalone utility in api/ so `persistence-memory/` can use it without a runtime/ dependency.

#### 7. `WorkItemSummaryBuilder` — utility

**Package:** `io.casehub.work.api`

Currently in `runtime/service/`. Only depends on `WorkItemSummary` (already in api/), `WorkItemEntity` (moving to api/), and `WorkItemStatus` (already in api/). Field access changes from entity public fields (`wi.status`) to record accessors (`wi.status()`). Otherwise a pure move.

### What Stays in runtime/

#### `WorkItemEntity` (renamed from `WorkItemEntity`)

The JPA entity keeps all annotations, `PanacheEntityBase` inheritance, `@PrePersist`/`@PreUpdate` callbacks, and `@Version`. Name changes from `WorkItemEntity` to `WorkItemEntity` to avoid collision with the api/ record.

#### `WorkItemLabelEntity` (renamed from `WorkItemLabelEntity`)

The JPA `@Embeddable` stays in runtime/ with its JPA annotations.

#### `WorkItemType` — no rename needed

The JPA `@Embeddable` stays in runtime/. No api/ equivalent with the same name exists — types are `Set<String>` in the api/ record.

#### `WorkItemMapper` — new standalone mapper

**Package:** `io.casehub.work.runtime.repository`

```
toDomain(WorkItemEntity entity) → WorkItem
toEntity(WorkItem domain) → WorkItemEntity
updateEntity(WorkItemEntity entity, WorkItem domain) → void
```

Follows the `ProgressInstanceMapper` pattern. Handles the label and type conversions (entity embeddables ↔ api/ records/strings).

### Migration by Module

| Module | Change type | Detail |
|--------|------------|--------|
| `api/` | **New types** | `WorkItemEntity` record, `WorkItemLabelEntity` record, `WorkItemStore`, `CrossTenantWorkItemStore`, `WorkItemQuery`, `WorkItemRootView`, `WorkItemSummaryBuilder`, `LabelPatternMatcher` utility |
| `runtime/` | **Rename + mapper** | `WorkItemEntity` → `WorkItemEntity`, `WorkItemLabelEntity` → `WorkItemLabelEntity`, new `WorkItemMapper`, update all internal references. `LabelVocabularyService.matchesPattern()` extracted to `LabelPatternMatcher` in api/ |
| `persistence-memory/` | **Rewrite** | Dependency shifts runtime → api/. Field access changes from public fields to record accessors. Type matching rewrites from `WorkItemType.path` to `Set<String>`. Label pattern matching shifts from `LabelVocabularyService` (runtime/) to `LabelPatternMatcher` (api/). Stores `WorkItemEntity` records directly — no entity mapper needed |
| `persistence-mongodb/` | **Mapper + OCC** | New `MongoWorkItemMapper` (following JPA mapper pattern). OCC preserved: load document with version, apply domain record fields, version-checked `replaceOne`. Dependency on runtime/ shifts to api/ |
| `rest/` | **Import changes** | `WorkItemStore` from `api.spi`, entity-to-domain handled by mapper before responses |
| `queues/` | **Import changes** | Same pattern as rest/ |
| `ai/` | **Import changes** | Same |
| `ledger/` | **Import changes** | Same |
| `issue-tracker/` | **Import changes** | Same |
| `queues-dashboard/` | **Import changes** | Same |
| `engine-adapter/` | **Import changes** | Already depends on api/ |
| `qhorus/` | **Import changes** | Already depends on api/ |
| `flow/` | **Import changes** | Already depends on api/ |
| `examples/`, `*-examples/` | **Import changes** | Same |
| `integration-tests/` | **Import changes** | Same |

### Validation

- `persistence-memory/` compiles and tests pass with only `api/` dependency (no `runtime/`)
- All existing tests pass — the mapper is a faithful bidirectional conversion
- No public API changes — REST responses are unaffected (they already map from domain to DTOs)

---

## #333 — Progress REST API Documentation

Add a `## Progress` section to `docs/api-reference.md`.

### Module Table Entry

| Module | Base paths | Description |
|---|---|---|
| `casehub-work-progress` | `/progress` | Structured progress tracking with state machines, step sequences, rollback, and tree hierarchies |

### Endpoints to Document

**CRUD:**
- `POST /progress` — create a progress instance
- `GET /progress/{id}` — get by ID
- `GET /progress` — find by scope (`?scopeType=`, `?scopeId=`)
- `GET /progress/{id}/tree` — get instance with all descendants
- `POST /progress/{id}/children` — attach a child instance

**State transitions:**
- `PUT /progress/{id}/state` — update state (arbitrary JSON)
- `POST /progress/{id}/complete` — mark complete
- `POST /progress/{id}/fail` — mark failed
- `POST /progress/{id}/reactivate` — reactivate from terminal

**Rollback (#329):**
- `POST /progress/{id}/rollback` — undo last state change (optional `?toEvent=` UUID for targeted rollback)
- `GET /progress/{id}/snapshots` — state history projection (optional `?limit=`, default 100, max 1000)

**Step convenience:**
- `POST /progress/{id}/steps/{stepName}/start`
- `POST /progress/{id}/steps/{stepName}/complete`
- `POST /progress/{id}/steps/{stepName}/skip`
- `POST /progress/{id}/steps/{stepName}/fail`
- `PUT /progress/{id}/steps/{stepName}/state` — update step data (arbitrary JSON)

**Events and streaming:**
- `GET /progress/{id}/events` — event history (optional `?since=` ISO-8601)
- `GET /progress/{id}/stream` — SSE stream (optional `?tenancyId=`)

### Request DTOs

**CreateProgressRequest:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `tenancyId` | string | no | Tenant context |
| `scopeType` | string | no | Scope classifier (e.g. `workItem`, `case`) |
| `scopeId` | string | no | Scope instance ID |
| `shapeType` | string | no | Shape type (`percentage`, `count`, `step`, `custom`) |
| `state` | JSON | no | Initial state |
| `parentProgressId` | UUID | no | Parent for tree attachment |
| `rollupStrategyId` | string | no | CDI bean name for rollup strategy |
| `definition` | JSON | no | Step definitions, schema, or custom shape config |
| `rollbackPolicy` | string | no | Rollback policy (`none`, `one-step`, `unrestricted`) |
| `visualisationMode` | string | no | Display hint (`linear`, `gauge`, `checklist`, `tree`) |

**UpdateStateRequest:** `{ "state": <JSON> }`

**UpdateStepDataRequest:** `{ "data": <JSON> }`

### Response Types

`ProgressInstance` record — returned by all mutation endpoints. Key fields: `id`, `tenancyId`, `scopeType`, `scopeId`, `shapeType`, `status`, `state`, `parentProgressId`, `rollupStrategyId`, `definition`, `rollbackPolicy`, `visualisationMode`, `createdAt`, `updatedAt`.

`ProgressSnapshot` record — returned by snapshots endpoint: `eventId`, `progressId`, `state`, `status`, `occurredAt`.

`ProgressUpdatedEvent` record — returned by events and SSE: `id`, `progressId`, `rootProgressId`, `tenancyId`, `changeType`, `previousState`, `newState`, `stepName`, `occurredAt`.

`TreeResponse` — `{ "root": ProgressInstance, "descendants": ProgressInstance[] }`.

---

## #340 — resolvedScope Fix (Already Done)

Close with comment: "Already fixed — `HumanTaskScheduleHandler` uses `event.resolvedScope()` / `event.resolvedTitle()` with fallback to `target.scope()` / `target.title()` (lines 132-135, 235-243). Same pattern applied for both `createInline()` and `handleTemplateMode()`."
