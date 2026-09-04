# #253 — Complete MongoDB Persistence Module

## Problem

`persistence-mongodb/` implements `WorkItemStore` and `AuditEntryStore` but not the remaining eleven runtime/core SPIs. Consumers wanting MongoDB-only persistence still need a JPA datasource for all of these:

| SPI | Module | Complexity | Functional coupling |
|-----|--------|-----------|-------------------|
| WorkItemNoteStore | runtime | Simple CRUD | — |
| WorkItemScheduleStore | runtime | CRUD + `findDue()` + OCC | → WorkItemTemplateStore |
| WorkItemTemplateStore | runtime | CRUD + `getByName()` | ← WorkItemScheduleStore |
| WorkItemLinkStore | runtime | CRUD + type filter | — |
| WorkItemSpawnGroupStore | runtime | CRUD + composite queries + OCC | — |
| WorkItemRelationStore | runtime | Directed graph queries | — |
| LabelVocabularyStore | runtime | CRUD + `findByScope()` + `findOrCreate()` | → LabelDefinitionStore |
| LabelDefinitionStore | runtime | CRUD + `findByPath()` | ← LabelVocabularyStore |
| FilterRuleStore | runtime | CRUD + `allEnabled()` | — |
| RoutingCursorStore | core | Atomic increment (`acquireNext()`) | — |
| IssueLinkStore | issue-tracker | CRUD + compound-key lookups | — |

Functional coupling means the stores must be implemented together — adding Mongo schedules without Mongo templates causes `WorkItemScheduleService.fireSchedule()` to read from an empty JPA table, silently producing nothing. Same for vocabularies without definitions.

## Approach

Implement all eleven in `persistence-mongodb/` following the established pattern: Panache MongoDB document class + `@Alternative @Priority(1)` store class + tenant scoping via `CurrentPrincipal`.

Add `casehub-work-issue-tracker` as a compile dependency of `persistence-mongodb`. This follows the `persistence-memory` precedent — both persistence backend modules centralize all store implementations in one module. The alternative (each optional module contributing its own Mongo store) distributes MongoDB knowledge across the codebase rather than centralizing it. The centralized approach is better: one module owns all MongoDB persistence, one place to look, one dependency to add.

## Implementations

### MongoWorkItemNoteStore + MongoWorkItemNoteDocument

- Collection: `work_item_notes`
- Fields: `id` (BsonId, String), `tenancyId`, `workItemId`, `content`, `author`, `createdAt`, `editedAt`
- Standard `from(WorkItemNote)` / `toDomain()` conversion
- `append()`: assign UUID + createdAt + tenancyId if absent, then `persist()`
- `findById()`: filter on `_id + tenancyId`
- `findByWorkItemId()`: filter on `workItemId + tenancyId`, sort by `createdAt ASC`
- `update()`: stamp tenancyId if absent, then `persistOrUpdate()`
- `delete()`: `deleteOne()` with `_id + tenancyId` filter, return `deletedCount > 0`

**tenancyId stamping on update():** The "if absent" guard on `update()` follows the JPA and InMemory implementations. A note reaching `update()` without tenancyId is itself a caller bug (the insert path should have stamped it). The guard silently masks that. Fixing the contract to throw instead belongs in a cross-tier design change, not scoped to #253.

### MongoIssueLinkStore + MongoIssueLinkDocument

- Collection: `work_item_issue_links`
- Fields: `id`, `tenancyId`, `workItemId`, `trackerType`, `externalRef`, `title`, `url`, `status`, `linkedAt`, `linkedBy`
- `findById()`: filter on `_id + tenancyId`
- `findByWorkItemId()`: filter on `workItemId + tenancyId`, sort by `linkedAt ASC`
- `findByRef()`: compound filter on `workItemId + trackerType + externalRef + tenancyId`
- `findByTrackerRef()`: filter on `trackerType + externalRef + tenancyId`
- `save()`: assign UUID + linkedAt + tenancyId if absent, then `persistOrUpdate()`
- `delete(WorkItemIssueLink link)`: extract `link.id`, filter by `_id` + validate `currentPrincipal.tenancyId()` matches the stored document's tenancyId before deleting (matching InMemory behavior)

### MongoRoutingCursorStore + MongoRoutingCursorDocument

