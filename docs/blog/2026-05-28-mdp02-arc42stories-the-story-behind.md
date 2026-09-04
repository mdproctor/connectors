---
layout: post
title: "Arc42Stories: When Layer 3 Broke Open Something Bigger"
date: 2026-05-28
entry_type: note
subtype: diary
projects: [CaseHub Parent]
tags: [arc42stories, sial, architecture-documentation, llm-development, vertical-slice]
---

The plan was to implement Layer 3 of devtown.

Devtown is CaseHub's reference application for software engineering — a PR review
coordination system that demonstrates what the platform makes possible for
development teams. Like all CaseHub harness applications, it integrates the
foundation modules incrementally: Layer 1 is the domain baseline, Layer 2 adds
SLA-bounded human review via casehub-work, Layer 3 adds typed
COMMAND/RESPONSE/DONE/DECLINE per specialist reviewer via casehub-qhorus. Each
layer closes a specific accountability gap the previous one left open.

Layer 3 had a CDI problem.

Both the Layer 3 service and the Layer 5 service (already built — engine
orchestration) implement the same port interface, `PrReviewApplicationService`.
In production you'd have one or the other, never both. But the codebase carries
all tutorial layers simultaneously, so the CDI container sees two
non-`@DefaultBean` candidates and throws `AmbiguousResolutionException`. The fix:
`@Alternative @Priority(1)` on the Layer 5 class, `@Unremovable` on Layer 3 so
Quarkus doesn't optimise it away.

I wrote it out. It compiled. Then I stopped.

Every annotation existed for one reason: to make tutorial layers coexist in the
same build. In production, neither annotation would be there. I wasn't solving
an architectural problem — I was solving a tutorial packaging problem dressed up
as CDI configuration. The question that followed opened everything else.

---

**The production-first rule has a corollary we hadn't applied to documentation.**

The rule for CaseHub harness apps is explicit: every line of code must justify
its existence in a deployed production system. A class that exists only to make
tutorial layers coexist in one build is wrong code. That rule was established
before this session and has kept us honest on individual classes. What the CDI
question revealed is that we'd been applying it to source code but not
consistently to the methodology and documentation architecture surrounding it.

The LAYER-LOG had quietly become a build plan. Sessions were completing layers as
full units — Layer 2 done, Layer 3 done, Layer 4 done — before touching the next.
The reading order of the document had become the build order of the system. That
is exactly wrong. You should build the thinnest working path through all relevant
layers first — one case opens, one agent receives a COMMAND, one human review
WorkItem is created with an SLA, one ledger entry is written — and then deepen
each layer in subsequent slices. The full stack is exercised early. Integration
failures surface before significant layer logic has been built out. The system is
always demonstrable.

We'd known this in principle. We hadn't written it down clearly enough to enforce
it, and the LAYER-LOG's structure had been silently encouraging the wrong pattern.

---

**Naming the delivery concept forced the right clarification.**

The obvious word for "a vertical cut through horizontal layers delivering one
user-visible capability" is "slice." It's the right metaphor — you're cutting
vertically through things stacked horizontally. The problem is that "vertical
slice" is already taken, and taken by something in exactly the same space.

Vertical Slice Architecture — Jimmy Bogard, 2018, widely adopted in .NET and
spreading actively into Java — is a code organisation pattern where all code for
one feature co-locates across layers in a single directory. A `CreateUser/`
folder contains the command, the handler, the validator, and the endpoint. No
more feature-scatter across Controllers/, Services/, Repositories/. The metaphor
is identical: a vertical cut through horizontal layers. The domain is completely
different: code organisation, not delivery planning.

A developer familiar with VSA reads "vertical slice" and expects feature
co-location. They look at the code, find traditional module structure —
`domain/`, `review/`, `app/` — and are confused twice: once by the word, again
by the code not matching it. We renamed them Chapters.

A Chapter is a vertical cut through the foundation layers that delivers one
user-visible capability end-to-end. The word comes from the narrative framing —
we're writing the story of the system as it's built — and it has no prior meaning
in software architecture. It fits, and it's unambiguous.

---

**The documentation rethink that followed.**

Once we had Chapters, the LAYER-LOG needed a Chapter Index at the top: a table
showing which capabilities the system can deliver, which foundation layers each
Chapter required, and what order they were delivered in and why. The ordering
rationale belongs adjacent to the index — sequential dependencies first (ledger
before trust scoring, because trust scoring reads attestation data written by
ledger), then minimal layer delta (prefer the Chapter that adds one new foundation
module over one that adds three, all else equal).

Below that, the layer entries continue doing what they've always done: key
wiring, gotchas, the domain-agnostic pattern an LLM follows to replicate the
layer in a different harness. Those two purposes — planning view at the top,
technical record below — don't conflict. The document just needed to state both
explicitly rather than defaulting to one.

The DESIGN.md tension surfaced next. We'd been maintaining a separate
cross-cutting decisions document alongside the LAYER-LOG. Good in theory:
decisions that span multiple layers or were debated across sessions get their own
entry, with the "why" preserved separately from the "what." In practice, layer
entries already had an "Architectural decisions" section. Two documents capturing
decisions, unclear ownership, entries written in one place and never updated in
the other. A future Claude session reading both files sees contradictions and
can't tell which is authoritative.

