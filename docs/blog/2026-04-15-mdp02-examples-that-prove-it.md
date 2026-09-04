---
layout: post
title: "Examples that prove it"
date: 2026-04-15
type: phase-update
entry_type: note
subtype: diary
projects: [quarkus-work]
tags: [ledger, examples, eigentrust, actortype, tdd]
excerpt: "Four runnable examples — expense approval, regulated credit decision, AI content moderation, and document review — together exercise every ledger capability, and the agent: prefix convention derives actorType without a separate parameter."
---

What I had after the previous session was a ledger module with ten capabilities and zero examples showing them in action. Documentation describes. Examples prove.

## Four scenarios, nothing left untouched

Before writing code, we audited the full capability surface — command/event separation, decision context, hash chain, rationale, planRef, evidence, provenance, actorType, attestations, EigenTrust — and designed four scenarios that together leave nothing undemonstrated:

**Expense approval** — the baseline. Full lifecycle (create → claim → start → complete), automatic ledger recording, SHA-256 hash chain, decisionContext snapshots at every transition. The entry point for anyone coming to the ledger for the first time.

**Regulated credit decision** — GDPR Article 22 in practice. The credit scoring system creates a WorkItem with provenance linking back to the loan application. The reviewing officer suspends mid-review, resumes, then delegates to a supervisor — the delegation entry carries `causedByEntryId` linking it to the resume that preceded it. The supervisor completes with an explicit `rationale` ("Income verified against payslips") and `planRef` ("credit-policy-v2.1"). A compliance officer adds a peer attestation (`verdict=SOUND`) for dual-control sign-off. Nine ledger entries, fully chained.

**AI content moderation** — agent/human hybrid. A mock classifier flags a post as hate speech and creates a WorkItem using `agent:content-ai` as the actor ID. That `agent:` prefix is enough: `LedgerEventCapture` derives `actorType=AGENT` automatically. The classifier's confidence score and model version land in the `evidence` field. A human moderator reviews and overrides the flag with a rationale. A compliance bot — also an agent — attests that the override is sound. The ledger chain reads: AGENT creates → HUMAN resolves → AGENT attests.

**Document review queue** — work queues and reputation. Three WorkItems posted to `candidateGroups=["doc-reviewers"]` with no direct assignee. Reviewers pull from the inbox. One claims a WorkItem and releases it; another picks it up and completes all three. `TrustScoreJob.runComputation()` materialises EigenTrust scores from the accumulated history — the reviewer who completed consistently scores higher. Queryable via `GET /workitems/actors/{actorId}/trust`.

Each scenario runs in one call — `POST /examples/{name}/run` — logs a narrative to stdout, and returns the full ledger trail as JSON.

## The actorType convention

The `agent:` prefix convention turned out to be a clean answer to a question I hadn't fully resolved: how does an AI agent identify itself as non-human in the ledger? A separate `actorType` field on every service call would leak into the API surface and require callers to remember to set it. The prefix lets the actorId carry the identity: `agent:content-ai`, `agent:compliance-bot`, `system:scheduler`. `LedgerEventCapture` derives the type from the prefix with no extra parameter. Simple enough to be hard to misuse.

## Ten capabilities, now readable

After the scenarios were running, I looked at the README ledger bullet: four capabilities listed out of ten. We expanded it to all ten — each named and described. The ledger module is genuinely interesting. The README should reflect that.