- Collection: `routing_cursors`
- Document ID: `poolHash + ":" + tenancyId` (composite string key)
- Fields: `lastIndex` (BSON int64 / NumberLong), `lastAccessed` (Instant)
- `acquireNext()`: obtain the raw `MongoCollection` via `MongoRoutingCursorDocument.mongoCollection()` (Panache escape hatch — the standard Panache CRUD doesn't support atomic `findOneAndUpdate`). Execute `findOneAndUpdate()` with:
  - Filter: `{_id: poolHash:tenancyId}`
  - Update: `{$inc: {lastIndex: 1}, $set: {lastAccessed: now}, $setOnInsert: {lastIndex: -1}}`
  - Options: `upsert: true, returnDocument: AFTER`
- First call (upsert): `$setOnInsert` sets `lastIndex: -1`, then `$inc` advances to 0. Returned document has `lastIndex: 0`. `acquireNext()` returns `Math.floorMod(0, poolSize) = 0`. Matches JPA/InMemory first-call behavior.
- `lastIndex` is stored as BSON int64 (NumberLong) to extend the overflow horizon beyond int32's ~2.1B limit.
- Java side uses `Math.floorMod()` instead of `%` to guarantee non-negative results even if the counter somehow wraps.

### MongoWorkItemScheduleStore + MongoWorkItemScheduleDocument

- Collection: `work_item_schedules`
- Fields: `id`, `tenancyId`, `name`, `templateId`, `cronExpression`, `active` (boolean), `createdBy`, `createdAt`, `lastFiredAt`, `nextFireAt`, `version` (Long)
- `put()`: OCC-safe write — use `mongoCollection().findOneAndUpdate()` with filter `{_id: id, version: currentVersion}` and update `{$set: {all fields}, $inc: {version: 1}}`. On new documents (no existing `_id`), use a plain `persist()` with `version: 0`. If filter matches zero documents on update → throw `jakarta.persistence.OptimisticLockException` (or equivalent). This matches JPA's `@Version` behavior for clustered double-fire prevention.
- `get()`: filter on `_id + tenancyId`
- `scanAll()`: filter on `tenancyId`, sort by `name ASC`
- `delete()`: filter on `_id + tenancyId`, return `deletedCount > 0`
- `findDue()`: filter on `active: true AND nextFireAt != null AND nextFireAt <= now AND tenancyId`, sort by `nextFireAt ASC`

### MongoWorkItemTemplateStore + MongoWorkItemTemplateDocument

- Collection: `work_item_templates`
- Fields: `id`, `tenancyId`, `name`, `description`, `category`, `priority` (String — `WorkItemPriority.name()` on write, `WorkItemPriority.valueOf()` on read, matching MongoWorkItemDocument pattern), `candidateGroups`, `candidateUsers`, `requiredCapabilities`, `defaultExpiryHours`, `defaultClaimHours`, `defaultExpiryBusinessHours`, `defaultClaimBusinessHours`, `defaultPayload`, `labelPaths`, `outcomes`, `excludedUsers`, `excludedGroups`, `scope`, `inputDataSchema`, `outputDataSchema`, `instanceCount`, `requiredCount`, `parentRole`, `assignmentStrategy`, `onThresholdReached`, `allowSameAssignee`, `createdBy`, `createdAt`
- `put()`: assign UUID + createdAt + tenancyId if absent, `persistOrUpdate()`
- `get()`: filter on `_id + tenancyId`
- `getByName()`: filter on `name + tenancyId`
- `scanAll()`: filter on `tenancyId`, sort by `name ASC`
- `delete()`: filter on `_id + tenancyId`, return `deletedCount > 0`

### MongoWorkItemLinkStore + MongoWorkItemLinkDocument

- Collection: `work_item_links`
- Fields: `id`, `tenancyId`, `workItemId`, `url`, `title`, `relationType`, `linkedBy`, `createdAt`
- `put()`: assign UUID + createdAt + tenancyId if absent, `persistOrUpdate()`
- `get()`: filter on `_id + tenancyId`
- `findByWorkItemId()`: filter on `workItemId + tenancyId`, sort by `createdAt ASC`
- `findByWorkItemIdAndType()`: filter on `workItemId + relationType + tenancyId`, sort by `createdAt ASC`
- `delete()`: filter on `_id + tenancyId`, return `deletedCount > 0`

### MongoWorkItemSpawnGroupStore + MongoWorkItemSpawnGroupDocument

- Collection: `work_item_spawn_groups`
- Fields: `id`, `tenancyId`, `parentId`, `idempotencyKey`, `createdAt`, `version` (Long), `instanceCount`, `requiredCount`, `onThresholdReached`, `allowSameAssignee`, `parentRole`, `completedCount`, `rejectedCount`, `policyTriggered`
- `put()`: OCC-safe write — same `findOneAndUpdate` + version-check pattern as WorkItemScheduleStore. M-of-N counter increments (`completedCount`, `rejectedCount`) go through `put()`, so the version check prevents double-counting under concurrent child completion.
- `get()`: filter on `_id + tenancyId`
- `findByParentId()`: filter on `parentId + tenancyId`, sort by `createdAt DESC`
- `findByParentAndKey()`: filter on `parentId + idempotencyKey + tenancyId`
- `findMultiInstanceByParentId()`: filter on `parentId + tenancyId + requiredCount != null`
- `delete()`: filter on `_id + tenancyId`, return `deletedCount > 0`

### MongoWorkItemRelationStore + MongoWorkItemRelationDocument

- Collection: `work_item_relations`
- Fields: `id`, `tenancyId`, `sourceId`, `targetId`, `relationType`, `createdBy`, `createdAt`
- `put()`: assign UUID + createdAt + tenancyId if absent, `persistOrUpdate()`
- `get()`: filter on `_id + tenancyId`
- `findBySourceId()`: filter on `sourceId + tenancyId`, sort by `createdAt ASC`
- `findByTargetId()`: filter on `targetId + tenancyId`, sort by `createdAt ASC`
- `findBySourceAndType()`: filter on `sourceId + relationType + tenancyId`, sort by `createdAt ASC`
- `findByTargetAndType()`: filter on `targetId + relationType + tenancyId`, sort by `createdAt ASC`
- `findExisting()`: filter on `sourceId + targetId + relationType + tenancyId`
- `delete()`: filter on `_id + tenancyId`, return `deletedCount > 0`

### MongoLabelVocabularyStore + MongoLabelVocabularyDocument

- Collection: `label_vocabularies`
- Fields: `id`, `tenancyId`, `scope` (String — `Path.toString()` on write, `Path.of()` on read), `name`
- `put()`: assign UUID + tenancyId if absent, `persistOrUpdate()`
- `get()`: filter on `_id + tenancyId`
- `scanAll()`: filter on `tenancyId`
- `findByScope()`: filter on `scope + tenancyId` (exact match on the String representation)
- `findOrCreate()`: override the default method with atomic `findOneAndUpdate` + `upsert: true` for race-safety (the default check-then-act is not safe under concurrent creation)
- `delete()`: filter on `_id + tenancyId`, return `deletedCount > 0`

### MongoLabelDefinitionStore + MongoLabelDefinitionDocument

- Collection: `label_definitions`
- Fields: `id`, `tenancyId`, `path` (String — `Path.toString()` / `Path.of()`), `vocabularyId`, `description`, `createdBy`, `createdAt`
- `put()`: assign UUID + createdAt + tenancyId if absent, `persistOrUpdate()`
- `get()`: filter on `_id + tenancyId`
- `findByVocabularyId()`: filter on `vocabularyId + tenancyId`
- `findByPath()`: filter on `path + tenancyId` (exact match on String representation)
- `delete()`: filter on `_id + tenancyId`, return `deletedCount > 0`

### MongoFilterRuleStore + MongoFilterRuleDocument

- Collection: `filter_rules`
- Fields: `id`, `tenancyId`, `name`, `description`, `enabled`, `condition`, `events`, `actionsJson`, `createdAt`
- `put()`: assign UUID + createdAt + tenancyId if absent, `persistOrUpdate()`
- `get()`: filter on `_id + tenancyId`
- `allEnabled()`: filter on `enabled: true + tenancyId`, sort by `createdAt ASC`
- `scanAll()`: filter on `tenancyId`, sort by `createdAt ASC`
- `delete()`: filter on `_id + tenancyId`, return `deletedCount > 0`

## CDI

All eleven stores: `@ApplicationScoped @Alternative @Priority(1)` — Tier 2 per `persistence-backend-cdi-priority.md` (located at `casehub/garden/docs/protocols/universal/persistence-backend-cdi-priority.md`).

## Dependencies

Add to `persistence-mongodb/pom.xml`:
```xml
<dependency>
  <groupId>io.casehub</groupId>
  <artifactId>casehub-work-issue-tracker</artifactId>
  <version>${project.version}</version>
</dependency>
```

## SPI Javadoc

Update all eleven SPI interfaces: replace any "No Tier 2 (MongoDB) exists yet" lines with the Tier 2 entry referencing `casehub-work-persistence-mongodb`.

## Unique Indexes

Four entities have JPA `@UniqueConstraint` annotations that must be mirrored as MongoDB unique compound indexes. Without them, uniqueness guarantees are silently lost — concurrent operations create duplicate data. Additionally, `LabelVocabularyStore.findOrCreate()` requires a unique index for the upsert to be atomic.

| Collection | Index fields | Mirrors |
|-----------|-------------|---------|
| `work_item_templates` | `{name: 1, tenancyId: 1}` unique | `@UniqueConstraint(name, tenancy_id)` |
| `work_item_spawn_groups` | `{parentId: 1, idempotencyKey: 1}` unique | `@UniqueConstraint(parent_id, idempotency_key)` |
| `work_item_relations` | `{sourceId: 1, targetId: 1, relationType: 1}` unique | `@UniqueConstraint(source_id, target_id, relation_type)` |
| `work_item_issue_links` | `{workItemId: 1, trackerType: 1, externalRef: 1}` unique | `@UniqueConstraint(work_item_id, tracker_type, external_ref)` |
| `label_vocabularies` | `{scope: 1, tenancyId: 1}` unique | Required for `findOrCreate()` upsert atomicity |

Create these indexes programmatically at startup — a `@Startup @ApplicationScoped` bean calling `collection.createIndex()` at boot. (`@MongoEntity` only supports `collection`, `database`, `clientName`, `readPreference` — no index definitions.) Store implementations that rely on uniqueness for correctness (e.g. `findByParentAndKey()` idempotency, `findExisting()` duplicate detection) must handle `MongoWriteException` with duplicate key error code.

**Performance indexes** (e.g. `{tenancyId: 1, workItemId: 1}` on notes/links, `{tenancyId: 1, active: 1, nextFireAt: 1}` on schedules) are operational concerns — they belong in a deployment guide, not this spec. The correctness indexes above are the baseline.

## OCC Pattern — Versioned Entities

Two domain models use `@Version` for optimistic concurrency control:
- **WorkItemSchedule** — prevents clustered double-fire of recurring schedules
- **WorkItemSpawnGroup** — prevents double-counting of M-of-N child completions

MongoDB Panache `persistOrUpdate()` does a plain upsert by `_id` — no version check. Both Mongo store implementations must use the raw `mongoCollection().findOneAndUpdate()` escape hatch:

```
filter:  {_id: id, version: currentVersion}
update:  {$set: {field1: v1, ...}, $inc: {version: 1}}
options: returnDocument: AFTER
```

- New documents: plain `persist()` with `version: 0L`
- Existing documents: `findOneAndUpdate` with version filter. If result is `null` (filter matched zero documents), throw `jakarta.persistence.OptimisticLockException`

This matches JPA's `@Version` semantics: any concurrent modification bumps the version, causing the stale writer's filter to miss.

## Out of Scope — Filed as #267

**CrossTenantProducer hardcodes JPA types — data integrity impact.** `CrossTenantProducer` directly injects `JpaCrossTenantRoutingCursorStore`, `JpaCrossTenantWorkItemStore`, `JpaCrossTenantWorkItemScheduleStore`. When MongoDB is active:

- **RoutingCursorCleanupJob** calls `cleanupStale()` against an empty JPA table while live cursor data sits in MongoDB. Stale cursors accumulate forever with no cleanup.
- **WorkItemScheduleService** (via `CrossTenantWorkItemScheduleStore.findActive()`) finds zero schedules in the empty JPA table while live schedules sit in MongoDB. Recurring WorkItem creation silently stops.

This is a design defect in the producer pattern, not in the individual stores. Filed as #267.

## Tests

Each store gets a `@QuarkusTest` following the existing `MongoWorkItemStoreTest` / `MongoAuditEntryStoreTest` pattern. Inline in a single test class per store (matching the MongoDB module's convention).

Tenant isolation tests are inline — each test creates data under two distinct tenancyIds and asserts queries only return data for the active tenant:

- `MongoWorkItemNoteStoreTest` — append/findById/findByWorkItemId/update/delete roundtrip; tenant isolation
- `MongoIssueLinkStoreTest` — save/findById/findByWorkItemId/findByRef/findByTrackerRef/delete; delete extracts `link.id` and validates tenancyId; tenant isolation
- `MongoRoutingCursorStoreTest` — first acquireNext returns 0; sequential calls advance and wrap at poolSize; concurrent-safe (atomic); tenant isolation (cursor state per-tenant)
- `MongoWorkItemScheduleStoreTest` — put/get/scanAll/delete/findDue roundtrip; findDue only returns active schedules with nextFireAt <= now; OCC: concurrent put with stale version throws OptimisticLockException; tenant isolation
- `MongoWorkItemTemplateStoreTest` — put/get/getByName/scanAll/delete roundtrip; tenant isolation
- `MongoWorkItemLinkStoreTest` — put/get/findByWorkItemId/findByWorkItemIdAndType/delete; tenant isolation
- `MongoWorkItemSpawnGroupStoreTest` — put/get/findByParentId/findByParentAndKey/findMultiInstanceByParentId/delete; OCC: concurrent counter increment with stale version throws; tenant isolation
- `MongoWorkItemRelationStoreTest` — put/get/findBySourceId/findByTargetId/findBySourceAndType/findByTargetAndType/findExisting/delete; tenant isolation
- `MongoLabelVocabularyStoreTest` — put/get/scanAll/findByScope/findOrCreate/delete; findOrCreate atomicity; tenant isolation
- `MongoLabelDefinitionStoreTest` — put/get/findByVocabularyId/findByPath/delete; tenant isolation
- `MongoFilterRuleStoreTest` — put/get/allEnabled/scanAll/delete; allEnabled only returns enabled rules; tenant isolation
