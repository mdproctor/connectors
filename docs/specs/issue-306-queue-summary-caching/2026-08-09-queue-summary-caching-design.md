# Queue Summary Caching Design

**Issue:** casehubio/work#306
**Epic:** casehubio/work#330 (Queue summary performance — #305 SQL push-down done)
**Date:** 2026-08-09
**Revised:** 2026-08-09 (post design review — coherence, structure, robustness, cross-cutting)

## Problem

The `GET /queues/{id}/summary` endpoint hits the database on every request. The SQL push-down (#305) made individual queries fast, but concurrent dashboard users still generate redundant identical queries. A caching layer deduplicates concurrent requests within invalidation windows, reducing database load proportional to the number of concurrent dashboard users.

## Approach: Quarkus Cache with Event-Driven Eviction

TTL-based Caffeine cache (via `quarkus-cache` extension) on `QueueMembershipService.summarize()`, with event-driven "evict all" on WorkItem lifecycle changes.

### Why this integration point

`QueueMembershipService.summarize()` is the single entry point for both:
- **SQL push-down path:** `viewQuery.summarizeByView()` → `SummaryQueryBuilder.build()` (no `additionalConditions`)
- **Entity-loading fallback:** `WorkItemSummaryBuilder.build(evaluateMembers())` (with `additionalConditions`)

Caching here covers both paths without duplication.

### Cache key

`queueViewId (UUID) + tenancyId (String)`. Both extracted from `SubjectViewSpec` by a `CacheKeyGenerator` — the domain method signature does not change for caching infrastructure.

`tenancyId` in the key prevents cross-tenant leakage — mandatory for any multi-tenant cache.

`Instant now` is NOT part of the key. It changes on every call and would defeat caching. The trade-off: `overdue` and `claimDeadlineBreached` counts (which depend on `now`) may be stale by up to the TTL window. For a 5-second default TTL, this is acceptable.

### Cache configuration

```properties
quarkus.cache.caffeine."queue-summary".expire-after-write=5S
```

Operator-tunable via `application.properties`. 5 seconds as the default — short enough to feel responsive on dashboards, long enough to absorb concurrent polls.

### Invalidation

A method annotated `@CacheInvalidateAll(cacheName = "queue-summary")` observed with `@Observes(during = TransactionPhase.AFTER_SUCCESS)` on `WorkItemLifecycleEvent`. This solves two problems simultaneously:
- **Post-commit ordering:** `AFTER_SUCCESS` ensures the eviction fires after the transaction commits, not before — preventing a race where a concurrent request populates the cache with pre-commit data.
- **Uni subscription:** `@CacheInvalidateAll` handles `Uni<Void>` subscription internally. The programmatic `Cache.invalidateAll()` returns a lazy `Uni<Void>` that does nothing if not subscribed — using the annotation avoids this trap.

Evicts ALL cached summaries on any WorkItem lifecycle event. This is deliberately simple:
- No reverse-mapping needed (WorkItem → which queues contain it?)
- Caffeine's key-level loading deduplicates concurrent same-key requests — N concurrent requests for the same queue result in 1 database query, even if a previous eviction just fired
- The event listener infrastructure is in place for future per-queue eviction refinement

**Cache effectiveness under load:** Under continuous mutation load (≥1 mutation/second), the cache deduplicates concurrent requests within each invalidation window but does not deduplicate across windows. With 50 queues polled every 2 seconds by 10 dashboard users (500 requests/cycle), roughly half hit the cache during typical mutation rates — a meaningful reduction, not total elimination.

### Known limitations

**Queue view definition changes:** When a queue's label pattern or `additionalConditions` change, the cached summary becomes stale for up to the TTL window. `SubjectViewOrchestrator.saveView()` lives in the platform-view library and does not fire a CDI event reachable by the queues module. Introducing a `SubjectViewChangeEvent` is a platform-level change out of scope for this issue. The 5-second TTL bounds the staleness window — acceptable for configuration changes that happen rarely.

**Single-node cache:** Caffeine is in-process only. In multi-instance deployments, a `WorkItemLifecycleEvent` on instance A evicts A's cache but not B's. Instance B serves stale data until its TTL expires. Cross-instance staleness is bounded by TTL (5 seconds) and acceptable for this use case. A distributed cache (e.g., Infinispan) would be needed if cross-instance consistency becomes a requirement.

**Cross-tenant blast radius:** `@CacheInvalidateAll` evicts all tenants' summaries when one tenant's WorkItem changes. Not harmful (just wasteful), acceptable for the initial implementation. Per-tenant eviction is a future refinement — requires sharing key composition between the `CacheKeyGenerator` and the eviction observer via a shared utility (e.g., `QueueSummaryCacheKeys`).

### Changes

#### 1. queues/pom.xml

Add Quarkus Cache dependency:
```xml
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-cache</artifactId>
</dependency>
```

Managed by the Quarkus BOM — no version needed.

#### 2. QueueSummaryCacheKeyGenerator

New class implementing `io.quarkus.cache.CacheKeyGenerator`. Extracts `queueViewId` and `tenancyId` from the `SubjectViewSpec` parameter to compose the cache key. The domain method signature stays unchanged — no `@CacheKey` annotations leak into the API.

```java
@ApplicationScoped
public class QueueSummaryCacheKeyGenerator implements CacheKeyGenerator {
    @Override
    public Object generate(Method method, Object... params) {
        SubjectViewSpec spec = (SubjectViewSpec) params[0];
        return new CompositeCacheKey(spec.id(), spec.tenancyId());
    }
}
```

#### 3. QueueMembershipService

Add `@CacheResult` with the key generator to `summarize()`:

```java
@CacheResult(cacheName = "queue-summary", keyGenerator = QueueSummaryCacheKeyGenerator.class)
public WorkItemSummary summarize(final SubjectViewSpec queue, final Instant now) {
    // existing logic unchanged — method signature preserved
}
```

Add event-driven invalidation:

```java
@CacheInvalidateAll(cacheName = "queue-summary")
void onWorkItemLifecycle(@Observes(during = TransactionPhase.AFTER_SUCCESS) WorkItemLifecycleEvent event) {
    // annotation handles eviction — method body empty
}
```

#### 4. QueueResource

No changes — `summarize(spec, Instant.now())` call unchanged.

#### 5. application.properties (queues module)

Add default TTL:
```properties
quarkus.cache.caffeine."queue-summary".expire-after-write=5S
```

### Scope

- **In scope:** Queue summary caching (`GET /queues/{id}/summary`)
- **Out of scope:** Inbox summary caching (`GET /workitems/inbox/summary`) — different cache key semantics (per-user query predicates), separate issue if needed

### Testing

Three tests (no TTL test — Caffeine owns that contract):

**a. Cache hit** — call `summarize()` twice with the same queue view. Between calls, use `CacheManager` to verify the cache contains an entry for the queue view key. Assert the second call returns the same `WorkItemSummary` object (identity — `assertThat(second).isSameAs(first)`). Do NOT mutate a WorkItem between calls — that fires `WorkItemLifecycleEvent` and evicts the cache.

**b. Event-driven eviction** — seed the cache with a `summarize()` call. Insert a new WorkItem into the queue (triggers lifecycle event + `AFTER_SUCCESS` eviction). Call `summarize()` again. Assert the result reflects the new WorkItem (cache was evicted, fresh query ran).

**c. Cross-tenant isolation** — populate cache for tenant A's queue. Call `summarize()` for tenant B's queue with the same view ID. Assert tenant B gets its own result, not tenant A's cached data.

All tests use stub-swap assertions (verify the returned data), not call-count assertions (per garden GE-20260531).
