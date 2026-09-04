---
title: "Conversation Folds — Epistemic Ground and Convergence"
date: 2026-07-21
type: diary
tags: [conversation, epistemic, convergence, fold, blocks]
issue: "casehubio/blocks#65, casehubio/blocks#66"
---

Multi-agent conversations accumulate assertions but nothing tracks what's
established vs what's still contested. The conversation projection folds
messages into structured state — points, threads, statuses — but the fold
doesn't interpret. Two agents acknowledging a claim and two agents repeating
themselves look the same to the fold.

Today's work adds a derivation layer on top of the fold. Not inside it —
deliberately outside. The fold preserves raw state. The derivation interprets
it. Different consumers need different interpretations: clinical requires
explicit per-agent sign-off, drafthouse accepts silence as acceptance, AML
needs completed obligations. Baking one interpretation into the fold would
be wrong for at least one of them.

The design has two stages. `CommonGroundAnalyser` classifies each
conversation point as ESTABLISHED, PENDING, or DISPUTED — driven by a
pluggable `EpistemicRule`. Three rules ship: explicit acknowledgement
(N participants must respond), tacit acceptance (silence within a round
window), and commitment resolution (only DONE counts). Rules compose via
`and()` (most conservative wins) and `or()` (most permissive wins).

`ConvergenceAnalyser` sits on top of common ground. It answers: is this
conversation progressing, converging, stuck, or dying? Five states, each
mapping to a supervisor action. CONSENSUS means close. DEADLOCK means
escalate. DIMINISHING_RETURNS means close with partial results. The signal
includes confidence so the supervisor can set its own threshold.

The key architectural call was post-fold derivation vs fold extension.
The fold enrichment was minimal — two fields added to `ThreadEntry`
(`sender` and `createdAt`) that the fold was already discarding from
`MessageView`. Everything else is pure functions from `ConversationState`
to derived views. The dependency chain is natural: fold → common ground →
convergence → render. Each stage is independently testable, independently
configurable, and independently replaceable.

`ConvergenceTermination` bridges to the platform's `TerminationCondition`
SPI so the same convergence policies work with `DebateBuilder.convergence()`
and the orchestrated/choreographed execution drivers. No separate
integration needed — the bridge adapter composes the full pipeline
(fold → common ground → convergence → decision) in a single `evaluate()`.

The adversarial design review (4 rounds, 17 issues, $15) caught several
things the brainstorm missed: `tacitAcceptance` would have falsely
established zero-response points (silence from agents who never saw a
claim isn't acceptance), `commitmentResolution` couldn't distinguish DONE
from RESPONSE without a dedicated `completedBy` set, and the renderer's
overload-per-combination pattern was heading toward combinatorial explosion
(fixed with `RenderContext`).

624 tests. 45 new, zero failures. The fold is the foundation — common ground
and convergence are the first two derived views, but the pattern generalises.
Any interpretation that can be expressed as a function from `ConversationState`
to a derived view slots into the same pipeline.
