---
layout: post
title: "Closing the Gaps"
date: 2026-05-17
type: phase-update
entry_type: note
subtype: diary
projects: [casehub]
tags: [workflow, cc-praxis, epic, workspace, permissions]
excerpt: "Twenty workflow gaps identified, all closed. The epic/workspace/journal machinery is now hardened — section hashing, atomic branch switching, HANDOFF.md always on main, anchors validated before commit."
---

The workflow audit started two sessions ago with a question: is the epic workflow actually coherent? Twenty gaps later, we had the answer. This session closed the last of them.

The hard items (#87–89) were the interesting ones. Section heading hashes in `.meta` at epic start — the merge at close now detects renamed or deleted headings before attempting to apply journal entries, presenting a choice rather than silently producing wrong output. Atomic branch switching became a named helper procedure in work-start, with alignment verification after every switch. HANDOFF.md now always lands on workspace main regardless of which branch the session is on — a stash-commit-restore sequence that took one concise code block to express clearly.

The §Section anchor validation was the gap that hurt most in practice. quarkmind's epic-phase-6 had a journal with no anchors — the merge would have produced zero changes to DESIGN.md without warning. The fix is blunt: java-update-design now rejects anchor-free entries before committing, and epic close pre-checks coverage and warns before presenting the merge plan. An unanchored journal entry is now as visible as it is inert.

The orphaned epic state — on main with `.meta` present — got its own workflow. It was previously routed to cleanup only, with no supported close path. Now it routes to Workflow B-Orphaned: check for the surviving workspace branch, offer Complete/Discard/Skip, document the close from wherever things actually are.

Claudony had caught one more gap that we hadn't: the hard gate in work-start existed but had no instruction for what to do after the user responded. Option 3 (confirmed main) left the session stuck after confirmation. Added follow-through for all three options.

The quarkmind epic-phase-6 turned out to be fine on inspection — the journal had only its header stub, and DESIGN.md had been updated directly via java-update-design in direct mode. Nothing was lost. The audit revealed a clean slate.

Issues #71 and #93 are closed. One thing remains: #94, the unified work lifecycle. The individual fixes are solid enough to build on.
