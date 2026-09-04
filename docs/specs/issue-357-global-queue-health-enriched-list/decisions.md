## D1: Enriched list response shape

**Choice:** Nest the full `WorkItemSummary` under a `summary` field on each queue entry in the list response
**Alternatives:**
- Flatten to explicit fields (`pendingCount`, `activeCount`, `completedCount`) — simpler but loses `overdue`, `claimDeadlineBreached`, `byPriority`, `oldestCreatedAt`; future fields require API changes
**Rationale:** UI already consumes `WorkItemSummary` from `/queues/{id}/summary`. Same shape avoids a second DTO. Summary is 6 fields — negligible response size increase. Already cached per queue.
**Trade-offs:** Slightly larger list response payload than flat fields. Acceptable for typical queue counts (<50).
**Sources:** `QueueResource.java:61` (current list), `WorkItemSummary.java`, `QueueMembershipService.java:33` (cached summarize)
**Exploration:** quick
**Status:** captured

## D2: Health endpoint aggregation strategy

**Choice:** Aggregate from per-queue cached summaries — iterate tenant queues, call `summarize()` for each (cache hits), sum totals into KPI array
**Alternatives:**
- Direct JPQL aggregate query — single query over all WorkItems, but counts all WorkItems (not just queued ones), duplicates counting logic from `WorkItemSummaryBuilder`, and reimplements overdue/breach thresholds
**Rationale:** Health endpoint is about queue health — items *in* queues, not all WorkItems. Per-queue summaries are already cached (`@CacheResult`), so aggregation is in-memory. Reuses existing `summarize()` without duplicating logic.
**Trade-offs:** 100 queues = 100 cache lookups. Acceptable — in-memory map access, not DB round-trips. If this becomes a bottleneck, a dedicated aggregate cache can be added later.
**Sources:** `QueueMembershipService.java:33` (cached summarize), `WorkItemSummary.java`, issue #357 body (KPI format)
**Exploration:** quick
**Depends on:** D1 (same WorkItemSummary shape feeds both list enrichment and health aggregation)
**Status:** captured
