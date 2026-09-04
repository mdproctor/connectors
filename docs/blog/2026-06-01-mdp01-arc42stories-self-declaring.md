---
layout: post
title: "Teaching ARC42STORIES.MD to declare itself"
date: 2026-06-01
type: phase-update
entry_type: note
subtype: diary
projects: [casehub]
---

The issue was filed to add a tier taxonomy to the Arc42Stories spec — Application tier,
Foundation tier, Extension tier. Reading it, something felt off.

Arc42 was always meant for any software system. Arc42Stories added Chapters and Layers
on top — but nothing in the base spec says "layers must be foundation module
integrations." That's a CaseHub harness app answer to the question, not a universal one.
A standalone app, a connector library, a UI component — any of them can have Chapters
that track progress and layers that reflect their own internal structure.

So instead of adding a tier taxonomy to the spec, the right fix was smaller: make clear
that layers are always project- or profile-defined, and that standalone apps need no
profile at all.

The interesting design problem was different: if a foundation module like
casehub-connectors writes an ARC42STORIES.MD, how does a reader — or an LLM session —
know where it sits in the platform? Its internal layers describe its own structure. They
don't tell you it depends on nothing, or that casehub-life and casehub-clinical consume
it.

That answer belongs in the document itself, not in a cross-repo fetch. I brought Claude
in to work through the design. We landed on a preamble block before §1: declares spec
version, profile, and what's project-specific. For a foundation module, project-specific
includes platform position — build order, dependencies, consumers. The profile ref uses
a local relative path with a raw HTTPS fallback so both development sessions and remote
LLM sessions can resolve it without extra fetching.

One more thing shook loose: the profile was duplicating the artifact schema table into
every document that followed it. A change to that schema would mean touching every
document. We shifted to a defaults/overrides model — the profile defines the schema, the
document inherits it, the preamble declares only the prefix. The table lives once.

We restructured the CaseHub profile to cover both Application and Foundation tiers. The
Foundation Layer Taxonomy — the casehub-work → casehub-qhorus → casehub-ledger →
casehub-engine integration sequence — is explicitly scoped to Application tier only.
Foundation modules define their own layer taxonomy. The worked devtown example was
replaced with a pointer to devtown's actual ARC42STORIES.MD; a snapshot in the profile
was always going to drift.

The guide also needed updating. AGENTIC-HARNESS-GUIDE.md still referred to LAYER-LOG.md
as the primary architectural record throughout. We updated it and added a migration
section — field mapping from LAYER-LOG layer entries to §9.4 Layer Entries, and seven
steps to migrate a repo. CLAUDE.md files will be updated in a forthcoming mass update;
the guide notes the transitional state.

The PR hit a snag: the branch was based on the fork's main, which had accumulated commits
ahead of casehubio/parent. GitHub reported CONFLICTING/DIRTY — no file conflicts, pure
history divergence. We cherry-picked the three commits onto a fresh branch from
upstream/main and raised a clean PR.
