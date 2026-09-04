---
layout: post
title: "The primitive and the orchestrator"
date: 2026-04-24
type: phase-update
entry_type: note
subtype: diary
projects: [quarkus-work]
tags: [architecture, casehub, subprocess-spawning, layering, ai-agents]
excerpt: "A data-driven spawn rule table is rejected before a line of code — the right answer is that subprocess orchestration belongs to CaseHub, and quarkus-work should expose the primitive that CaseHub reaches for."
---

Claude's first spec for subprocess spawning had a data-driven rule table: store
spawn rules in the database, fire them as WorkItems transition states, complete
the parent when children finish. Runtime-configurable, no Quarkus-Flow needed.

I asked directly: "Why are you building something that looks like a workflow
engine?" Claude's answer was circular dependencies and Quarkus-Flow being
optional. My response: "We kept Quarkus-Flow optional isn't a justification for
building another workflow engine."

Then I pushed again: CaseHub already handles orchestration and choreography. Why
are we building any of this in quarkus-work at all?

## What happens when the context window doesn't span your whole system

Claude had lost the thread. CaseHub lives in a different project — no context
carries across — and without that context, Claude was narrowing its focus to the
immediate problem. The result was a design that was quietly rebuilding case
management primitives inside quarkus-work without recognising that's what it was
doing. Classic duplication, generated not from ignorance of the concept but from
ignorance of the existing implementation sitting in a neighbouring repository.

I pulled up two old KIE blog posts on flexible processes in Kogito: ad-hoc
subprocesses, activation conditions, milestones. The point wasn't nostalgia — it
was to give Claude the mental model of what CaseHub is already designed to do.
If spawn rules are runtime-configurable and children can have activation
conditions, that's CMMN. We built that model before in Kogito. CaseHub is the
right home for it now.

Once Claude had that reference, the layering clicked. But then came a second
version of the same problem.

I asked Claude to review CaseHub before continuing the design — but didn't
specify the path. Claude found an older, incomplete POC in a different directory
and wrote the entire spec against that. The reviews came back confident and
detailed. Wrong source.

I didn't catch it immediately. When I did, we had to discard the spec work and
start the CaseHub review again — this time pointing Claude explicitly at
`~/dev/casehub-engine`, the real engine. The spec that came back was materially
different.

Two incidents, same root cause: Claude narrows to what's visible in its context,
fills the gaps with plausible-looking assumptions, and doesn't surface what it
doesn't know. The work looks right until you check the premise.

## The boundary rule

quarkus-work fires events and provides primitives. Every "what happens next"
decision lives above it.

The test is simple: if code in quarkus-work says "when X happens, do Y to
another WorkItem" — that's orchestration, it doesn't belong. If it says "when
X happens to this WorkItem, update this WorkItem's own state" — that's lifecycle
management, it belongs.

Which meant the template-driven auto-spawn, the completion rollup service, and
the parent auto-complete were all cut. Not deferred — not in quarkus-work at all.
A standalone application that wants those behaviours writes a ten-line CDI
observer.

We wrote `docs/architecture/LAYERING.md` to fix the boundary in place. The
more useful outcome is that it now exists as explicit context to hand Claude at
the start of any future session touching this layer — rather than hoping the
reasoning survives.

## callerRef

One real design decision in the spawn primitive: how does CaseHub route a
child's completion back to the right PlanItem without a query?

The obvious answer was `correlationKey`. But that term is already taken in
casehub-engine — it's the SHA-256 hash of `workerName:capabilityName:inputDataHash`,
used by `PendingWorkRegistry` for worker-execution deduplication. Using the same
word for two different concepts across two projects would have been quietly
confusing for anyone reading across both codebases. Claude caught the collision
when reviewing the spec against the real engine source.

The final field is `callerRef` — an opaque string that quarkus-work stores and
echoes in every lifecycle event, never interprets. CaseHub embeds its `planItemId`
there. The receiving adapter routes completion without a lookup.

## Filter engine cleanup

One structural consequence of getting the layers right: `FilterRule` — a
`@Entity` — had been sitting in `quarkus-work-core`, which CaseHub depends on
for `WorkBroker`. A Hibernate entity in a generic routing library means any
module depending on it without a datasource gets a deployment failure at
`@QuarkusTest` time. We moved the filter engine to `runtime/`. quarkus-work-core
is now pure CDI and quarkus-work-api types — something CaseHub can depend on
without pulling in a datasource requirement.

The rest was implementation: Flyway migrations for `caller_ref` and
`work_item_spawn_group`, spawn service and REST endpoints, ledger wiring so
spawned children's CREATED entries carry `causedByEntryId` pointing back to
the parent's SPAWNED entry.
