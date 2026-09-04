---
layout: post
title: "Fourteen Issues and a Git Lesson"
date: 2026-05-21
type: phase-update
entry_type: note
subtype: diary
projects: [casehub]
tags: [protocols, git, squash]
---

This morning started as a maintenance day. Fourteen open issues on the parent repo, almost all XS or S, almost all doc or protocol work that had accumulated over a few sessions. The kind of backlog where each item takes ten minutes but together they represent real drift between what's built and what's documented.

We worked through them in order: PLATFORM.md dependency rows, cross-repo table updates, seven new protocol files. The most substantial was the trust routing maturity model — a four-phase pattern for any casehub application that uses trust-based worker routing. Phase 0 is pure availability routing, identical to the Gastown GUPP baseline. Phases advance automatically as `minimumObservations` thresholds are crossed per agent per capability. No operator intervention, no configuration switches. The system never blocks on cold-start because Phase 0 is always there — it just accumulates evidence until it can route by trust.

Then we tried a squash.

The history had 190 commits going back to the previous backup point. Most were meaningful, but there were obvious clusters: nine audit-4 trim commits that should be one, fourteen snippet-evolution commits that should be one, several two-commit pairs split across session boundaries.

The first attempt failed immediately. I'd used `backup/pre-squash-main-20260508` as the rebase base — which looked right, since that's what main looked like before. But the backup branch is a *sibling*, not an ancestor. It shares a merge-base with the 190-commit range; it already contains those commits in squashed form. Git tried to replay commit A onto a base that already had A in squashed form, and conflicted on the first file.

The correct base is the direct parent of the oldest commit in the range. There was also a merge-commit problem: `pick` doesn't accept merge commits in a rebase todo — you need `merge -C <sha>` to replay the topology, or `drop` when the pre-merge work has already been absorbed into the plan.

Given the complexity — other sessions were actively pushing during the attempt, the history stretched six weeks — we scoped down. Instead of 190 commits we targeted the recent 85: the cleanest, most obviously squashable clusters. The rebase ran without a conflict. The nine audit-4 trim commits became one. Four other pairs absorbed. 85 commits down to 74.

Not the full clean history I wanted. But the lesson is in the garden and the squash window for next time starts from a better baseline.
