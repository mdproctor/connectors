# MongoWorkItemDocument Field Sync + OCC

**Issue:** casehubio/work#270
**Date:** 2026-06-20
**Revised:** 2026-06-21 (review round 2 — 7 findings applied)

## Problem

`MongoWorkItemDocument` is missing 13 fields present on the `WorkItemEntity` JPA entity. Data written through the MongoDB store silently drops these fields on write and never restores them on read. This breaks:

- **Multi-instance groups:** `parentId` not persisted → `findByParentId()`, `findByParentIdExcludingStatuses()`, `findByParentIdWithStatuses()`, `countByParentAndAssignee()` all return empty/zero via default linear-scan implementations that read `toDomain()` output.
- **Engine spawn routing:** `callerRef` not persisted → `findByCallerRef()` returns empty. Engine-initiated WorkItems cannot be correlated back to their originating PlanItem.
- **Claim SLA tracking:** `accumulatedUnclaimedSeconds`, `lastReturnedToPoolAt` reset on every round-trip.
- **Named outcomes:** `templateId`, `permittedOutcomes`, `outcome`, `excludedUsers` lost.
- **Schema validation:** `inputDataSchema`, `outputDataSchema` lost.
- **AI metadata:** `confidenceScore` lost.
- **Scope:** `scope` lost.
- **Concurrency:** `version` not mapped. Additionally, `MongoWorkItemStore.put()` uses `persistOrUpdate()` (unconditional upsert) with no version check — two concurrent claims can both succeed. JPA prevents this via `@Version` → `OptimisticLockException` → HTTP 409.
- **Outcome query filter:** `MongoWorkItemStore.buildFilter()` ignores `WorkItemQuery.outcome()` — the 12th of 12 query dimensions. REST API `GET /workitems?outcome=approved` silently returns all WorkItems regardless of outcome. JPA and InMemory stores handle it correctly.

## Design

### 1. MongoWorkItemDocument — 13 new fields

| Field | Doc type | Conversion | Notes |
|-------|----------|------------|-------|
| `version` | `Long` | Direct | OCC version, managed by store |
| `accumulatedUnclaimedSeconds` | `long` | Direct | Primitive, default 0 |
| `lastReturnedToPoolAt` | `Instant` | Direct | |
| `confidenceScore` | `Double` | Direct | |
| `callerRef` | `String` | Direct | |
| `parentId` | `String` | `UUID.toString()` / `UUID.fromString()` | Same as `id` |
| `scope` | `String` | Direct | |
| `templateId` | `String` | `UUID.toString()` / `UUID.fromString()` | Same as `id` |
| `permittedOutcomes` | `String` | Direct | JSON array string — not queried at store level |
| `excludedUsers` | `List<String>` | `splitCsv()` / `joinCsv()` | Consistent with candidateGroups/candidateUsers |
| `outcome` | `String` | Direct | |
| `inputDataSchema` | `String` | Direct | |
| `outputDataSchema` | `String` | Direct | |

**`excludedUsers` as `List<String>`:** Same CSV→array conversion as `candidateGroups`/`candidateUsers`. Enables `$in` queries and follows the established MongoDB-native representation for sets of user IDs.

**`permittedOutcomes` as `String`:** It's a JSON array, not CSV. Service layer owns parsing. No store-level query need.

All fields mapped in both `from()` and `toDomain()`.

### 2. MongoWorkItemStore.put() — optimistic concurrency control

Replace the current `persistOrUpdate()` upsert with version-checked writes.

**Insert vs update detection:** Check for document existence via `MongoWorkItemDocument.find({_id: id}).firstResult() != null`. This is the same pattern used by `MongoWorkItemSpawnGroupStore` and `MongoWorkItemScheduleStore`. Using `version == null` is not viable — `WorkItem.version` is initialized to `0L`, never null.

**Insert path** (document does not exist):
1. Assign id, tenancyId, timestamps (unchanged)
2. Set `workItem.version = 0L`
3. `MongoWorkItemDocument.from(workItem).persist()`

**Update path** (document exists):
1. Update timestamps (unchanged)
2. Build doc via `MongoWorkItemDocument.from(workItem)`
3. Compute new version: `long currentVersion = workItem.version != null ? workItem.version : 0L; doc.version = currentVersion + 1`
4. `replaceOne(filter={_id: id, version: workItem.version}, doc)`
5. If `modifiedCount == 0` → throw `jakarta.persistence.OptimisticLockException`
6. Set `workItem.version = doc.version`

The null-safe arithmetic in step 3 handles the migration case (existing documents written before this change have no `version` field — `toDomain()` maps them as `version = null`). The filter `{version: null}` correctly matches documents where the field is absent (MongoDB `$eq: null` matches both explicit null and absent fields).

**Why `replaceOne` instead of `findOneAndUpdate`:** SpawnGroupStore/ScheduleStore use `findOneAndUpdate` with explicit `Updates.set()` per field (~12 fields each). WorkItem has 44 fields — enumerating them all in `Updates.combine()` is brittle and won't self-maintain when fields are added. `replaceOne` reuses `from()` for the full document. This is a deliberate pattern divergence from SpawnGroupStore/ScheduleStore: those stores use `findOneAndUpdate` because they atomically increment counters (`completedCount`, `rejectedCount`). WorkItem has no counters — it's a full state replacement where `replaceOne` is the natural fit.

