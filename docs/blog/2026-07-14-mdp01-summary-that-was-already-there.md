---
layout: post
title: "The summary that was already there"
date: 2026-07-14
type: phase-update
entry_type: note
subtype: diary
projects: [CaseHub Work]
tags: [queues, api, refactoring]
---

## The summary that was already there

I needed a `GET /queues/{id}/summary` endpoint — pre-computed aggregates so the
blocks-ui dashboard doesn't have to pull the full item list and count things
client-side. Total items, priority breakdown, SLA breach counts, oldest item age.

When I looked at what we already had, the answer was sitting in `InboxSummaryBuilder`.
A pure static function on `List<WorkItem>` that computes exactly the metrics I needed:
total, byStatus, byPriority, overdue, claimDeadlineBreached. It was named after its
first consumer, not what it actually does. Nothing about it was inbox-specific — it
takes a list, returns counts.

So the real work was a rename and one new field. `InboxSummaryBuilder` became
`WorkItemSummaryBuilder`, `InboxSummary` became `Summary` (nested record — Claude's
design review caught a name collision with `ActorStateResponse.WorkItemSummary` in
the actor-state module, which has completely different semantics). I added
`oldestCreatedAt` — the `min(createdAt)` across non-terminal items, returned as a raw
instant so the client can compute the age without response-time staleness. Follows the
existing `QueueHealthReport.oldestUnclaimedCreatedAt` convention.

The queue summary endpoint itself is five lines. Look up the queue view, evaluate
members, pass the list to `WorkItemSummaryBuilder.build()`, return the result.

```java
final var members = membershipService.evaluateMembers(q);
return Response.ok(WorkItemSummaryBuilder.build(members, Instant.now())).build();
```

The endpoint still loads all matching WorkItems into memory — same as `GET /queues/{id}`
does today. What it eliminates is serialising and shipping all those entities over the
wire. For a 5,000-item queue, the client gets six numbers instead of 5,000 JSON objects.
Database-level aggregation to avoid the server-side load is a separate concern, tracked
in #305.

The interesting part wasn't the endpoint — it was recognising that the aggregation logic
already existed under the wrong name.
