---
layout: post
title: "Verifying Twenty-Three Findings"
date: 2026-05-23
type: phase-update
entry_type: note
subtype: diary
projects: [CaseHub]
tags: [architecture, audit, coherence]
---

The platform coherence audit I ran a few months back logged 32 cross-repo capability gaps. Six of the top eight had since been closed. The remaining 23 sat in parent#4 as descriptions — claimed, not verified against the actual code, with no depth assessment and no issues filed.

I wanted to go through them properly. Not just confirm they still existed, but understand root cause, blast radius, and what a fix would actually involve. Each finding should produce a filed issue before we moved on.

We organised them into eleven batches, theme-grouped within the priority order. Verification meant reading the actual source — not trusting the audit's description. That discipline caught three findings that were stale or misdescribed.

The most entertaining: finding 26 claimed the A2A task endpoint was in `claudony` and should move to `casehub-engine`. When we checked, the endpoint is in `quarkus-qhorus`, uses only Qhorus abstractions (CommitmentState, Channel, MessageService), and is correctly placed. Moving it to engine would create a hard dependency in the wrong direction. The audit was wrong about the repo and wrong about the fix. We filed what was actually missing — a CaseInstance-level A2A endpoint doesn't exist anywhere — as engine#340.

Finding 19 was also stale. Three "independent deadline enforcement implementations" turns out to be three schedulers enforcing completely different things: WorkItem SLA deadlines, case execution budgets, and Qhorus agent obligation monitoring. Unifying them would be over-abstraction. Nothing to file.

Finding 5 was supposed to need a verification test. When we read the code, the test already existed — `LedgerTraceListenerIT.traceId_nullWhenNoActiveSpan()` — and it proved the bug. `CaseLedgerEventCapture` is `@ObservesAsync`, which dispatches to a managed thread pool. OTel span context is thread-local. It doesn't propagate across the async boundary. Every `CaseLedgerEntry` record has a null `traceId`, silently, always. The fix is one line: capture the trace ID on the synchronous thread before firing the event. Filed as engine#342, and submitted to the garden as GE-20260523-bd68ba.

The finding that surprised me most was 30. The audit said trust thresholds aren't enforced when Qhorus creates Commitments. When we read the code, `TrustGateService` — a purpose-built trust gate with `meetsThreshold(actorId, minTrust)` — already exists in casehub-ledger, which qhorus imports directly. It's injectable. Nobody calls it. The infrastructure to enforce the threshold was sitting in the CDI context the whole time.

Twenty-two of the twenty-three findings confirmed. One outright stale (finding 19). Finding 26 required a corrected framing. Twenty-two issues filed across eight repos, three new ones filed for gaps the audit's original description missed. The analysis doc is at `docs/audit/2026-05-23-coherence-analysis.md`.

The structural themes from the original audit held up: the normative/evaluative disconnect, the notification silo, the broken causal chain. Some have been partially addressed since the audit ran. The medium-priority work — batches M1 through M8 — is now tracked and actionable.
