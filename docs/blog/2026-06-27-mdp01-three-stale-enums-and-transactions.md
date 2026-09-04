---
layout: post
title: "Three Stale Enums and a Question About Transactions"
date: 2026-06-27
entry_type: note
subtype: diary
projects: [casehub]
tags: [lifecycle, cdi, coherence]
---

Another Claude session had reviewed our Lifecycle Coherence Protocol — the normative doc that registers every lifecycle state machine across CaseHub. It came back with a rewritten version of the state machine table. Every row was different.

The question was whether the other Claude was right or hallucinating. We verified each enum against the actual code — `PlanItemStatus` in engine, `WorkItemStatus` in work, `CommitmentState` in qhorus. All three had drifted from what LIFECYCLE.md recorded. Engine had 9 states where the doc said 8. Work had 12 where the doc said 10, with two renames (`CREATED`→`PENDING`, `CLAIMED`→`ASSIGNED`) the doc never picked up. Qhorus renamed `HANDOFF` to `DELEGATED` and added `ACKNOWLEDGED` — the doc still listed the old names.

The drift wasn't random. It was the residue of lifecycle alignment work that happened in peer repos and never got back-propagated to the parent doc. Each refactoring session updated its own repo's enum correctly and moved on. Nobody updated the cross-repo reference.

Two genuine protocol gaps surfaced: `CommitmentState` is missing `isActive()` — the only registered enum without it, violating the protocol's own Rule 4. And `PlanItemStatus` has a class-level Javadoc that omits `SUSPENDED` from its active/terminal groupings while the `isActive()` method correctly includes it.

The more interesting thread was evaluating whether dual-channel CDI event firing (`fire()` + `fireAsync()`) should become a platform standard. casehub-work already normalised to it. The question: does any other repo need it?

We audited CDI observers across five repos. The answer is clean: only work and ledger need dual-channel. Engine is fully async — its ledger observers use their own `@Transactional(REQUIRES_NEW)`, explicitly decoupled from the producer. Platform fires from Vert.x event-bus handlers where no producer transaction exists. Connectors is fully async. Qhorus has sync observers on `ChannelInitialisedEvent`, but the need is ordering — the backend must register before messages flow — not transactional participation.

The distinction worth naming: sync-for-ordering and sync-for-transaction are different things. Qhorus fires `ChannelInitialisedEvent` synchronously because backends must be registered before messages arrive. Ledger fires `AttestationRecordedEvent` synchronously because `IncrementalTrustUpdateObserver` uses `TransactionPhase.AFTER_SUCCESS` — it genuinely needs the originating transaction's commit boundary. The first pattern doesn't benefit from dual-channel; the second requires it. Mandating dual-channel everywhere would add ceremony that masks the repos where it actually matters.

Ledger already has the pattern partially — `TrustScoreRoutingPublisher` fires both channels — but `JpaLedgerEntryRepository` and `KeyRotationService` still fire sync-only despite having the same transactional observer needs. Filed ledger#159 to normalise the rest.
