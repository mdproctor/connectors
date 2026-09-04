---
layout: post
title: "SWF doesn't have a human"
date: 2026-05-15
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-work, casehub-engine]
tags: [engine, adapter, human-task, serverless-workflow, standards]
---

The previous entry covered the HumanTask binding design — the sealed
`BindingTarget`, the outbound `HumanTaskScheduleHandler`, the `outputMapping`
round-trip. Claude had built the implementation in a separate session. We
verified it this morning: 861 tests across four modules, all green. I closed
the engine issue and started asking a question I'd been putting off.

Does any of this map to something standardised?

## The spec that forgot humans

CNCF Serverless Workflow 1.0 — what Quarkus-Flow implements — has twelve task
types: `call`, `composite`, `emit`, `for`, `listen`, `raise`, `run`, `set`,
`switch`, `try`, `wait`, and `extension`. Not one of them is a human task.

Human-in-the-loop in SWF is modelled as an event pair: emit to signal work is
needed, listen for the response event. The newsletter drafter example in
Quarkus-Flow does exactly this — emits `draftReady`, listens for `review.done`.
A human reviews somewhere in between, fires a CloudEvent, the workflow continues.

CaseHub's current Quarkus-Flow bridge does something different. It wraps WorkItem
creation in a `function` task that returns a `Uni` — the workflow suspends waiting
for a future. It works, but it's not the SWF idiom. It's Quarkus-specific, not
portable. There's an issue open to add the `emit+listen` approach as an
alternative path.

## The standard that might have answered this

There is a companion spec called Open Human Task — OHT — designed to fill exactly
this gap. Lifecycle states, assignment rules, form schemas, deadlines with
escalation actions. On paper, it's what you'd design if you wanted a human task
standard to sit alongside SWF.

In practice, it isn't taking off. I looked at what it defines and compared it
against what CaseHub WorkItem already has. The overlap is substantial: candidate
groups, direct assignment, claim deadlines, business-hours SLA, multi-instance
parallel completion, notifications, delegation. CaseHub does most of what OHT
specifies, and several things OHT doesn't — REJECTED as a distinct lifecycle state,
EXPIRED with business-hours awareness, AI semantic routing, an audit trail.

Three OHT ideas are worth lifting regardless:

**Named outcomes.** OHT gives tasks typed result classifications — `approved`,
`rejected`, `needs-revision` — rather than a free-form resolution string. This
matters for the engine: `outputMapping` can switch on `outcome` without parsing
JSON content. A `WorkItemTemplate` with a declared outcome list, and
`WorkItemService.complete()` accepting an outcome name, would make the resolution
machine-readable without guessing at the schema.

**Schema-validated output.** OHT validates output against a JsonSchema defined on
the task definition. CaseHub's `resolution` is untyped. Pairing named outcomes
with an output schema lets any consumer validate what comes back without
side-channel knowledge.

**Excluded owners.** Explicit conflict-of-interest enforcement — the person who
initiated the task can't also approve it. Useful in approval and compliance
workflows.

OHT took years to produce something CaseHub already mostly has. The gaps are worth
closing on our terms rather than waiting for a standard that may never arrive.

## A small thing about proxies

The last piece of this session was code cleanup — clarifying the `@Transactional`
annotation on the 5-arg `instantiate()` overload that the previous session's code
review flagged. While doing that, we found the proxy bypass gotcha has an exception
worth knowing.

Adding `@BeforeEach @Transactional` to a `@QuarkusTest` class — you'd expect it
not to work, because `@Transactional` on internal `this`-calls is silently ignored.
But Quarkus registers `@QuarkusTest` instances as CDI beans and gives JUnit the
proxy, not the raw instance. JUnit calls `@BeforeEach` through the proxy.
`@Transactional` fires. The gotcha has a counterexample, and it matters precisely
when you've already been burned by the gotcha and start avoiding `@Transactional`
on lifecycle methods altogether.
