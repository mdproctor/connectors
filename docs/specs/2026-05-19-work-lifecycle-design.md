# Work Lifecycle — Unified Design Spec
**Date:** 2026-05-19  
**Issue:** cc-praxis#94  
**Status:** Design approved — ready for implementation

---

## Context and Decisions

Replaces the fragmented `work-start` + `epic begin/close` + proposed pause/resume with four coherent commands. The `epic` skill is deprecated as user-facing; its close logic migrates into `work-end`. `/epic begin` continues to work during migration (it will be retired once `work-start` absorbs branch creation fully, tracked in cc-praxis#94).

**Confirmed decisions:**
- Branches (not worktrees) for all issue-scoped work
- Every branch gets `.meta` + `JOURNAL.md` — no lightweight vs epic distinction
- Branch name derived from issue: `issue-NNN-<slug>`, user confirms before creation
- Full pre-checks on every session including resume (revisit if friction emerges)
- Epic skill: parallel during migration, retired once stable
- GitHub rebase merge on PRs — linear history throughout

---

## The Four Commands

| Command | Replaces | Purpose |
|---------|----------|---------|
| `work-start` | `work-start` + `epic begin` | Entry point: detect state, create branch, run pre-checks |
| `work-end` | `epic close` | Close branch: promote artifacts, merge journal, mark closed |
| `work-pause` | (new) | Save context, switch both repos to main |
| `work-resume` | (new) | Restore context, switch both repos back to branch |

---

## Path Resolution (required at the start of every command)

Before any file operations or git commands, resolve paths from CLAUDE.md:

```bash
PROJECT=$(grep "add-dir" CLAUDE.md | head -1 | sed 's/.*add-dir //')
WORKSPACE=$(grep "^\*\*Workspace:\*\*" CLAUDE.md | head -1 | sed 's/.*`\(.*\)`.*/\1/')
```

**All file paths in this spec are absolute.** Never use CWD-relative paths. Never use bare `git` without `-C <path>`. Replace every `<workspace-path>` with `$WORKSPACE` and every `<project-path>` with `$PROJECT` in implementation.

---

## Shared: Branch Switch Helper

Referenced by all four commands. Never switch one repo alone.

```bash
# Resolve paths first (see Path Resolution above)

git -C "$PROJECT" checkout <branch>
git -C "$WORKSPACE" checkout <branch>

# Check remote ahead — PROMPT before pulling
PROJECT_BEHIND=$(git -C "$PROJECT" rev-list HEAD..origin/<branch> --count 2>/dev/null || echo 0)
WORKSPACE_BEHIND=$(git -C "$WORKSPACE" rev-list HEAD..origin/<branch> --count 2>/dev/null || echo 0)
if [ "$PROJECT_BEHIND" -gt 0 ] || [ "$WORKSPACE_BEHIND" -gt 0 ]; then
  echo "Remote has new commits (+${PROJECT_BEHIND} project, +${WORKSPACE_BEHIND} workspace)."
  echo "Incorporate now with pull --rebase? (y/n)"
  # Wait — user may not be ready for upstream changes
fi

# Verify alignment after switch
PROJECT_BRANCH=$(git -C "$PROJECT" branch --show-current)
WORKSPACE_BRANCH=$(git -C "$WORKSPACE" branch --show-current)
[ "$PROJECT_BRANCH" = "$WORKSPACE_BRANCH" ] \
  || { echo "⚠️ Mismatch after switch. Manual alignment required."; exit 1; }
echo "✅ Both repos on: $PROJECT_BRANCH"
```

If helper fails (branch absent in one repo, network error): hard stop with instructions. Do not loop.

---

## work-start

### Detection — checked in order, first match wins

Resolve paths first. Then check:

```bash
META_BRANCH=$(grep "^branch:" "$WORKSPACE/design/.meta" 2>/dev/null | sed 's/branch: //')
CURRENT_WORKSPACE=$(git -C "$WORKSPACE" branch --show-current)
CURRENT_PROJECT=$(git -C "$PROJECT" branch --show-current)
```

```
1. $WORKSPACE/design/.paused exists in the working tree
   → Hard stop. "Paused branch detected. Invoke work-resume first."

2. $WORKSPACE/design/.meta exists, AND
   META_BRANCH == CURRENT_WORKSPACE == CURRENT_PROJECT (all three match)
   → Resume path.

3. $WORKSPACE/design/.meta exists, CURRENT_WORKSPACE == main
   (orphaned — .meta on main, regardless of project branch)
   → Hard stop. "Invoke work-end to complete or discard the abandoned branch."
   *** Must be checked BEFORE state 4 — "orphaned on main" also satisfies
   "branches misaligned" so state 4 would fire incorrectly if checked first,
   attempting to switch to a branch that may have been deleted after PR merge. ***

4. $WORKSPACE/design/.meta exists, branches misaligned
   (META_BRANCH != CURRENT_WORKSPACE or CURRENT_PROJECT, and not orphaned)
   → Invoke Branch Switch Helper inline.
     If helper fails → hard stop with manual instructions (no loop).

5. CURRENT_WORKSPACE == main, no .meta, no .paused
   → New branch path.

6. On non-main branch, no .meta
   → "You are on <branch> with no branch scaffold.
      Continue here (y) or switch to main (n)?"
      y → run Steps 0, 2, 3, 11 only. No scaffold created. Skip Step 4 — no .meta exists to record the issue number.
            This path is for hotfixes or docs-only work that will not use work-end. If work-end is needed later, create .meta manually first.
      n → Branch Switch Helper to main, re-run detection.
```

### New branch path

**Step 0 — Resolve paths**  
Read `$PROJECT` and `$WORKSPACE` from CLAUDE.md (see Path Resolution).

**Step 1 — Work description**  
Use invocation argument if provided. Otherwise prompt: "Describe the work in one sentence."

**Step 2 — Platform coherence**  
Read platform doc. Run five coherence questions against the work description. Resolve any concerns before proceeding.

**Step 3 — Relevant protocols**  
Scan `docs/protocols/`. Read applicable rules. Resolve violations before proceeding.

**Step 4 — Issue**  
If tracking enabled:
- Search for existing open issue
- If found: confirm (`Found #N: <title>. Use this? y/n`)
- If not found: offer to create; wait for result
- Record issue number and title for Step 5

If tracking disabled: skip silently.

**Step 5 — Branch name**  
Derive: `issue-NNN-<slug>` (title lowercased, special chars stripped, max 30 chars).  
Show to user, allow override. Guards: reject `main`, `HEAD`, or any existing branch name.  
Branch number (NNN) is the stable key — title slug is a convenience only.

**Step 6 — Flyway V scan**  
```bash
git -C "$PROJECT" fetch --all 2>/dev/null || echo "⚠️ No network — scan incomplete"
```
If network available: scan main + all remote branches for claimed V numbers. Compute `next-safe-v = max + 1`. If conflict: warn, show `Next safe V: <N>`, block until acknowledged.

Default: `FLYWAY_NEXT_V=unknown` — the user may not know at branch creation time.  
Only ask if the user described migration work: "Will this branch include database migrations? (y/n)"  
→ y: `FLYWAY_NEXT_V=<N>`  
→ explicit n: `FLYWAY_NEXT_V=none`  
→ no answer: `FLYWAY_NEXT_V=unknown` (safe default)

**Step 7 — Create branches (atomic)**  
```bash
git -C "$PROJECT" checkout -b <branch-name>
# If fails → abort (nothing to clean up)
git -C "$WORKSPACE" checkout -b <branch-name>
# If fails → git -C "$PROJECT" branch -D <branch-name>, abort, report error
```

**Step 8 — Resolve design routing and SHA baseline**  
Read routing config (3-layer cascade) for `design` artifact:
- If `design → workspace`: `DESIGN_REPO="$WORKSPACE"`, baseline = `git -C "$WORKSPACE" rev-parse main`, `DESIGN_REPO_KEY=workspace`
- If `design → project` (default): `DESIGN_REPO="$PROJECT"`, baseline = `git -C "$PROJECT" rev-parse HEAD`, `DESIGN_REPO_KEY=project`

`DESIGN_REPO_KEY` is stored in `.meta` as `design-repo:` so work-end can use it directly without re-deriving from routing config — which may have changed between sessions. Work-end reads `design-repo:` from `.meta` and sets `$DESIGN_REPO` accordingly.

**Step 9 — Scaffold**  
```bash
mkdir -p "$WORKSPACE/design"
```

`$WORKSPACE/design/JOURNAL.md`:
```markdown
# Design Journal — <branch-name>
```

`$WORKSPACE/design/.meta`:
```
branch: <branch-name>
project-sha: <baseline SHA from Step 8>
date: <YYYY-MM-DD>
issue: <N or blank>
flyway-next-v: <N | none | unknown>
design-repo: <workspace | project>
design-section-hashes: <pipe-separated hash:heading pairs, or blank>
```

`design-repo:` is the stable source of truth for `$DESIGN_REPO` across sessions — used by work-end Step 3 instead of re-deriving from routing config.

Section hashes — stored as a single pipe-separated string on one line (no multi-line format):
```bash
HASHES=$(grep "^## " "$DESIGN_REPO/DESIGN.md" 2>/dev/null \
  | while read h; do printf "%s:%s|" "$(printf '%s' "$h" | shasum -a 256 | cut -c1-8)" "$h"; done)
```
Example value: `abc12345:## Architecture|def67890:## Data Model|`

Use `shasum -a 256` (portable across macOS and Linux, available via Git's bundled tools). Stored on a single line so standard `grep "^design-section-hashes:"` reads it back without multi-line parsing.

Work-end Step 5b reads it back:
```bash
STORED=$(grep "^design-section-hashes:" "$WORKSPACE/design/.meta" | sed 's/design-section-hashes: //')
# Re-hash current headings in the same format and compare
```

**Step 10 — Commit and push scaffold**  
```bash
git -C "$WORKSPACE" add design/JOURNAL.md design/.meta
git -C "$WORKSPACE" commit -m "init(<branch-name>): scaffold workspace branch"
git -C "$WORKSPACE" push  # non-fatal if fails; warn and continue
```

**Step 11 — IntelliJ MCPs**  
Call `mcp__intellij-index__ide_index_status` and `mcp__intellij__get_project_modules`. Hard stop if unavailable.

**Step 12 — Offer brainstorming**  
"Start a brainstorm? (y/n)" — if yes, specs write to `$WORKSPACE/specs/<branch-name>/`.

**Specs routing:** `specs` always routes to `project` (`docs/specs/`). Not user-configurable. The three-layer cascade covers blog/adr/snapshots/plans/design only.

### Resume path (Detection state 2)

Surface `.meta`:
```
⚡ Resuming: <branch-name>  Issue: #<N>  Started: <date>
   Flyway V: <N | none | unknown>
   Project: <branch>  Workspace: <branch>
```
Run Steps 0, 2, 3, 11 only. Skip all branch creation steps.

---

## work-end

### Pre-conditions

Resolve paths (Step 0). Then check in order:

1. **If `$WORKSPACE/design/.paused` exists** → hard stop. "You have a paused branch. Invoke `work-resume` first, then `work-end`."
2. **Must be on a branch where `$WORKSPACE/design/.meta` exists** → proceed.
3. **If `$WORKSPACE/design/.meta` exists but current workspace branch is `main`** (orphaned) → hard stop. Offer to switch to surviving epic branch and close from there, or discard.

### Step 0 — Resolve paths
Read `$PROJECT`, `$WORKSPACE` from CLAUDE.md. Read `$OWNER_REPO` from `GitHub repo:` line.

### Step 1 — Read context and extract variables
```bash
cat "$WORKSPACE/design/.meta"   # branch, project-sha, issue, flyway-next-v

BRANCH_NAME=$(grep "^branch:" "$WORKSPACE/design/.meta" | sed 's/branch: //')
PROJECT_SHA=$(grep "^project-sha:" "$WORKSPACE/design/.meta" | sed 's/project-sha: //')
ISSUE_N=$(grep "^issue:" "$WORKSPACE/design/.meta" | sed 's/issue: //')
```

`$BRANCH_NAME`, `$PROJECT_SHA`, and `$ISSUE_N` are used throughout Steps 3–10. Extract once here, never re-read from `.meta`.

### Step 2 — Flyway V re-scan

Re-scan at close time — another branch may have claimed the same V numbers since branch creation.

```bash
git -C "$PROJECT" fetch --all 2>/dev/null || echo "⚠️ No network — scan skipped"
```

If conflict: `[R]` renumber affected migration files, `[A]` abort. Block close until resolved.

### Step 3 — Resolve routing and set DESIGN_REPO

Three-layer cascade for each artifact type. Warn on deprecated vocabulary (`base`, `project repo`, `design-journal`). Show resolved table, user confirms before proceeding.

**Capability detection** — for each resolved destination:
```bash
detect_capability() {
  local dest="$1"
  if [ -d "$dest/.git" ]; then
    git -C "$dest" remote get-url origin &>/dev/null 2>&1 \
      && echo "remote-git" || echo "local-git"
  else
    echo "filesystem"
  fi
}
```

**Specs routing is non-configurable** — specs always route to `project` (`$PROJECT/docs/specs/`), regardless of the routing table. If the cascade resolves `specs → workspace`, override it to `project` with a warning: "Specs routing overridden to project — specs routing is not user-configurable."

Set `$DESIGN_REPO` from `.meta` — do NOT re-derive from routing config (which may have changed since the branch was created):
```bash
DESIGN_REPO_KEY=$(grep "^design-repo:" "$WORKSPACE/design/.meta" | sed 's/design-repo: //')
[ "$DESIGN_REPO_KEY" = "workspace" ] && DESIGN_REPO="$WORKSPACE" || DESIGN_REPO="$PROJECT"
```

**`$DESIGN_REPO` scope:** This variable must be available through Step 8d. Do not recalculate it in subsequent steps — use the value set here. Steps 4–8c do not change the routing.

### Step 4 — Inventory artifacts
```bash
ls "$WORKSPACE/adr/" | grep -v INDEX.md
ls "$WORKSPACE/blog/" | grep -v INDEX.md
ls "$WORKSPACE/snapshots/" | grep -v INDEX.md
ls "$WORKSPACE/specs/<branch-name>/" 2>/dev/null
ls "$WORKSPACE/plans/" | grep -v "^attic$"
cat "$WORKSPACE/design/JOURNAL.md"
```

### Step 5 — Journal validation

**5a — DESIGN.md existence**  
If `$DESIGN_REPO/DESIGN.md` missing: `[C]` create from journal entries, `[S]` skip merge.

**5b — Section heading drift**  
Re-hash H2 headings in `$DESIGN_REPO/DESIGN.md`. Compare against `design-section-hashes` in `.meta`. For each §Section anchor in JOURNAL.md, verify its heading still exists unchanged.  
If drift: `[U]` update journal anchors, `[S]` skip drifted sections, `[A]` abort.

**5c — Anchor validation**  
Count `^### .*·.*§` lines vs total `^### ` lines in JOURNAL.md.  
If any entries lack anchors: `[F]` fix via java-update-design, `[S]` skip merge, `[C]` continue accepting loss.

**5d — Empty journal**  
If no entries at all: `[W]` write retrospective via java-update-design, `[S]` skip and accept permanent loss.

### Step 6 — Select specs for GitHub posting

If tracking enabled, list `$WORKSPACE/specs/<branch-name>/`, ask which to post. Skip silently if disabled.

### Step 7 — Present close plan

```
work-end close plan — <branch-name>

  Flyway V check     ✅ no conflicts
  Artifact routing
  ├── adr/<N>        → project      [remote-git]
  ├── blog/<N>       → workspace    [remote-git]
  ├── specs/<N>      → project      [remote-git]
  └── snapshots/<N>  → workspace    [remote-git]
  Plan archiving     → plans/attic/<branch-name>/  [workspace main]
  Journal merge      → DESIGN.md  (<N> sections)
  Spec posting       → #<N>  (<filenames>)
  Issue              → close #<N>
  Publish blog       → offer after (N entries staged)

Approve all, or step by step? (all / step)
```

### Step 8 — Execute

Failures are reported but do not stop remaining steps, **except**: journal merge failure prompts before continuing to issue close.

**8a — Batch workspace-main operations** (single main-visit):
```bash
# Stash any uncommitted workspace changes
git -C "$WORKSPACE" status --short | grep -q . && git -C "$WORKSPACE" stash

git -C "$WORKSPACE" checkout main
git -C "$WORKSPACE" pull --rebase origin main

# Promote workspace-routed artifacts (blog, snapshots)
# artifact-files are paths relative to $WORKSPACE root, taken from the Step 4 inventory
# filtered by routing destination = workspace. E.g. "blog/2026-05-19-entry.md"
for each workspace-routed artifact:
  mkdir -p "$WORKSPACE/<dest>/"
  git -C "$WORKSPACE" checkout "$BRANCH_NAME" -- <artifact-files>
  git -C "$WORKSPACE" add "<dest>/"
  git -C "$WORKSPACE" commit -m "feat: promote <type> from $BRANCH_NAME"

# Archive plans to attic
if plans exist:
  git -C "$WORKSPACE" checkout <branch-name> -- plans/<files>
  mkdir -p "$WORKSPACE/plans/attic/<branch-name>"
  mv "$WORKSPACE/plans/<files>" "$WORKSPACE/plans/attic/<branch-name>/"
  git -C "$WORKSPACE" add -A
  git -C "$WORKSPACE" commit -m "archive(<branch-name>): move plans to attic"

git -C "$WORKSPACE" push  # single push for all workspace-main commits

git -C "$WORKSPACE" checkout <branch-name>
git -C "$WORKSPACE" stash pop  # only if stashed above
```

**8b — Project-routed artifact promotion** (ADRs, specs):
```bash
for each project-routed artifact:
  mkdir -p "$PROJECT/<dest>/"
  cp "$WORKSPACE/<artifact-file>" "$PROJECT/<dest>/"
  git -C "$PROJECT" add "<dest>/"
  git -C "$PROJECT" commit -m "feat: promote <type> from <branch-name>"
  git -C "$PROJECT" push  # non-fatal if fails; report exit code
```

**8c — Spec cleanup** (only if 8b push exit code was 0):
If 8b push failed, skip entirely — workspace copy is the only remaining copy.
```bash
rm -rf "$WORKSPACE/specs/<branch-name>/"
git -C "$WORKSPACE" add -A
git -C "$WORKSPACE" commit -m "chore(<branch-name>): remove promoted specs from staging"
git -C "$WORKSPACE" push
```

**8d — Journal merge**:
Uses `$DESIGN_REPO` (set in Step 3) and `$PROJECT_SHA` (set in Step 1).

**⚠️ Branch context matters:** When `$DESIGN_REPO = "$WORKSPACE"` (design → workspace routing), the merge MUST happen while workspace is still on **main** (during the 8a main-visit), not after returning to the epic branch. If the merge is committed to the workspace epic branch, it will be discarded at close. For `$DESIGN_REPO = "$PROJECT"`, the merge happens on the project epic branch and is included in the PR — correct.

Implementation:
- If `$DESIGN_REPO = "$WORKSPACE"`: perform the journal merge during 8a while on workspace main. After artifact promotion, cherry-pick `design/JOURNAL.md` from the epic branch (`git -C "$WORKSPACE" checkout "$BRANCH_NAME" -- design/JOURNAL.md`), run the merge, commit to workspace main, then skip 8d.
- If `$DESIGN_REPO = "$PROJECT"`: run 8d as written below (project is on its epic branch throughout).

Steps (for the `$DESIGN_REPO = "$PROJECT"` case, or after cherry-pick for workspace case):
1. Read baseline: `git -C "$DESIGN_REPO" show "$PROJECT_SHA":DESIGN.md`
2. Read current `$DESIGN_REPO/DESIGN.md`
3. Apply journal narrative per §Section, preserving independent main-branch changes
4. Write merged result
5. Post-merge verification: re-read each §Section; present to user (`[A]` accept, `[R]` redo, `[X]` abort) before committing
6. `git -C "$DESIGN_REPO" add DESIGN.md && git -C "$DESIGN_REPO" commit -m "docs($BRANCH_NAME): apply design journal" && git -C "$DESIGN_REPO" push`

If journal merge fails: prompt user before continuing to issue close.

**8e — Spec posting**: post selected specs as collapsible comments on GitHub issue.

**8f — Issue close** (only if tracking enabled and `$ISSUE_N` is non-empty):
```bash
[ -n "$ISSUE_N" ] && gh issue close "$ISSUE_N" --repo "$OWNER_REPO"
```

**8g — Offer publish-blog**: if blog entries were staged to workspace.

**8h — Final report**:
```
✅ ADRs → project
✅ Specs → project  
✅ Blog → workspace
✅ Plans → attic
✅ Journal merged → DESIGN.md (N sections)
✅ Specs posted to #N, issue closed
❌ Push failed — <path>. Run: git -C <path> push
```

**8i — Offer hygiene scan**: "Run branch hygiene scan? Checks Flyway conflicts, unmerged code, stale branches. (y/n)"

### Step 8 (step path)

Phase 1: Artifact routing — confirm, execute, report → "Continue to journal merge? (y/n)"  
Phase 2: Journal merge — show each §Section before/after, confirm → "Continue to GitHub posting? (y/n)"  
Phase 3: Spec posting, issue close, publish-blog offer → "Continue to branch cleanup? (y/n)"  
Phase 4: EPIC-CLOSED.md and return to main.

### Step 9 — Mark closed

```bash
cat > "$WORKSPACE/EPIC-CLOSED.md" << EOF
# Branch Closed — <branch-name>
**Date:** <YYYY-MM-DD>
**Issue:** #<N>
**Scheduled for deletion:** <date + 14 days>
EOF
git -C "$WORKSPACE" add EPIC-CLOSED.md
git -C "$WORKSPACE" commit -m "docs(<branch-name>): mark closed, deletion due <date>"
git -C "$WORKSPACE" push
```

Branches are **not deleted**. `EPIC-CLOSED.md` is the signal for hygiene scan cleanup.

### Step 10 — Return to main

```
Return both repos to main? (y/n)
```
If y:
```bash
git -C "$PROJECT" checkout main
git -C "$WORKSPACE" checkout main
```
Check remote ahead; prompt before `pull --rebase`. Not automatic.

---

## work-pause

### Step 0 — Resolve paths
Read `$PROJECT`, `$WORKSPACE` from CLAUDE.md.

### Step 1 — Validate state
Must be on a branch where `$WORKSPACE/design/.meta` exists.  
If `$WORKSPACE/design/.paused` already exists: hard stop — only one paused branch at a time.

### Step 2 — Handle uncommitted changes
```bash
PROJECT_DIRTY=$(git -C "$PROJECT" status --short | grep -c .)
WORKSPACE_DIRTY=$(git -C "$WORKSPACE" status --short | grep -c .)
```
If either is dirty:
```
Uncommitted changes found. Stash them? (y/n)
  n → abort (commit or discard first)
```
If y:
```bash
STASH_PROJECT=none
STASH_WORKSPACE=none
[ "$PROJECT_DIRTY" -gt 0 ] && git -C "$PROJECT" stash && STASH_PROJECT="stash@{0}"
[ "$WORKSPACE_DIRTY" -gt 0 ] && git -C "$WORKSPACE" stash && STASH_WORKSPACE="stash@{0}"
```
**Note to user at pause time:** If you run `git stash` or `git stash pop` manually on either repo before resuming, the recorded stash position will shift and restoration will fail.

### Step 3 — Record pause in .meta (atomic with Step 4)
```bash
cat >> "$WORKSPACE/design/.meta" << EOF
paused: true
paused-at: $(date -u +%Y-%m-%dT%H:%M:%SZ)
paused-issue: <N if working on different issue>
stash-project: $STASH_PROJECT
stash-workspace: $STASH_WORKSPACE
EOF
git -C "$WORKSPACE" add design/.meta
git -C "$WORKSPACE" commit -m "chore(<branch-name>): pause"
git -C "$WORKSPACE" push
```
**If push fails: abort before Step 4.** Do not write `.paused` to main.

### Step 4 — Write .paused to workspace main
```bash
# Workspace stash already on stack from Step 2 if applicable — do NOT stash again
git -C "$WORKSPACE" checkout main
git -C "$WORKSPACE" pull --rebase origin main

mkdir -p "$WORKSPACE/design"
cat > "$WORKSPACE/design/.paused" << EOF
branch: <branch-name>
paused-at: $(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF

git -C "$WORKSPACE" add design/.paused
git -C "$WORKSPACE" commit -m "chore: pause marker for <branch-name>"
git -C "$WORKSPACE" push
```

### Step 5 — Switch project repo to main
```bash
git -C "$PROJECT" checkout main
```
Prompt before remote pull.

### Step 6 — Confirm
```
⏸  Paused: <branch-name>  Both repos on main.
   Resume with: work-resume
```

---

## work-resume

### Step 0 — Resolve paths
Read `$PROJECT`, `$WORKSPACE` from CLAUDE.md.

### Step 1 — Check .paused
```bash
cat "$WORKSPACE/design/.paused" 2>/dev/null || { echo "Nothing to resume."; exit 1; }
RESUME_BRANCH=$(grep "^branch:" "$WORKSPACE/design/.paused" | sed 's/branch: //')
```

### Step 2 — Stale check
```bash
git -C "$WORKSPACE" branch -a | grep -q "$RESUME_BRANCH"
git -C "$PROJECT" branch -a | grep -q "$RESUME_BRANCH"
```
If missing from either: `[D]` discard `.paused` and clean up, `[A]` abort.

### Step 3 — Switch both repos to epic branch
Use Branch Switch Helper with `$RESUME_BRANCH`. Prompt before remote pull.

### Step 4 — Remove .paused from workspace main
```bash
# Stash any uncommitted workspace changes (now on epic branch)
git -C "$WORKSPACE" status --short | grep -q . && git -C "$WORKSPACE" stash

git -C "$WORKSPACE" checkout main
git -C "$WORKSPACE" pull --rebase origin main
rm "$WORKSPACE/design/.paused"
git -C "$WORKSPACE" add -A
git -C "$WORKSPACE" commit -m "chore: resume $RESUME_BRANCH, remove pause marker"
git -C "$WORKSPACE" push

git -C "$WORKSPACE" checkout "$RESUME_BRANCH"
git -C "$WORKSPACE" stash pop 2>/dev/null || true  # only if stashed above
```

### Step 5 — Restore stashed changes
Read from `.meta` and pop the specific recorded stash reference:
```bash
STASH_PROJECT=$(grep "^stash-project:" "$WORKSPACE/design/.meta" | sed 's/stash-project: //')
STASH_WORKSPACE=$(grep "^stash-workspace:" "$WORKSPACE/design/.meta" | sed 's/stash-workspace: //')

# Use the recorded stash reference — do NOT use bare stash pop (pops wrong stash if stack shifted)
[ "$STASH_PROJECT" != "none" ] && \
  git -C "$PROJECT" stash pop "$STASH_PROJECT" 2>/dev/null \
  || { [ "$STASH_PROJECT" != "none" ] && echo "⚠️ Project stash pop failed ($STASH_PROJECT) — resolve manually"; }

[ "$STASH_WORKSPACE" != "none" ] && \
  git -C "$WORKSPACE" stash pop "$STASH_WORKSPACE" 2>/dev/null \
  || { [ "$STASH_WORKSPACE" != "none" ] && echo "⚠️ Workspace stash pop failed ($STASH_WORKSPACE) — resolve manually"; }
```

### Step 6 — Clear pause flags from .meta
```bash
sed -i '' '/^paused:/d; /^paused-at:/d; /^paused-issue:/d; /^stash-project:/d; /^stash-workspace:/d' \
  "$WORKSPACE/design/.meta"
git -C "$WORKSPACE" add design/.meta
git -C "$WORKSPACE" commit -m "chore($RESUME_BRANCH): clear pause flags from .meta"
git -C "$WORKSPACE" push
```

### Step 7 — Surface context
```
▶  Resumed: <branch-name>  Issue: #<N>  Paused <duration> ago
   Stash restored: <yes | no | conflict — resolve manually>
```

### Step 8 — Run pre-checks
Steps 0, 2, 3, 11 from work-start (path resolution, platform coherence, protocols, IntelliJ).

---

## Epic skill — Deprecation

`epic/SKILL.md` gains a deprecation header:
```
⚠️  This skill is deprecated. Use:
    work-start  — for /epic begin
    work-end    — for /epic close
    work-pause  — new
    work-resume — new

The workflows below are preserved for reference during migration.
```

Skill remains functional during migration. Retired once all sessions use the new commands and no issues are found.

**Gate text migration:** The current work-start gate says "Option 1 (branch work) → invoke `/epic begin`". Once the new skills are deployed, update this to "branch creation is handled by work-start itself — proceed."

---

## Required Skill Updates (non-negotiable for correctness)

### java-update-design — workspace mode detection

**Current (broken for `issue-NNN-*` branches):** checks `epic-*` branch prefix.

**Required:**
```bash
# All three must be true; branch name prefix is NOT checked
[ -f "$WORKSPACE/design/.meta" ]
[ -f "$WORKSPACE/design/JOURNAL.md" ]
[ "$(git -C "$WORKSPACE" branch --show-current)" != "main" ]
```

Without this fix, every commit on an `issue-NNN-*` branch silently writes directly to `DESIGN.md`. The journal mechanism is entirely bypassed with no error.

### Branch Hygiene Scan — detection by .meta, not branch name

**Current (broken):** `git branch | grep 'epic-'`

**Required:**
```bash
for branch in $(git -C "$WORKSPACE" branch | sed 's/^[* ]*//' | grep -v '^main$'); do
  git -C "$WORKSPACE" show "$branch:design/.meta" 2>/dev/null && echo "$branch"
done
```

---

## Branch Hygiene Scan

Moved to the handover wrap checklist (epic hygiene item). Also offered by `work-end` Step 8i after close.

**Detection:** Find branches by `.meta` presence, not by `epic-*` name prefix.

Checks per branch: Flyway V conflicts across all open branches, unmerged code on project branch, PR status, EPIC-CLOSED.md existence and deletion date, artifact promotion status (specs, plans).

Blocks deletion of any branch with unmerged code. Reports only — does not auto-delete.

---

## .meta Schema

```
branch: <branch-name>
project-sha: <SHA — routing-aware baseline>
date: <YYYY-MM-DD>
issue: <N or blank>
flyway-next-v: <N | none | unknown>
design-repo: <workspace | project>
design-section-hashes: <pipe-separated hash:heading pairs — e.g. abc12345:## Architecture|def67890:## Data Model|>
paused: true                              # only present when paused
paused-at: <ISO timestamp>                # only present when paused
paused-issue: <N>                         # only present when paused on different issue
stash-project: <stash@{N} | none>         # only present when paused
stash-workspace: <stash@{N} | none>       # only present when paused
```

---

## Known Limitations

- Only one paused branch at a time
- Stash reference uses `stash@{N}` stack position — if the user manually stashes or pops between pause and resume, the recorded position shifts and restoration fails. User is warned at pause time.
- If project repo stash is accidentally popped during an interruption, it cannot be recovered
- `paused-issue` is informational only — not enforced by work-start
- Flyway V scan requires network; `unknown` V is a manual verification burden
- **Flyway scan staleness in concurrent sessions:** The family workspace runs multiple Claude sessions simultaneously. The Flyway V scan at `work-end` may be stale if another session merged between `work-start` and `work-end`. Re-scan at close time mitigates but does not eliminate the race.
- **ADRs promoted after PR merge:** If the PR is merged before `work-end` runs, ADR promotion commits directly to project main rather than as part of the PR diff. This is expected behaviour — ADRs committed to the project epic branch during development are part of the PR; those staged in workspace and promoted at close are post-PR additions.
- `work-start` gate text currently says "invoke `/epic begin`" — update when deploying new skills (see Epic skill — Deprecation)
