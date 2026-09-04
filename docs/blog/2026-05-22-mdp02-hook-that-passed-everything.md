---
layout: post
title: "The Hook That Passed Everything"
date: 2026-05-22
type: phase-update
entry_type: note
subtype: log
projects: [casehub-work]
tags: [git, squash, hooks, rebase]
---

The pre-push hook checks for squash candidates before anything leaves the machine. The patterns in it look reasonable: `^docs\(claude\):`, `^fix\(test\):`, `^chore:`. It had been in place for weeks. Every push sailed through.

We ran the squash check manually today and found 43 commits in the range with candidates the hook should have flagged. The hook had passed all of them.

The problem is that `git log --oneline` prepends the abbreviated SHA to every line:

```
db3d59f docs(claude): remove hardcoded username from git workflow
7272117 docs(claude): blog routing points to ~/.claude/blog-routing.yaml
```

The `^` anchor in `^docs\(claude\):` matches the start of the line — which is the hash. Nothing ever matched because nothing ever starts with `docs(claude):`. Replace `--oneline` with `--format=%s` and the patterns work correctly. A three-character change, broken for weeks.

The squash itself took longer. The commit range had merge commits — two-parent commits from short-lived fix branches — and those don't behave like regular commits under `git rebase -i`.

The first attempt used `fixup` on them. Git was explicit: `error: cannot squash merge commit into another commit`. Clear enough.

The second attempt used `drop`. The rebase printed `BUG: sequencer.c:2725: unexpected todo_command` to stderr, then exited with code 0. The branch was unchanged. No error, no failure, just nothing. We only caught it because the commit count after the "rebase" was identical to before.

The fix is `--no-rebase-merges`. With that flag, merge commits don't appear in the todo at all — they're linearised away automatically. The regular commits in the range are replayed; the merge commits aren't picked up or dropped, they simply cease to exist. 43 commits became 23.

Separately, `epic-excluded-users` got its formal close. The feature had been in main for five days, but the design spec written at the start of the epic had never been promoted from the branch to `docs/specs/`. Easy to miss — the spec was committed to the epic branch of the project repo, not the workspace, and the promotion step was skipped when the feature was merged. We caught it by diffing the epic branch against main and checking what files existed on the epic but not on main.

One spec file. One `git show` to a file. One commit. Branch closed.
