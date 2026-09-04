---
title: The Lifecycle That Grew Up
date: 2026-06-18
author: Mark Proctor
tags: [casehub-work, lifecycle, design, WS-HumanTask, architecture]
---

WorkItemStatus started life with ten states. Enough to model a human task inbox — claim, start, complete, reject, cancel, the usual suspects. It handled delegation, suspension, expiry, and escalation. It was functional.

But functional isn't the same as correct.

The gap surfaced when I mapped every lifecycle type across the platform. Six layers deep — Serverless Workflow's `WorkflowStatus`, CaseHub's `CaseStatus`, the blackboard `PlanItemStatus`, the WorkItem, and Qhorus `CommitmentState`. Every layer except WorkItem distinguishes system failure from actor refusal. `PlanItemStatus` has FAULTED and REJECTED. `CommitmentState` has FAILED and DECLINED. WorkItem had only REJECTED — and that one word was carrying two meanings.

When an AI agent hits a context overflow, that's not a rejection. It's a fault. Calling it REJECTED corrupts trust scoring because REJECTED implies a deliberate decision. The agent made no decision — the infrastructure failed. Once you see it, you can't unsee it.

The naming question was the first real design decision. WS-HumanTask calls it ERROR. OpenHumanTask calls it error. But four layers in this platform already use FAULTED. Platform coherence won — one word, same meaning, every layer.

OBSOLETE was the second addition. Both WS-HumanTask and OpenHumanTask have it. Distinct from CANCELLED in a way that matters: CANCELLED means someone decided to stop. OBSOLETE means the case context changed and the work became irrelevant. An IRB review that's OBSOLETE because the trial was withdrawn is categorically different from one that's CANCELLED because a supervisor pulled the plug. The reviewer did nothing wrong. The termination reason is structural, not decisional.

The implementation itself was mechanical — new enum values, service methods, REST endpoints, Flyway migration, MongoDB document updates, response DTOs. Standard TDD with 33 new tests.

What wasn't mechanical was the audit. Five review rounds surfaced hardcoded status lists in six locations across four modules. The same fragility pattern that caused the original EXPIRED bug (#243) — explicit enumeration of terminal statuses that silently drops new ones. `FilterRegistryEngine`, `MultiInstanceGroupPolicy`, `ReportService` (four locations), `HumanTaskRecoveryService`, the `WorkItemLifecycleAdapter` status filter. Each one was a silent bug waiting for FAULTED or OBSOLETE to activate it.

The fix is the same everywhere: `isTerminal()` instead of `List.of(COMPLETED, REJECTED, CANCELLED, EXPIRED)`. Future terminal statuses flow through automatically. The fragility is eliminated, not patched.

The deeper finding was that DELEGATED means three incompatible things across the platform. `PlanItemStatus.DELEGATED`: control passed to external actor, non-terminal. `WorkItemStatus.DELEGATED`: pre-acceptance hold for a named actor, non-terminal. `CommitmentState.DELEGATED`: obligation transferred and discharged, terminal. Same word, opposite terminal semantics on the Qhorus side. PLATFORM.md already flagged this as a known overlap risk — the fix is javadoc cross-references, not a rename. The semantics are correct within each system.

The spec went through five review rounds. Each round found real issues — the `PlanItemFaultedEvent` parity gap, the `ActionGateCompletionApplier` needing explicit FAULTED/OBSOLETE mapping, the `cancelFromSystem()` pattern completion. By round five the reviewer confirmed every `WorkItemStatus` consumer in the codebase had been audited.

Five cross-repo issues filed for the work that lives outside casehub-work: the engine adapter changes (engine#539), the normative LIFECYCLE.md document in casehub-parent (parent#284), the Qhorus DELEGATED javadoc (qhorus#291), PlanItemStatus.SUSPENDED observability gap (engine#540), and the pre-existing MongoWorkItemDocument field sync debt (work#270).

WorkItemStatus has twelve values now. Seven terminal, five active. It closes every gap in the WS-HumanTask and OpenHumanTask alignment tables. The things casehub has beyond the specs — EXPIRED, ESCALATED, delegation chains, the SLA breach policy framework — are all still there. The lifecycle grew up without losing what made it distinctive.
