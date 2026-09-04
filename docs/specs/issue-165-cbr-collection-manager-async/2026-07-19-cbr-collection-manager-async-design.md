# CbrCollectionManager Async Methods — Design Spec

**Issue:** casehubio/neocortex#165
**Date:** 2026-07-19
**Status:** Approved

## Problem

`CbrCollectionManager` wraps natively-async Qdrant gRPC calls (`ListenableFuture`) with blocking `.get()`. `ReactiveQdrantCbrCaseMemoryStore` then wraps these blocking calls with `runSubscriptionOn(workerPool)` to avoid blocking the Vert.x event loop. This creates a double conversion: async → blocking → async, with unnecessary worker pool dispatch overhead.

Pre-existing bug: `purgeCollection()` calls `deleteByFilter` via `Uni.createFrom().item(() -> ...)` without `runSubscriptionOn` — blocks the event loop if called from a reactive context.

## Approach

**Async canonical, blocking convenience wrappers.** Async methods become the real implementation using `toUni()` to chain `ListenableFuture` calls natively. Blocking methods become trivial one-liners (`asyncVariant().await().indefinitely()`) for callers that run on worker threads (e.g. `CbrReconciliationService`).

## Changes

### 1. QdrantFutures utility (new file)

Package-private utility class in `io.casehub.neocortex.memory.cbr.qdrant`, mirroring the `rag` module's existing `QdrantFutures`. Single static method:

```java
static <T> Uni<T> toUni(ListenableFuture<T> future)
```

Includes cancellation propagation (`em.onTermination(() -> future.cancel(false))`). Replaces the private static `toUni()` in `ReactiveQdrantCbrCaseMemoryStore`, which lacks cancellation propagation — cancelled Uni subscriptions (timeout, client disconnect, competing Uni arm failure) now cancel the underlying gRPC `ListenableFuture`. This is an intentional improvement affecting all existing `toUni()` callers in the reactive store, matching the rag module's established behavior.

**Duplication rationale:** `memory-qdrant` has no Maven dependency on the `rag` module, and the rag copy is package-private. Both are internal implementation details of their respective modules — a shared utility module for a 12-line static method would be over-engineering. Each copy has its own test class (`QdrantFuturesTest`).

### 2. CbrCollectionManager — async canonical

Three public async methods, each returning `Uni`:

**`ensureCollectionAsync(String caseType, int vectorDimension)` → `Uni<Void>`**

Chains:
1. Synchronous fast-path: `knownCollections.contains(collection)` → `Uni.createFrom().voidItem()`
2. `toUni(collectionExistsAsync)` → branch:
   - Exists: `toUni(getCollectionInfoAsync)` → validate dimensions/sparse vectors → possibly `toUni(deleteCollectionAsync)` + recreate
   - Not exists: build `CreateCollection`, `toUni(createCollectionAsync)` → `createBasePayloadIndexesAsync`
3. `.invoke(() -> knownCollections.add(collection))`

Domain exceptions (`CbrDimensionMismatchException`, `CbrSparseVectorMigrationException`) propagate as Uni failures. No `InterruptedException`/`ExecutionException` handling needed — `toUni()` handles this.

**`registerSchemaIndexesAsync(CbrFeatureSchema schema, int vectorDimension)` → `Uni<Void>`**

Chains `ensureCollectionAsync()` then iterates schema fields sequentially via `Multi.createFrom().iterable(schema.fields()).onItem().transformToUniAndConcatenate(field -> indexesForField(collection, "f_" + field.name(), field)).collect().asList().replaceWithVoid()`.

**Private helper:** `indexesForField(String collection, String payloadKey, FeatureField field)` → `Uni<Void>` — handles the per-field switch:
- Simple types (`Categorical`, `Numeric`, `Text`, `CategoricalList`, `NumericList`): single `toUni(createPayloadIndexAsync(collection, payloadKey, type, ...)).replaceWithVoid()`
- `NestedObject`: `Multi.createFrom().iterable(no.innerFields()).onItem().transformToUniAndConcatenate(inner -> toUni(createPayloadIndexAsync(collection, payloadKey + "." + inner.name(), innerPayloadType(inner), ...)).replaceWithVoid()).collect().asList().replaceWithVoid()` — flattens the nested loop into a sequential Multi chain, drained fully before completing
- `ObjectList`: same as `NestedObject` but with `payloadKey + "[]." + inner.name()` key format
- `TimeSeries`, `DiscreteSequence`: `Uni.createFrom().voidItem()` (no indexes)

All Multi chains use `.collect().asList()` as the terminal operator, matching the established pattern in `ReactiveQdrantCbrCaseMemoryStore` (6 existing instances). `.toUni()` is not used — it requests only one item and cancels the upstream, which would risk incomplete index creation.