**Tenancy protocol compliance:** Unchanged — `tenancyId` stamped on insert when null, preserved on update.

### 3. MongoWorkItemStore.buildFilter() — outcome filter fix

`buildFilter()` handles 11 of 12 `WorkItemQuery` dimensions but silently ignores `outcome`. Add:

```java
if (q.outcome() != null) {
    ands.add(new Document("outcome", q.outcome()));
}
```

This aligns MongoDB with JPA (`JpaWorkItemStore.java:138-143`) and InMemory (`InMemoryWorkItemStore.java:173`).

### 4. MongoWorkItemStore — 5 query method overrides

All currently fall back to default `WorkItemStore` implementations that linear-scan `scanAll()`. With `callerRef` and `parentId` now persisted, these become proper server-side filtered queries (not indexed — MongoDB index definitions are a separate concern across all collections). All are tenant-scoped.

- **`findByCallerRef(String callerRef)`** — filter `{callerRef, tenancyId}`, return first or empty
- **`findByParentId(UUID parentId)`** — filter `{parentId, tenancyId}`, return list
- **`findByParentIdExcludingStatuses(UUID, List<WorkItemStatus>)`** — filter `{parentId, tenancyId, status: {$nin: ...}}`
- **`findByParentIdWithStatuses(UUID, List<WorkItemStatus>)`** — filter `{parentId, tenancyId, status: {$in: ...}}`
- **`countByParentAndAssignee(UUID, String, UUID)`** — filter `{parentId, assigneeId, tenancyId, _id: {$ne: excludeId}, status: {$nin: terminalStatuses}}`, return count. Terminal statuses derived from `WorkItemStatus.isTerminal()` at runtime — not hardcoded. This correctly excludes all 7 terminal statuses (COMPLETED, REJECTED, FAULTED, CANCELLED, OBSOLETE, EXPIRED, ESCALATED).

Note: the JPA implementation (`JpaWorkItemStore.java:186-190`) hardcodes 4 of 7 terminal statuses, missing FAULTED, OBSOLETE, and EXPIRED. Filed as a separate issue — see Follow-up issues below.

### 5. Migration — existing MongoDB documents

MongoDB is schema-less. No collection-wide migration is required. New fields are absent on old documents and present on new ones.

Field-by-field behavior when reading an old document that lacks the 13 new fields:

- **`accumulatedUnclaimedSeconds`** (primitive `long` on document): BSON POJO codec leaves unset primitives at Java defaults → 0. This is the correct domain default.
- **`version`** (`Long` on document): absent → null. Handled by the null-safe arithmetic in the OCC update path — first update migrates the document to version 1 (see §2 step 3).
- **All other 11 fields** (nullable reference types: `String`, `Double`, `Instant`, `List<String>`): absent → null. Null is the correct default for absent data — these fields are all nullable on the domain entity.

### 6. Test coverage

Extend `MongoWorkItemStoreTest`:

**Roundtrip:** Single test populating all 13 fields → `put()` → `get()` → assert every field. `excludedUsers` CSV `"alice,bob"` round-trips through `List<String>` storage.

**OCC:**
- `put_setsVersionToZero_onInsert`
- `put_incrementsVersion_onUpdate`
- `put_throwsOptimisticLockException_onStaleVersion` (mirrors SpawnGroupStoreTest/ScheduleStoreTest pattern)

**Query methods:**
- `findByCallerRef_returnsMatching` / `_returnsEmpty_whenNotFound`
- `findByParentId_returnsChildren`
- `findByParentIdExcludingStatuses_excludesTerminal`
- `findByParentIdWithStatuses_filtersToGivenStatuses`
- `countByParentAndAssignee_excludesSelf`

**Outcome filter:**
- `scan_byOutcome_filtersCorrectly`

**Tenant isolation:**
- Cross-cutting test: create a WorkItem with `callerRef` and `parentId` in tenant A, assert `findByCallerRef()` and `findByParentId()` from tenant B return empty.

## Files changed

| File | Change |
|------|--------|
| `persistence-mongodb/.../MongoWorkItemDocument.java` | Add 13 fields, update `from()`, `toDomain()` |
| `persistence-mongodb/.../MongoWorkItemStore.java` | OCC in `put()`, outcome in `buildFilter()`, 5 query overrides |
| `persistence-mongodb/.../MongoWorkItemStoreTest.java` | Roundtrip, OCC, query, outcome, tenant isolation tests |

## Protocol coherence

- **store-tenancy-stamping-on-insert:** `put()` stamps on insert, preserves on update — unchanged.
- **cross-tenant-store-minimal-surface:** `MongoCrossTenantWorkItemStore` uses `MongoWorkItemDocument.toDomain()` — benefits automatically from the field mapping fix. No changes needed there.

## Follow-up issues (out of scope for #270)

- **JPA `countByParentAndAssignee` hardcodes 4 of 7 terminal statuses** — `JpaWorkItemStore.java:186-190` lists COMPLETED, REJECTED, CANCELLED, ESCALATED but misses FAULTED, OBSOLETE, EXPIRED. Should use `WorkItemStatus.isTerminal()` to derive the exclusion set dynamically.
- **`WorkItemStore.put()` contract doesn't document OCC** — JPA provides it via `@Version`, MongoDB will provide it via version filters, InMemory doesn't provide it at all. The service layer relies on OCC for claim atomicity. If a future backend omits OCC, double-claims would silently succeed. The interface should document OCC as a `put()` contract requirement.
