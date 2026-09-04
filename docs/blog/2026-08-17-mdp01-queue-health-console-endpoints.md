---
layout: post
title: "Queue health: giving the console what it needs"
date: 2026-08-17
entry_type: note
subtype: diary
projects: [casehub-work]
tags: [queues, rest-api, console-ui]
---

The scaffold console's Queues tab was pointing at two endpoints that didn't exist: a `GET /queues/health` for the KPI metric row, and an enriched `GET /queues` that includes per-queue counts. The per-queue plumbing was already there — `GET /queues/{id}/summary` returns a cached `WorkItemSummary` with status counts, priority breakdown, overdue, and claim deadline breaches. The gap was aggregation.

The interesting design choice was how to enrich the list response. The UI expected flat fields — `pendingCount`, `activeCount`, `completedCount`. I went with nesting the full `WorkItemSummary` instead. The UI already consumes that shape from the per-queue summary endpoint, so no second DTO needed. More importantly, flattening would have discarded `overdue`, `claimDeadlineBreached`, `byPriority`, and `oldestCreatedAt` — all things the console will want for tooltips and column sorting within a release or two. Adding them later means another API change. The full summary is six fields. Not worth optimising away.

The health endpoint aggregates those per-queue summaries into five KPI metrics: total, pending, active, overdue, and claim SLA breaches. Each gets a status — `critical` for overdue or breaches, `warning` for pending items, `neutral` otherwise. The aggregation iterates all tenant queues and calls `summarize()` for each, which hits the cache. For a tenant with 20 queues that's 20 in-memory map lookups, not 20 database queries.

One thing worth calling out: a WorkItem can appear in multiple queues if label patterns overlap. The health endpoint counts per-queue, not unique WorkItems — so the same item in three queues gets counted three times in the total. This is intentional. Each queue's load matters independently. If you want unique counts, that's a different endpoint with different semantics.

Both changes sit in `casehub-work-queues` — no new modules, no migrations, no caching changes. The building blocks were already there; this was assembly.