**`deleteByFilterAsync(String collection, Filter filter)` → `Uni<Integer>`**

Chains `toUni(scrollAsync)` → count result → if > 0 `toUni(deleteAsync)` → return count.

**Private helper:** `createBasePayloadIndexesAsync(String collection)` → `Uni<Void>` — iterates `BASE_KEYWORD_FIELDS` creating keyword indexes, then `_stored_at` float index.

**Blocking wrappers** (one-liners, for `CbrReconciliationService`):
```java
void ensureCollection(String caseType, int dim) {
    ensureCollectionAsync(caseType, dim).await().indefinitely();
}
int deleteByFilter(String collection, Filter filter) {
    return deleteByFilterAsync(collection, filter).await().indefinitely();
}
void registerSchemaIndexes(CbrFeatureSchema schema, int dim) {
    registerSchemaIndexesAsync(schema, dim).await().indefinitely();
}
```

### 3. ReactiveQdrantCbrCaseMemoryStore — remove worker pool dispatch

**`registerSchema()`** — direct async call, no `runSubscriptionOn`:
```java
schemas.put(schema.caseType(), schema);
return collectionManager.registerSchemaIndexesAsync(schema, vectorDimension());
```

**`store()`** — restructured into a pipeline separating blocking from async:
1. Worker pool: `delegate.store()`, `embeddingModel.embed()`, `sparseEmbedder.embed()`, `CamelCaseExpander.expand()`, `CbrPointBuilder.buildPoint()`, `collectionManager.collectionName()` → `StoreContext` (existing record, unchanged)
2. Event loop: `collectionManager.ensureCollectionAsync(caseType, dim).replaceWith(ctx)` (async, no pool needed)
3. Event loop: `upsertWithRetry(ctx.collection(), List.of(ctx.point()), config.maxRetries()).replaceWith(ctx.memoryId())`

`buildPoint()` stays on the worker pool — it depends only on embeddings and metadata, not on collection existence. This reuses the existing `StoreContext(memoryId, collection, point)` record with no new intermediate types.

**`eraseFromAllCollections()`** — replace `Uni.createFrom().item(() -> deleteByFilter(...)).runSubscriptionOn(workerPool)` with `collectionManager.deleteByFilterAsync(collection, filter)`.

**`purgeCollection()`** — same replacement. Fixes the pre-existing bug where `deleteByFilter` ran on the calling thread without `runSubscriptionOn`.

### 4. Files unchanged

- `CbrReconciliationService.java` — blocking batch service, uses blocking convenience wrappers
- `QdrantCbrCaseMemoryStore.java` — thin delegate to reactive store, no direct CbrCollectionManager usage
- `QdrantCbrBeanProducer.java` — produces CbrCollectionManager, no API change

## Scope

| File | Change type |
|------|-------------|
| `QdrantFutures.java` | New — package-private `toUni()` utility |
| `QdrantFuturesTest.java` | New — unit tests mirroring rag module's `QdrantFuturesTest` |
| `CbrCollectionManager.java` | Modified — async methods canonical, blocking wrappers |
| `ReactiveQdrantCbrCaseMemoryStore.java` | Modified — use async methods, restructure `store()`, remove private `toUni()` |

## Testing

Existing `QdrantCbrCaseMemoryStoreTest` and `CbrReconciliationServiceTest` (Testcontainers) validate functional correctness end-to-end. The refactor is internal — no SPI or API changes. Existing tests should pass without modification.

**New:** `QdrantFuturesTest` for the CBR copy, mirroring `io.casehub.neocortex.rag.runtime.QdrantFuturesTest` — success propagation, failure propagation, cancellation propagation, pre-completed future.

**Event-loop safety limitation:** Existing tests run on JUnit threads where blocking is harmless. They validate functional correctness but not event-loop safety — a regression reintroducing a blocking `.get()` call inside a reactive chain would not be caught. The async-canonical conversion structurally eliminates this class of bug: all gRPC calls flow through `toUni()`, leaving no `.get()` calls in reactive code paths.

## Garden entries referenced

- **GE-20260629-0a321f:** `createCollectionAsync` is NOT idempotent — race condition in `ensureCollection` pre-exists and is preserved (not addressed by this issue)
- **GE-20260714-85bd9a:** Qdrant `scrollAsync` returns unmodifiable protobuf lists — `purgeCollection` already handles this with `new ArrayList<>(scrollResult.getResultList())`

## Follow-up

- **#166:** Reactive JPA backend for CbrCaseMemoryStore — eliminates the same double-conversion pattern in `memory-cbr-jpa`
