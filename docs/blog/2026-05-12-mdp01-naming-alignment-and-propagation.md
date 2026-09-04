---
layout: post
title: "Naming Alignment and Artifact Propagation"
date: 2026-05-12
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-parent]
tags: [maven, naming, protocols, intellij, refactoring]
excerpt: "A systematic scan of nine repos revealed consistent naming drift. Most fixes were mechanical. Claudony was not — and the connectors rename surfaced exactly the gap we needed a protocol for."
---

Platform naming has been inconsistent since the repos multiplied. Nine repos, each with its own drift: claudony on `dev.claudony` groupId, engine folders not matching their artifactIds, work carrying the full `casehub-work-` prefix on directory names that should be short. We ran a full scan and fixed all of it.

Most fixes were mechanical. Claudony was not.

Moving `dev.claudony` to `io.casehub.claudony` requires IntelliJ's Refactor → Move Package UI — the MCP `ide_refactor_rename` only handles same-level renames. The refactoring ran, reported success, and silently corrupted four test files. The failure mode: `@Inject SessionRegistry registry;` on a single line split into two lines during the move, and the following field's `@Inject` merged with its class name, producing `@InjectCaseEventBroadcaster` as a fake annotation. Syntactically plausible, semantically wrong. The compiler refused it. We caught all four by grepping for `@Inject[A-Z]` — no space — and fixed them manually.

The more consequential miss came from renaming `casehub-connectors` to `casehub-connectors-core`. We updated the connectors repo, checked it thoroughly, moved on. Two repos — `casehub-work-notifications` and `casehub-devtown-app` — both had the old artifact name hardcoded in their poms. Completely missed until the full-stack build failed.

That's the scenario a protocol exists to prevent. We added `artifact-rename-propagation.md`: before shipping any artifactId rename, look up every consumer, update them in the same session, verify each builds. We also added a Cross-Repo Dependency Map to PLATFORM.md — twenty-five rows mapping every cross-repo artifact dependency. The next rename has a lookup table.

The scan also surfaced a gap in the existing folder-naming protocol. It covered folder names well but said nothing about groupId, root artifactId, parent references, or version alignment. `maven-coordinate-standard.md` fills that: the full coordinate convention in one place, with a verification checklist to run before any pom commit.

Two consumers missed. Two protocols written. The pattern is consistent — you don't write the rule until reality shows you exactly where you needed it.
