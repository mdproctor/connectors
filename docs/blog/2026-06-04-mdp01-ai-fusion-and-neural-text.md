---
layout: post
title: "AI Fusion Direction, a New Repo, and a README That Taught Me Something"
date: 2026-06-04
type: pivot
entry_type: note
subtype: diary
projects: [casehub]
tags: [ai-fusion, architecture, neural-text, write-content]
---

The session started as doc sync — six open issues, all documentation, work from
other repos that hadn't landed in the platform docs yet. DraftHouse Layer 2 shipped
and APPLICATIONS.md still said "Scaffold." casehub-engine had a tenancy enforcement
stack nobody had documented. Standard maintenance. We cleared all six.

Then the conversation turned to gaps.

## Where the "AI Fusion platform" framing came from

I asked Claude to look across all the foundation repos — PLATFORM.md, every
deep-dive — and identify capability areas that were missing for a genuine AI Fusion
platform. Not features. Whole new areas, like eidos or ledger in scope.

The first answer included "model/provider registry." I pushed back immediately.
That's efficiency and governance — choosing which LLM to call and tracking cost.
It doesn't enable new kinds of AI capability; it manages existing capability more
efficiently. Not fusion.

The corrected framing was better: AI Fusion is combining AI systems that *reason
differently*, not just routing between models. Dense vector search isn't fusion.
A system where Drools rules fire on typed facts, LLM agents reason about the output,
and QuarkusFlow transforms the results — where each paradigm's weaknesses are
covered by the others — that's fusion.

## The QuarkusFlow correction

Claude identified "heterogeneous AI integration" as a gap. I pointed out that
QuarkusFlow as a Worker already covers this. The `WorkerProvisioner` SPI exists
precisely for this. Claudony implements it for Claude CLI sessions. OpenClaw
implements it for OpenClaw agents. QuarkusFlow would implement it for deterministic
workflow execution. The architecture already supports heterogeneous workers — the
gap was a pending integration, not a missing abstraction.

That correction sharpened the actual question: not *executing* heterogeneous AI, but
*synthesising their outputs* into something coherent. When QuarkusFlow produces
structured data and an LLM produces text, how does the next step treat those two
things differently? Right now it doesn't. Both arrive as untyped JSON in the case
context.

## Drools made it concrete

When I mentioned that Drools was coming next as a Worker, the gap became impossible
to ignore. Drools fires rules and produces typed conclusions —
`SARDecision(typologyId="47", mandatory=true, tracedRules=["Rule47"], confidence=1.0)`.
That conclusion is deterministic, fully traceable, 100% confidence. An LLM
conclusion is probabilistic, generative, implicit confidence, opaque reasoning. They
sit side by side in the case context as raw text, epistemically indistinguishable.

The real gap is a **typed fact space**: a case-scoped, live, mutable workspace where
all worker types assert typed knowledge with epistemic metadata — paradigm,
confidence, basis, derivation chain. Drools asserts into it natively. LLM outputs
get compiled from it. QuarkusFlow reads structured data from it. Human decisions are
recorded as typed facts with authority metadata.

This isn't the `BlackboardRegistry` casehub-engine already has. That tracks plan
item state — which workflow steps are running or delegated. Domain knowledge is
different. This is the true AI Blackboard in Hayes-Roth's 1985 sense: shared
epistemic working memory, not plan coordination.

We wrote the AI Fusion spec — the typed fact space design, the AML investigation
walkthrough showing all four paradigms collaborating, the `CaseFact` interface with
epistemic metadata. Then we filed issues for the other gaps: AI observability (#154),
policy engine (#155), evaluation infrastructure (#156), artifact pipeline (#157),
RAG (#158).

## The RAG detour

Issue #158 started as "universal RAG library shared between casehub and Hortora."
After Hortora pointed out that LangChain4j already covers most of what we'd
described — Tika integration, chunking, dense ONNX embeddings, 30+ vector stores —
we had to rethink.

The actual gap is narrower: SPLADE sparse embeddings, NLI classification, scalar
regression, cross-encoder reranking — inference types LangChain4j doesn't handle.
Hortora already runs all of these in production via Tika, ONNX, SPLADE, and Qdrant.

So the shared module became `casehubio/neural-text` — an ONNX inference layer
(shared, zero casehub domain deps, usable by Hortora) plus a casehub-specific
LangChain4j RAG wiring layer. Nine Maven modules. The naming had several iterations
before landing there: `casehubio/inference` (confused with Drools inference),
`casehubio/ml` (too general), `casehubio/neural` (sensors), `casehubio/neural-text`
(precise).

We bootstrapped the full ecosystem: GitHub repos, workspace, CI/CD integration across
every parent workflow, casehubio.github.io architecture diagram and project card,
PLATFORM.md, deep-dive docs, and a full ARC42STORIES.MD — seven epics, seven layers,
C4 diagrams at multiple views. Nine Maven modules with pom.xml files, source
directories, and application.properties stubs. Next session opens the repo and adds
Java files.

## The README episode

Writing the README for neural-text surfaced something I hadn't expected from a
content exercise. I initially routed it through the write-content skill as a `Brief`
with `Reference` mode. The Brief mode constraint — "no prose where tables work" —
was correct for module inventory tables and status sections. But applied globally,
the per-capability explanations disappeared. Not compacted — gone.

The diagnosis was direct: "how did my write-content end up being a content stripper?"

Right. The mode applied correctly to the right sections. Applied to the wrong
sections, it removed load-bearing information that has no table equivalent. The
explanation of why SPLADE matters for regulatory text — that dense embeddings dilute
the weight of terms like `Art. 22 GDPR` — cannot be expressed as a table row without
losing the reasoning.

A README is a multi-mode document. Different sections serve different purposes and
need different mode constraints. Module tables are Reference/inventory. Per-capability
explanations are Explanation/discursive. Applying one mode globally is the mistake.

We added a `[R] README` form to the write-content skill — an explicit section-to-mode
map, plus a named anti-pattern called "the stripping trap" with the test: "Could a
developer who hasn't been in the design discussions understand why this matters from
the table entry alone?" If no — the prose is load-bearing and must stay.
