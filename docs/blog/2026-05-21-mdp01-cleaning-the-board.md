---
layout: post
title: "Cleaning the Board"
date: 2026-05-21
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-parent, cc-praxis]
tags: [protocols, sla, path-detection, workspace]
---

Two sessions of maintenance work. Not glamorous, but the kind of day that leaves
the board genuinely cleaner than it started.

## Three protocols, two closed

Parent#30 was the most straightforward: extract the `DeviationLedgerWriter` pattern
from casehub-clinical into a formal protocol so other harnesses know to follow it.
One `@ApplicationScoped` writer bean that owns `sequenceNumber` computation for a
given `LedgerEntry` subtype. The reasoning is simple — if two services independently
call `findLatestBySubjectId()` before either has flushed, they can compute the same
sequence number. The fix is a shared owner. The pattern already existed in clinical#14;
we just made it a rule.

Parent#11 was more interesting. The issue asked for a protocol ensuring all strategy
SPIs pass `caseId` through their method signatures so a future `PerCaseDynamicStrategy`
can dispatch per-case. We wrote the protocol. Then we looked at the actual code and
found every SPI on the list was already compliant — `CaseWorkerUpdateStrategy`,
`CaseChannelLayout`, `WorkerContextProvider` all pass `caseId` explicitly; `WorkerProvisioner`
and `MeshParticipationStrategy` carry it via context objects. The protocol updated to
say "already done" rather than "verify." That's the best kind of close.

The `PerCaseDynamicStrategy` discussion itself was worth having. The issue was motivated
by wanting different SSE update strategies for different case priorities. Once we understood
that — it's a UI panel update mechanism — the appetite for building `CaseStrategyRegistry`
and a fleet-wide replication protocol to vary a worker panel refresh rate deflated quickly.
Filed as "not now," which is different from filed as "planned."

## SLA propagation — or most of it

Parent#6: case budget deadlines propagate to `WorkItem.expiresAt`. We found the integration
point in `WorkerScheduleEventHandler.dispatchCommand()` — it already embeds a `correlationId`
in the COMMAND message content. The engine side was one change: add `caseBudgetDeadline` to
`HumanTaskScheduleEvent`, extract it from `PropagationContext` in the publisher, apply
`min(taskDeadline, caseBudgetDeadline)` in the handler.

The claudony side was more interesting than expected. Claude traced through `postToChannel()`
into `ReactiveMessageService.send()` and found that Qhorus does create Commitments — but only
when `correlationId` is non-null. Claudony always passes null. No Commitments are ever opened
for any engine-dispatched COMMAND. The deadline bounding is moot until claudony starts passing
correlationIds, and claudony won't do that until someone builds the feature. Two issues filed
in engine and claudony to complete the chain when that session happens.

The engine work landed on `casehubio/engine` main. The implementation also fixed a pre-existing
build break: `HumanTaskScheduleHandler.createInline()` was using a positional `WorkItemCreateRequest`
constructor that no longer exists in the current work module jar. The tests had been passing
against stale class files.

## The path detection problem — and its surprisingly complete solution

Parent#34 was what I expected to be the largest item. Repos weren't writing to DESIGN.md. The
diagnosis: all the workspace-aware skills (`java-update-design`, `work-start`, `work-end`, etc.)
were detecting workspace and project paths by grepping `**Workspace:**` from CLAUDE.md. Per-repo
workspace CLAUDE.md files are symlinked from the project repo and carry no such field — so the
extraction returned empty strings silently.

We dispatched an agent to do a full audit. The agent concluded the `proj/` symlink detection
was dead code because it couldn't find `proj/` symlinks in the project directories. That was
wrong. The symlinks exist in the *workspace* directories (`~/claude/public/casehub/engine/proj`),
not the project directories — exactly where skills would look for them when running with CWD
as the workspace. The infrastructure was complete. The skills just weren't using it.

One fix, propagated through seven skills: replace every `grep "**Workspace:**" CLAUDE.md` with
`readlink -f proj`. Two lines:

```bash
WORKSPACE=$(git rev-parse --show-toplevel 2>/dev/null)
PROJECT=$(readlink -f proj 2>/dev/null)
```

All thirteen repos had both `proj/` and `wksp/` symlinks already in place. The only missing
ones were platform (no `wksp/`) and cccli (no `proj/` or `wksp/`). Added. The canonical block
is now documented in cc-praxis CLAUDE.md with a verification grep so drift is catchable.

## What this unlocks

DESIGN.md updates should start flowing again in all casehub per-repo workspace sessions.
The claudony Commitment tracking is queued behind correlationId support. The SPI caseId
protocol is documented and confirmed. The ledger writer pattern is formalised for the next
harness that needs it.
