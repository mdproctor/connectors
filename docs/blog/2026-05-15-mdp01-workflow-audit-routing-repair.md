---
layout: post
title: "Workflow Audit and the Routing Repair"
date: 2026-05-15
type: phase-update
entry_type: note
subtype: diary
projects: [casehub]
tags: [workflow, cc-praxis, routing, epic, workspace]
excerpt: "A systematic audit found 20 gaps in the epic/workspace workflow. Nine fixed. Claude moved artifacts the wrong direction three times before we got the routing right across all ten repos."
---

The session started with a question I'd been avoiding: is the epic workflow actually coherent, or just plausible-sounding? A systematic audit of every skill involved gave the answer. Twenty distinct gaps, ranging from missing a pause mechanism for mid-epic interruptions to `HANDOFF.md` being committed to the epic branch where the next session can't find it.

We worked through the trivial and easy items — nine fixed across two sessions. The trivial ones were template string edits: wrong routing defaults in workspace-init, Session Start missing the workspace add-dir, journal-entry defaulting off when it should default on mid-epic. Each took minutes. The easy ones were guard clauses and validation checks: handle missing DESIGN.md at epic close, validate branch alignment at start, check epic state in work-start before anything else. The harder items sit in a GitHub epic at `mdproctor/cc-praxis#71` with child issues for each.

The routing mess was a different kind of problem. The audit found that routing tables across all casehub repos were inconsistent — some pointing blog to project, some to workspace, some routing ADRs to workspace when they should go to project. Ten repos needed fixing. Claude made the changes wrong three times before we got it right.

The first error: moving quarkmind's specs and plans from the project repo to the workspace. They were already in the right place. Claude moved them backwards. The reset was a force push.

The second: moving the project CLAUDE.md content to the workspace for quarkmind, then creating a project symlink to the workspace. I'd said clearly that personal projects put CLAUDE.md in the project, workspace symlinks to it. Claude inverted the rule. Two more commits to fix.

The third: the cccli blog entries. Claude removed them from the project and put them in the workspace. I'd said cccli is on hold, leave the blog in the project. Claude had stopped reading far enough back in the conversation.

The pattern in all three: Claude was acting on a locally consistent interpretation of the most recent instruction, without holding the full constraint set in view. It's a long-session problem. The fix was explicit confirmation questions before touching anything.

Once the direction was right, the repair was thorough. Routing tables standardised across all ten repos: ADRs and specs to project, blog staged to workspace then published via publish-blog, plans and snapshots staying in workspace permanently. The 158 workspace blog entries we thought were unpublished turned out to be mostly published already — only 24 were genuinely missing from `mdproctor.github.io`. All 24 are there now.

The casehub-flow naming question is still open. The repo has nothing to do with workflow or Quarkus Flow — it's a case definition catalog service. Current thinking is `casehub-app`, the conventional Java name for the deployable artifact. Worth checking with Treble before committing to a rename that touches ten repos.
