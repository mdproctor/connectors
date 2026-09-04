# Hardcoded Database Name Fix — persistence-mongodb

**Issue:** #269
**Date:** 2026-06-18
**Status:** Approved

## Problem

Four stores in `persistence-mongodb` use `mongoClient.getDatabase("workitems")`
directly instead of Panache's collection resolution:

- `MongoRoutingCursorStore` — atomic `acquireNext()` via `findOneAndUpdate`
- `MongoWorkItemScheduleStore` — OCC via `findOneAndUpdate`
- `MongoWorkItemSpawnGroupStore` — OCC via `findOneAndUpdate`
- `MongoLabelVocabularyStore` — atomic `findOrCreate` via `findOneAndUpdate`

If a deployer overrides `quarkus.mongodb.database`, these 4 stores still query
`"workitems"` while the other 7 Panache-based stores follow the config.

## Approach

Replace `mongoClient.getDatabase("workitems").getCollection("xxx")` with
`MongoXxxDocument.mongoCollection()` — the Panache escape hatch that resolves
database and collection from `@MongoEntity` annotation + `quarkus.mongodb.database`
config. This is already used by `MongoIndexInitializer` for index creation on these
same document classes.

`findOneAndUpdate` returns the entity type (not raw `Document`), so field access
changes from `result.getLong("version")` to `result.version`. For
`MongoLabelVocabularyStore`, the 6-line manual field extraction is replaced by
`result.toDomain()`.

`@Inject MongoClient mongoClient` is removed from all 4 stores.

### Why not inject the database name from config

`@ConfigProperty(name = "quarkus.mongodb.database") String databaseName` would
make the name configurable but still bypass Panache's resolution. It keeps the
raw `Document` return type and duplicated field extraction. The right fix is to
use Panache's collection resolution end-to-end.

## Changes per store

**MongoRoutingCursorStore:**
- Remove `@Inject MongoClient mongoClient`
- `MongoRoutingCursorDocument.mongoCollection().findOneAndUpdate(...)` → returns `MongoRoutingCursorDocument`
- `result.getLong("lastIndex")` → `result.lastIndex`

**MongoWorkItemScheduleStore:**
- Remove `@Inject MongoClient mongoClient`
- `MongoWorkItemScheduleDocument.mongoCollection().findOneAndUpdate(...)` → returns `MongoWorkItemScheduleDocument`
- `result.getLong("version")` → `result.version`

**MongoWorkItemSpawnGroupStore:**
- Remove `@Inject MongoClient mongoClient`
- `MongoWorkItemSpawnGroupDocument.mongoCollection().findOneAndUpdate(...)` → returns `MongoWorkItemSpawnGroupDocument`
- `result.getLong("version")` → `result.version`

**MongoLabelVocabularyStore:**
- Remove `@Inject MongoClient mongoClient`
- `MongoLabelVocabularyDocument.mongoCollection().findOneAndUpdate(...)` → returns `MongoLabelVocabularyDocument`
- Replace manual `Document` field extraction with `result.toDomain()`

## Testing

Existing tests cover all 4 code paths (OCC, atomic increment, findOrCreate).
No new tests — behaviour is identical; only the collection source changes.
Run the full `persistence-mongodb` test suite.

## Scope

- 4 files modified (one per store)
- 0 files created
- No API changes, no new dependencies