The resolution was to merge: one document — ARC42STORIES.MD — carrying the
Chapter Index, layer entries, and cross-cutting decisions under a single roof.
The JOURNAL stays separate because it's ephemeral — it captures in-session
reasoning, feeds the permanent document at epic close, then is discarded.
Everything else converges. The document is named ARC42STORIES.MD, all caps,
consistent with CLAUDE.md and HANDOFF.md, so any reader knows immediately what
they're looking at and which standard it follows.

We also defined a project artifact schema — a small table in the document's
introduction mapping artifact types to their naming format. Improvement log
entries use `PREFIX-NNN` where PREFIX is project-specific (DT for devtown, AML
for the financial crime harness). Garden entries are `GE-YYYYMMDD-xxxxxx`. ADRs
are `ADR-NNNN`. Any cross-reference in the document that matches a defined format
is navigable without prior knowledge of the project. An LLM picking up the
document cold can resolve every reference from the schema table alone.

---

**When I went looking for prior art, I found arc42.**

[arc42](https://arc42.org) has been the architecture documentation standard since
2005. Twelve sections, stable for over a decade, translated into eleven
languages, used by SAP, Siemens, Deutsche Telekom. Gernot Starke and Peter
Hruschka built something that survived widespread production use. The instinct to
extend proven prior art rather than build bespoke was correct.

arc42 covers the static structure well: introduction and goals, constraints,
context and scope, solution strategy, building block view, runtime view,
deployment view, crosscutting concepts, architectural decisions, quality
requirements, risks and technical debt, glossary. Everything you need to document
a system that exists.

It has no concept of delivery planning. No Journeys, no Chapters, no layer delta
tracking, no incremental build model. Section 9 — architectural decisions — has
an explicit rule: "only decisions not described elsewhere belong here." That
resolves the DESIGN.md duplication cleanly. arc42 thought of it first. The
document was designed for a world where an architect wrote documentation once for
a system already built. That world still exists. It's just not the only one.

---

**Arc42Stories extends arc42 in three directions it doesn't address.**

The first is delivery planning. Journeys and Chapters as first-class concepts —
a Journey is a major business flow (for devtown: PR Review Coordination), a
Chapter is a delivery increment within it. Each Chapter has a Layer Impact table
showing which foundation modules it touches and by how much (None / Low / Medium
/ High delta). The Chapter Index answers what the system can DO, not which
modules are integrated. Those are different questions and the distinction matters
when evaluating the system or planning the next increment.

The second is LLM session continuity. ARC42STORIES.MD is a living document — it
grows across sessions as Chapters ship. A new Claude session reads one file and
has full context: which capabilities exist, what's pending, which architectural
decisions are settled and why, where the key files are, which protocols govern
each layer. The three-document system — ARC42STORIES.MD (permanent architectural
record), JOURNAL.md (ephemeral per-epic working doc), HANDOFF.md (per-session
continuity) — gives every session a clean pick-up point. arc42 was designed for
humans reading a completed document. We need humans and machines reading a
document that's always partially written.

The third is LLM replication. Each layer entry carries a "Pattern to replicate"
section: domain-agnostic numbered steps an LLM follows to implement the same
layer in a completely different harness. Not "here's how devtown wired
casehub-work" but "here are the steps to wire any human task lifecycle into any
harness, regardless of domain." Clinical trials. Financial crime investigation.
Game AI coordination. AML has demonstrated this works — an LLM reading the AML
layer entries can produce the equivalent layer for devtown without asking domain
questions. arc42 was written for humans reading documentation. Arc42Stories is
also written for machines generating new systems from it.

The name writes itself. arc42 gives you the architectural answer to life, the
universe, and everything. Arc42Stories gives you the full story behind the answer.

---

**The C4 extension needed three views arc42 doesn't define.**

Standard container and component diagrams handle static structure — that carries
over unchanged. Arc42Stories adds three views specific to the Journey/Chapter
model.

The Layer Architecture View is a `C4Container` diagram with one
`Container_Boundary` per foundation layer. It shows the horizontal stack — which
components belong to which layer, how they connect — and evolves as Chapters ship
new components into it.

The Chapter View is a `C4Component` diagram filtered to one Chapter's elements.
Components introduced in this Chapter are prefixed 🟢; components modified by it
are prefixed 🟡; existing context components have no prefix and may be omitted if
not needed for clarity. It makes the Layer Impact table visual — you can see
exactly what changed and where.

The Journey Map is a Mermaid flowchart, not a C4Dynamic diagram. C4Dynamic is
for runtime sequences — how components communicate at execution time. A Journey
Map is a planning view — how Chapters chain together, what depends on what,
what's shipped versus pending. Green for shipped Chapters, yellow for active,
grey for pending. One flowchart per Journey, updated as Chapters close.

---

Arc42Stories is committed to casehub-parent as a universal spec with a CaseHub
profile that instantiates the Foundation Layer taxonomy (casehub-work,
casehub-qhorus, casehub-ledger, casehub-engine) and the artifact naming schema.
The spec is written to be portable — any LLM-driven project could adopt it. A
Spring Boot harness, a Django harness, a game AI harness. The CaseHub profile
shows how to instantiate it for one specific stack; the pattern is the same
regardless of what's underneath.

LLM-driven development has documentation needs that no existing standard
addresses. arc42 is the closest prior art and we're standing on it — not
reinventing it. That's the right foundation for something worth promoting.

The session did not ship Layer 3. It shipped something that will make every
layer, in every harness, easier to build, explain, and replicate.
