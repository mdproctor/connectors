---
layout: post
title: "The filter that grew into a contract"
date: 2026-04-21
type: phase-update
entry_type: note
subtype: diary
projects: [quarkus-work]
tags: [architecture, ai-native, routing, casehub, filter-registry]
excerpt: "A confidence-gated routing rule that could be wired in twenty lines becomes quarkus-work-filter-registry instead — because inline logic is a trap that becomes load-bearing and unremovable."
---

Three epics shipped before the interesting thing happened.

Form schemas (#98) — a JSON Schema definition per WorkItem category, stored as TEXT,
validated on creation and completion via networknt. The audit history API (#99) — cross-WorkItem
queries with actorId/event/date filters, an SLA breach report, actor performance summaries.
Both were features I'd planned, predictable to build, and are now behind us. The test count
went from 446 to 497 during that stretch.

Then Epic #100 started, and the first feature — confidence-gated routing — refused to stay small.

## The filter that grew into a contract

I wanted: when an AI agent creates a WorkItem with `confidenceScore < 0.7`, apply the
label `ai/low-confidence`. Simple enough. The label mechanism already existed. I could
have wired it directly in `WorkItemService.create()` in twenty lines.

We didn't do that. We spent most of the session building `quarkus-work-filter-registry`
instead.

The reasoning: confidence-gating is a routing decision, and routing decisions should be
composable, not hardcoded. The queues module already had a filter engine (JEXL expressions,
label application); the AI module needed something similar but more general — arbitrary
actions beyond labels. It made sense to build one pluggable engine instead of two ad-hoc ones.

So the design became: a `FilterAction` SPI resolved by CDI bean name, a `FilterRegistryEngine`
that observes `WorkItemLifecycleEvent` and evaluates JEXL conditions against a WorkItem,
permanent (CDI-produced) and dynamic (DB-persisted) filter rules, and three built-in actions:
`APPLY_LABEL`, `OVERRIDE_CANDIDATE_GROUPS`, `SET_PRIORITY`. The `quarkus-work-ai` module
then just produces one `FilterDefinition` via CDI — the low-confidence filter is four lines.

That architecture is what I wanted. But the design conversation that followed it is what made
the session unusual.

## The shared contract problem

WorkerSelectionStrategy — the second Epic #100 feature — is also in CaseHub's design:
`WorkerSelectionStrategy.select(request, availableWorkers)` in casehub-engine. Two systems
were independently inventing the same SPI.

Rather than let them drift, I decided to create `quarkus-work-api` — a pure-Java module
with zero runtime dependencies containing only the shared types: `WorkerCandidate`,
`SelectionContext`, `AssignmentDecision`, `AssignmentTrigger`, `WorkerSelectionStrategy`,
`WorkerRegistry`. CaseHub can add a compile-scope dependency on this module without pulling
in Panache, Flyway, or a single Quarkus extension.

The naming needed alignment too. What I'd been calling `WorkItemRouter` is `WorkerSelectionStrategy`
in CaseHub. `GroupMemberResolver` maps to CaseHub's `WorkerRegistry`. `RoutingDecision` becomes
`AssignmentDecision`. We wrote a CaseHub alignment issue (#123 in casehubio/engine) with the
exact steps to adopt the shared API.

## What we actually built for routing

`LeastLoadedStrategy` counts active WorkItems (ASSIGNED + IN_PROGRESS + SUSPENDED — not PENDING)
per candidate and pre-assigns to the lightest. `ClaimFirstStrategy` is the no-op. Both get
selected by `quarkus.work.routing.strategy` config; a CDI `@Alternative` overrides both.

`WorkItemAssignmentService` wires into `create()`, `release()`, and `delegate()`. The delegate
case had a subtle bug: the strategy must evaluate before `item.assigneeId` is cleared, or
Hibernate's auto-flush drops the delegator's active items from the DB before the count query
runs. Claude caught this by watching the test failures — "strategy fires before mutation" is
now in the code and the garden.

One other thing worth noting: `JpaWorkItemStore.scan()` with an `assigneeId` filter generates
`OR candidateUsers LIKE '%actorId%'`. For inbox queries that's correct. For workload counting
it over-counts — the fix is a post-filter in memory. Both are garden entries now.

The session ended at 525 runtime tests, 0 failures, with `quarkus-work-api` available as
a standalone artifact and a CaseHub issue filed to adopt it.
