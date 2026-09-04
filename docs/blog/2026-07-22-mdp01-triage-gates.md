---
layout: post
title: "Triage gates — when scoring isn't enough"
date: 2026-07-22
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-aml]
tags: [triage, cbr, regulatory-compliance, investigation]
---

The investigation triage worker was a stub — every case got `SAR_WARRANTED` regardless
of what the specialist findings said. That was fine for wiring the engine layers together,
but it meant the `investigation-cleared` goal never fired. Every transaction went through
SAR drafting, compliance review, the full path. The FALSE_POSITIVE and INCONCLUSIVE
outcomes existed in the enum but had no way to surface.

## The shape of the problem

Triage sits at a junction. Entity resolution, pattern analysis, and OSINT screening have
all completed. CBR path advice may or may not be present. The triage worker looks at
everything and decides: file a SAR, clear the transaction, or flag it as ambiguous.

I started with the obvious question — should this be one scoring function or something
more structured? The regulatory context answers that. A sanctions hit on the OFAC/SDN
list isn't a "high score" — it's an absolute. You file. There is no threshold that makes
a sanctions match acceptable. Same for a confirmed PEP with an OSINT PEP hit, same for
a shell company entity type.

So the design splits into two layers: hard gates that fire unconditionally, and a
weighted scoring function for everything else. The gates check first. If none fire,
the scorer accumulates a risk score from five factors — entity risk, structuring
detection, PEP entity type, OSINT decline (uncertainty, not evidence), and PEP hit.
Thresholds determine the outcome.

## CBR as threshold adjustment

The CBR path advisor produces a `predominantOutcome` from similar past cases — "80% of
cases like this were FALSE_POSITIVE." The question was how much influence to give it.

Threshold adjustment felt right. CBR shifts where the SAR and FALSE_POSITIVE boundaries
sit, not the score itself. If history says cases like this are usually false positives,
the bar for filing a SAR goes up. If history says they usually warrant a SAR, it goes
down. Hard gates ignore CBR entirely — sanctions are sanctions regardless of what
happened before.

The adjustment is capped at ±0.15 and gated on confidence. Low-confidence CBR advice
(few similar cases, low similarity) has no effect. An ordering invariant prevents the
SAR threshold from crossing below the FALSE_POSITIVE threshold even under extreme
adjustment.

## The INCONCLUSIVE gate

The adversarial design review caught something I'd missed. SAR_WARRANTED has a downstream
gate — the SAR_FILING PlannedAction requires MLRO approval before the SAR is submitted.
FALSE_POSITIVE is a positive analytical conclusion — the evidence says no SAR is needed.
But INCONCLUSIVE means the evidence is ambiguous. Regulatory frameworks expect human
review before clearing a case on ambiguous evidence.

Claude's reviewer argued that INCONCLUSIVE should declare its own PlannedAction —
`INVESTIGATION_CLEARANCE` — so a compliance officer explicitly approves the clearance.
If they reject it, the case stays open with no completion path. That's the correct
regulatory posture: an inconclusive case that a compliance officer refuses to clear
must not auto-resolve.

This required switching the triage worker from `FlowWorkerFunction` to
`WorkerFunction.Sync`, since PlannedAction support isn't available in the flow
executor yet.

## Typed inputs

While building the triage logic, the raw `Map<String, Object>` extraction pattern
across all workers became hard to justify. The domain records already exist —
`EntityResolutionResult`, `PatternAnalysisResult`, `OsintResult`. Every worker that
reads specialist findings was picking through maps with string keys and unchecked
casts. `OsintResult` didn't even have a `declined` field — it was silently dropped
on deserialization.

We retrofitted typed inputs across the board. `OsintResult` gained `declined` and
`reason` fields with a compact constructor enforcing mutual exclusivity (a declined
screening can't report hits). `EntityResolutionResult` got risk score bounds
validation. The triage evaluator works with `TriageInput` — a typed aggregate of all
four specialist findings.

The thresholds are externalized through platform preferences, following the same
`PreferenceKey<DoublePreference>` pattern that `AmlMemoryPolicyKeys` established for
the memory lookback window.
