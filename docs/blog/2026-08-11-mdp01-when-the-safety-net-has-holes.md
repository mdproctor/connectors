---
layout: post
title: "When the Safety Net Has Holes"
date: 2026-08-11
entry_type: article
subtype: diary
projects: [casehub-work]
tags: [workspace-audit, cdi, slot-management, branch-hygiene, remediation]
---

Two things looked routine today. A workspace audit — checking that branches were properly closed across the casehub family. And a CDI cleanup — removing unnecessary `@ApplicationScoped` annotations from 21 classes flagged by an earlier audit.

Both turned out to be wrong about what they thought they knew.

## The audit that found real loss

I started the workspace audit because a branch mismatch kept showing up at session start — `.meta` files referencing closed issues, ctx.py reporting stale state. Annoying but not urgent. Worth a sweep.

Claude ran the audit across 22 repos — workspace repos, project repos, and 9 slot directories. The headline number was eye-catching: 731 branches, 473 with close stamps. But the real finding was in three slots.

Slots 93, 94, and 97 each had engine branches stamped "chore: branch closed" — the standard marker that says "this work landed on main." Except it hadn't. The branches existed only in their slot clones, never pushed to the original repo, never pushed to GitHub. DataChannel patterns, agent learning, an entire `agentic-engine` Maven module — roughly 6,700 lines of implementation sitting in local directories with no backup.

The close stamps had no landing SHA. That was the tell. The new-format stamp includes the SHA where content landed on main — `landed as abc123 on main`. These had the old format: just "branch closed" with nothing to verify against.

## Why it happened

The work-end skill's SKILL.md described a slot-mode landing sequence — loop through repos, two-hop push (slot clone to original to GitHub), stamp with SHA. The description was accurate. The problem: it called `work_end_execute.py` which implements none of that. The correct implementation lives in `slot_manager.py merge_slot()` — proper per-repo loop, two-hop push, landing SHA stamps. It just wasn't wired up.

So when work-end ran in slot mode, it processed one repo (the one passed as `project=`), stamped the workspace branch without merging its content, and silently abandoned everything else. The verification gate couldn't catch it either — old-format stamps without a landing SHA were passing as "acceptable."

A second issue compounded it. The engine repo's workspace symlink (`wksp`) was pointing to `../work/engine` — a subdirectory inside the casehub-work project repo, not a separate git root. So engine workspace operations (branch creation, .meta scaffolding) were landing on the work repo's git tree. ctx.py didn't validate that symlink targets are git roots. A directory that looks like a workspace (has `design/`, `blog/`, `specs/`) was accepted without checking for `.git`.

This has happened twice. The symlink gets recreated by a session that improvises `ln -s` instead of using the standard script.

## The CDI cleanup that wasn't

Issue #345 listed 21 classes with "zero injections" — no `@Inject` fields. The audit concluded: CDI unnecessary, remove `@ApplicationScoped`.

The list was wrong. Of the 21 classes, 8 no longer exist. Of the remaining 13, 12 are SPI implementations — they implement interfaces that are `@Inject`ed elsewhere. CDI is required for these, not because they inject anything, but because the container needs to discover them for injection points elsewhere.

"Zero injections" is a misleading metric for CDI audits. A class that injects nothing can still be essential to CDI — if anything injects *it*. The correct audit checks both directions: does the class inject anything, AND does anything inject it? For SPI implementations, removing `@ApplicationScoped` breaks every injection point that depends on the interface.

Four classes genuinely had unnecessary CDI — stateless utilities with no interface, injected by a single caller that could just use `new`. One SPI implementation (`NoOpWorkerRegistry`) was missing `@DefaultBean`, which would cause ambiguous dependency if a consumer provides their own implementation.

The final change: 12 files, net -13 lines. Not 21 classes cleaned up — 4 de-CDI'd and 1 fixed. The audit overcounted by 4x.

## What's still out there

185 branches across engine, platform, and openclaw have genuine unmerged code — real `.java` and `pom.xml` changes with close stamps but non-zero diffs against main. These are pre-squash history from the squash-merge workflow, not data loss. But they need proper verification before anyone assumes the content reached main.

The skill fixes for work-end and ctx.py shipped today via soredium. The symlink is corrected. The stranded slot content is pushed to GitHub. But the 185 branches are still out there, each one a small bet that the squash-merge did what it was supposed to.
