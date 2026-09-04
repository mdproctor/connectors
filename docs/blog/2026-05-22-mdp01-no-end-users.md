---
layout: post
title: "No End Users"
date: 2026-05-22
type: phase-update
entry_type: note
subtype: log
projects: [CaseHub Parent]
tags: [design-philosophy, tooling, git]
---

There's a pattern I keep seeing. Claude builds a workaround. Not wrong exactly — the workaround works — but it accumulates a layer where none is needed. A wrapper class to avoid breaking a caller. A compatibility shim to preserve an old API. Design debt dressed up as pragmatism.

The underlying cause isn't a capability gap. It's training bias. Every real-world codebase Claude learned from had end users, had API consumers, had things that couldn't break. Breaking callers is usually expensive and sometimes catastrophic. That bias runs deep, and overriding it requires being explicit — not once, but persistently.

I spent time with Claude reviewing the development workflow prompt snippet that governs design sessions on CaseHub. What started as wording cleanup turned into something more substantive: naming the design philosophy clearly enough that Claude can apply it at the moment of a workaround decision, not just acknowledge it when told.

The critical addition was context, not instruction. "Never add workarounds" is ignored the moment Claude is staring at twelve call sites. "This platform has no end users — breaking changes cost nothing externally. Before proposing any wrapper, stop and ask: is this the right design?" provides the reasoning. Reasoning beats rules when the training reflex kicks in.

One adjustment I hadn't expected to need: explaining what "Think hard, be diligent, be systematic" actually means. I use those phrases to set an operating posture for the full session — not a single moment of deliberation, but a sustained mode through brainstorming, design, and implementation. The snippet now carries all four. They're not redundant — each targets a different failure mode.

We also reworked how design alignment with PLATFORM.md and the platform protocols is described. The instinct is to design something and then verify it against the rules. But by the time you're verifying, you've often committed to an architecture that violates one of them, and you're in rewrite territory. The wording now says: read before proposing anything, consult throughout, final review before leaving brainstorming. One continuous discipline, not a gate.

On the tooling side, we found and fixed a real bug: when tracking an issue from a different GitHub repo in a workspace set up for a different project, work-start would set `DESIGN_REPO` from the workspace routing config — pointing the design journal at the wrong `DESIGN.md` entirely. The fix adds a Layer 0 to the routing cascade: detect cross-repo issue refs (`cc-praxis#94` instead of `parent#20`), resolve the repo's local path via sibling directory convention, and store it in `.meta` so work-end can pick it up correctly.

A squash run toward the end of the day surfaced something worth keeping. I had created a working branch from main, classified twelve commits, ran the rebase — and Claude came back: the two-dot diff against main was non-zero. A pure history rewrite cannot change the tree state. Something else had changed. Another session had run concurrently and merged commits onto local main between branch creation and the squash. The working branch's `@{u}..HEAD` range had reflected the snapshot of main at creation time, not its current state. The check before any swap: `git diff main <squash-work-branch> --stat`. Empty means safe; non-empty means main moved — abort and re-run.
