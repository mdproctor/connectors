---
layout: post
title: "Designing the Work Lifecycle"
date: 2026-05-20
type: phase-update
entry_type: note
subtype: diary
projects: [cc-praxis]
tags: [workflow, shell, claude-code, design]
---

The old workflow needed two commands to start any piece of work: `/work-start`
for pre-checks, then `/epic begin` to create a branch. If you forgot the second
one, you'd be writing artifacts to the wrong place — journal missing, design
notes landing in the project repo directly. There was no enforcement, and the
gap was wide enough to walk through by accident.

I'd been wanting to fix this for a while. An audit of twenty gaps in cc-praxis#71
pointed at the same root cause: the lifecycle was fragmented across commands that
didn't know about each other. Issue #94 was the synthesis — four commands, one
coherent lifecycle: `work-start` absorbs branch creation, `work-end` absorbs epic
close, two new commands for pause and resume.

## The detection state ordering problem

The spec took five rounds of review before I called it ready. Each round found
something real. The most interesting was the detection state ordering.

`work-start` has to detect six possible branch states. State 3 is "orphaned `.meta`
on main" — the branch was merged or deleted without running `work-end`. State 4 is
"branches misaligned" — `.meta` exists but the workspace and project repos are on
different branches. Both conditions can be true simultaneously: an orphaned `.meta`
on main also satisfies "branches misaligned" because the project branch is gone.
Checking state 4 before state 3 sends the branch-switch helper looking for a branch
that no longer exists.

The fix is just ordering — check state 3 first — but spotting it required thinking
through what each state's data looks like rather than just the happy paths.

## Routing decisions belong in `.meta`, not at close time

The design journal merges into either the project repo or the workspace at branch
close, depending on a three-layer routing config. My first draft re-read the routing
config at close time. That's wrong: the config might have changed since the branch
was created, giving a different answer than at branch creation.

The fix was to resolve and store `design-repo: project|workspace` in `.meta` at
`work-start` Step 8, then read it back verbatim at `work-end`. The routing
decision is stamped at branch creation and treated as immutable thereafter.

## Twelve bash bugs

I brought Claude in for the implementation. We wrote all four skill files in one
pass, plus the deprecation header on `epic`, the `java-update-design` workspace mode
fix, and the supporting docs. Then Claude reviewed the implementation and found twelve
bash correctness issues.

The worst was in `work-pause`:

```bash
PROJECT_DIRTY=$(git -C "$PROJECT" status --short | grep -c . || echo 0)
```

`grep -c` outputs `0` when there are no matches and exits 1. The `|| echo 0` was
meant to handle the no-match case — but because `grep -c` already outputs `0`, the
pipeline produces `0\n0` when the repo is clean. The dirty check sees a non-empty
string and triggers the stash prompt incorrectly. The fix is `wc -l`:

```bash
PROJECT_DIRTY=$(git -C "$PROJECT" status --short | wc -l | tr -d ' ')
```

There were also two `A && B || C` patterns in `work-resume` meant to act as
conditional error handlers. They don't. If `A` is false, `C` runs unconditionally —
the shell evaluates it as `(A && B) || C`, not as `if A then B else C`. Harmless
in this instance because `C` contained a redundant guard, but fragile enough to
replace with explicit `if/fi`.

The stash reference issue: `work-pause` records `stash@{N}` at pause time; `work-resume`
should pop that specific position, not always `stash@{0}`. Several `stash pop` calls
were still using the bare form. We fixed all of them.

## The java-update-design silent failure

The `java-update-design` fix was worth noting separately. The existing code detected
workspace mode by checking for an `epic-*` branch prefix. On an `issue-NNN-*` branch
— the new naming convention — the check failed silently: every commit wrote directly
to `DESIGN.md`, bypassing the journal with no error and no warning. We replaced the
prefix check with a three-condition test: `.meta` exists, `JOURNAL.md` exists,
workspace not on main. Branch name plays no part.

## CI and a commit to the wrong place

CI found seven test failures when the PR went up. Most were pre-existing: the
`principles` bundle in the test fixtures was missing `testing-principles`, the guide
page had `/epic` as a prompt block — nine characters, below the ten-character
minimum — and `ALL_SKILLS` still listed `epic-start` and `epic-close` from a refactor
that was anticipated but never shipped. We regenerated the web app data, added overview
cards for the four new skills, and fixed the assertions.

One hiccup: the test fix commit went to main instead of the PR branch. I'd been on
main briefly and didn't switch back before committing. Rebasing the issue branch onto
the updated main and force-pushing cleaned it up, but it's the kind of thing that
leaves a slightly awkward rebase seam in the history.

The Structure validator is still failing in CI — exit code 2 (warnings only), which
the framework's own logic treats as passed (`returncode != 1`), but CI consistently
shows ❌. We looked at it for a while without finding the cause. It was already failing
on main before the PR. Not our problem to solve this session.

The PR was merged via rebase. `work-start`, `work-end`, `work-pause`, `work-resume`
are now in cc-praxis and synced to `~/.claude/skills/`. `epic` carries a deprecation
header. The old workflow keeps working; sessions that read the new skill listings will
find the unified commands.
