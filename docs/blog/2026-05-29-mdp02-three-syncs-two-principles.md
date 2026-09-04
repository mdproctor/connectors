---
layout: post
title: "Three syncs, two principles"
date: 2026-05-29
type: phase-update
entry_type: note
subtype: diary
projects: [CaseHub Parent]
---

## Three syncs, two principles

Three doc issues, all in the parent repo. Mechanical work — updating references that had fallen behind code changes. What makes it worth writing up is what the changes reveal about how the platform thinks about extension.

**The extraction threshold.** ADR-0008, as originally written, put memory adapters in a standalone `casehub-memory` repo. The amendment moved them into `casehub-platform` as submodules — `memory-memori/`, `memory-mem0/`, `memory-graphiti/`. The stated rationale: extract only when a confirmed non-CaseHub consumer needs them.

A standalone repo for adapters with one consumer is maintenance surface that adds nothing. The cost of extraction is immediate — versioning, coordination, an additional dependency in every consuming build. The benefit is hypothetical. PLATFORM.md had a full row for casehub-memory; we collapsed it into the casehub-platform entry instead.

**The inline rule.** The arc42stories profile had a pointer to AGENTIC-HARNESS-GUIDE.md for anti-patterns. That works if the reader has both documents. It's a dead reference if they only have the arc42 doc — which is the common case when an agent or tool loads a single file at a time.

We added a rule: §8 must include 2–3 of the most dangerous failure modes inline, in Symptom → Cause → Fix format. The full platform-wide list stays in the harness guide. Application-specific patterns travel with the application document.

The production-first test got a tighter definition too. The old phrasing asked whether a class would exist in "a production system that does not include any other Chapters." The revision: "a production system built to this layer and no further." The distinction matters for tutorial layers — a class that makes sense in a full-stack deployment may not belong at Layer 2. Making the question layer-relative catches overbuilding that the original phrasing missed.

**The clinical SPI sync.** Updating the casehub-clinical deep-dive to reflect the engine SPI cleanup branch.

`AeStatusUpdater` was extracted from `AeEscalationListener` to solve two problems: Panache mock isolation (Panache static methods don't compose well with mock object graphs) and `@Transactional(REQUIRES_NEW)` boundary enforcement. Pulling the write-back into its own bean makes both the test setup and the transactional contract explicit. It's the standard fix when you need tight transactional isolation and testability together.

There was also a `StubWorkloadProvider` — a `@DefaultBean @ApplicationScoped` zero-returning implementation needed because a recent engine deletion removed a class clinical depended on for tests. The stub absorbs the shock; the root cause is tracked separately. This is the paperwork side of cross-repo dependency management: deletions happen elsewhere, clinical records both the adaptation and the open gap.

`IrbCommitteeAssignmentPolicy` mirrors `DeviationResponsePolicy` — same structure: a context record, an assignment record, a `@DefaultBean` in the runtime service package. Deliberate consistency. The third SPI following the same shape gives a future implementor something obvious to copy.
