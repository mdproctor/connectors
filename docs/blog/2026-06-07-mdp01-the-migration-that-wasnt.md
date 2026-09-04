---
layout: post
title: "The Migration That Wasn't"
date: 2026-06-07
entry_type: note
subtype: diary
projects: [casehub-parent, casehub-iot]
tags: [documentation, protocols, workspace-setup, rbac]
---

Spent most of this session clearing a backlog of doc sync issues — twelve across PLATFORM.md, casehub-engine, casehub-ledger, casehub-eidos, casehub-work, casehub-clinical, claudony, and casehub-connectors. Mechanical work, but it surfaced something I hadn't expected.

I asked why peer repos were still writing protocols to casehubio/parent instead of casehubio/garden. The answer was embarrassingly simple: the migration was never actually complete. The `docs/protocols/` directory in parent still existed, still accepted writes, and — crucially — PLATFORM.md still told sessions to add new protocols there. No error. No signal. Sessions wrote to the old location and the files landed silently.

We found it by comparing file counts: parent had 86 files, garden had 197. Weeks of continued writes to a supposedly retired location. We deleted the parent directory, updated 27 references in PLATFORM.md, and swept the remaining stale references out of eight other docs files.

The lesson is obvious in hindsight: a migration isn't complete until the old destination no longer exists. "Content moved" and "old location removed" are two separate steps, and only the second makes the migration durable. Obvious. Not in any checklist until now.

The casehub-iot workspace I'd set up the previous session turned out to be missing three things: the `wksp` symlink from the project repo back to the workspace, the `CLAUDE.md` symlink from the workspace to the project's CLAUDE.md, and Maven module directories with skeleton pom.xml files. The pre-push hook existed but wasn't activated. None of these were in the peer repo creation protocol I'd just written the same day. Fixed all of them, updated the protocol.

On RBAC: the platform now documents that the infrastructure is actually in place. `CurrentPrincipal.roles()` delegates to `groups()`, `casehub-platform-oidc` ships `OidcCurrentPrincipal`, `@RolesAllowed` works with CaseHub group names without a bridge. What's missing is adoption — devtown#71 is the first harness to use a named role annotation. Once the OIDC module lands on the classpath it enforces automatically.
