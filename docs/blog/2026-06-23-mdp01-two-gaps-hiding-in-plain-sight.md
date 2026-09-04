---
layout: post
title: "Two gaps hiding in plain sight"
date: 2026-06-23
type: phase-update
entry_type: note
subtype: diary
projects: [casehub]
tags: [documentation, api-design, issue-management]
---

The branch started as routine maintenance. Four docs issues, all small — update some type names, remove a stale deferred note, update the neural-text section, register a new repo in the build dashboards. Nothing that should surprise anyone.

It didn't quite work out that way.

## The names the API outgrew

When `casehub-worker` was being designed, PLATFORM.md was written with the working names: `WorkerContext`, `WorkerSpec`, `WorkerCapability`. These names made sense at the time — they described what the types were conceptually before the API crystallised.

The API shipped as `Worker`, `Capability`, `WorkerFunction`, `WorkerResult`, `WorkerOutcome`, `PlannedAction`. The repo is live, the code is published, downstream consumers will depend on these names. But PLATFORM.md still said `WorkerContext` and friends — five occurrences across the capability ownership table, the build order diagram, the cross-dependency table, and the coherence protocol.

Nobody caught it because the names are close enough to be meaningful. A reader skimming PLATFORM.md would assume `WorkerContext` is roughly what they're looking for. It's only when you go to wire it up and look at the actual API that you find the names don't exist.

The detection only happened because we were updating the same section for a different reason.

## A promise buried in an epic

The second surprise came when closing #277 — the CloudEvent infrastructure rollout epic. All five sub-issues were closed. Straightforward.

Before closing, I read the epic body. One line: *"casehub-ras consumes `@ObservesAsync CloudEvent` but requires its own issue and design."* I checked the casehub-ras issues. No such issue existed.

The pattern is familiar: someone writing the epic description documents the known follow-on inline rather than filing it. At epic-close time, the sub-issues are checked, all green, the epic closes — and the promised issue is gone. It only survives if someone reads the description carefully rather than scanning the checklist.

We filed casehub-ras#11 and closed #277 referencing it.

The type name drift is a specific instance of a broader problem — docs written during design diverge from docs written after it. When API terminology evolves, the pre-finalisation docs don't update automatically. For a long-lived platform document like PLATFORM.md that accumulates sections over months, this divergence is nearly guaranteed unless someone audits the name vocabulary after each API release. The routine batch is when that audit happens by accident.