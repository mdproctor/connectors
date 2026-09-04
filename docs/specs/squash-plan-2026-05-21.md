# Squash Plan — casehub-work fork → upstream
**Date:** 2026-05-21
**Range:** upstream/main..HEAD (33 commits → 14)
**Mode:** Flat compaction (Strategy D/E — no merge commits in range)

---

## Summary

```
33  commits (original)
-1  dropped (zero files changed: docs(epic-output-schema) journal no-op)
-18 absorbed by squash/merge
──────────────────────────────────────────────────
14  commits remaining — no content lost
```

---

## Already Clean — 0 commits

All commits in range require action (squash, merge, or drop).

---

## Group 1 — docs(claude): add Name field for write-blog auto-population

`4e4695b` — standalone, 1 file, CLAUDE.md only.

✅ KEEP `4e4695b` docs(claude): add Name field for write-blog project auto-population
> **Result:** 1 commit (message adequate)

---

## Group 2 — feat(template): findByRef, findByName, payloadOverride on instantiate

`28f6c5e` — standalone feature, no issue ref, 4 files.

✅ KEEP `28f6c5e` feat(template): findByRef, findByName, and payloadOverride on instantiate
> **Result:** 1 commit (message adequate)

---

## Group 3 — Named outcomes (#169)

*Compaction group — 2 commits → 1*

| Commit | Action | Curated result |
|--------|--------|----------------|
| `b2b9632` feat(template): named outcomes per WorkItemTemplate (Closes #169) | ✅ KEEP | *(message adequate — unchanged)* |
| `773771a` docs: sync CLAUDE.md and DESIGN.md for named outcomes (#169) | 🔽 SQUASH ↑ | *(absorbed — doc sync follow-on)* |

> **Result:** 1 commit.

---

## Group 4 — Schema-validated output (#170)

*Compaction group — 5 commits → 1 (+ 1 DROP)*

| Commit | Action | Curated result |
|--------|--------|----------------|
| `0f2ecc2` feat: merge epic-output-schema — template-level data schemas, WorkItemFormSchema deleted (#170) | ✅ KEEP | *(message adequate — unchanged)* |
| `0d6c529` feat(template): schema-validated output data (Closes #170) | 🔽 SQUASH ↑ | *(absorbed — branch commit already included in merge)* |
| `874042e` docs: sync DESIGN.md and CLAUDE.md for schema-validated output (#170) | 🔽 SQUASH ↑ | *(absorbed — doc sync follow-on)* |
| `adbf250` docs(epic-output-schema): promote design spec to project (Refs #170) | 🔽 SQUASH ↑ | *(absorbed — spec promotion follow-on)* |
| `6bdc4f9` docs: apply design journals for epics #170 and #171 — §Build Roadmap, §Flyway Migration History | 🔽 SQUASH ↑ | *(absorbed — journal application)* |
| `09c17aa` docs(epic-output-schema): apply design journal — 2026-05-17 (DESIGN.md already current via doc-sync) | ❌ DROP | *(zero file changes confirmed — commit message itself says DESIGN.md already current)* |

> **Result:** 1 commit (1 dropped).

---

## Group 5 — Conflict-of-interest exclusion enforcement (#171)

*Compaction group — 4 commits → 1*
**Final message:** `feat(api): conflict-of-interest exclusion — ExclusionPolicy SPI, excludedUsers field, V26/V27 migrations (Closes #171)`

| Commit | Action | Curated result |
|--------|--------|----------------|
| `989da78` feat: excluded users — conflict-of-interest enforcement (Closes #171) | ✅ KEEP | *(see Final message above)* |
| `7d1d75f` feat: conflict-of-interest enforcement — ExclusionPolicy SPI, excludedUsers field, V26/V27 migrations (#171) | 🔀 MERGE ↑ | *(unified — same feature, renumbered migrations V23/V24→V26/V27; detailed description folded into Final message)* |
| `0655198` fix: restore excluded-users feature lost in cherry-pick -X ours | 🔀 MERGE ↑ | *(unified — restoration of feature dropped during concurrent epic merge; implementation artifact)* |
| `baec733` docs(claude): flyway branch reservation rule + cherry-pick -X ours gotcha | 🔽 SQUASH ↑ | *(absorbed — CLAUDE.md follow-on documenting gotcha from this work)* |

> **Result:** 1 commit.

---

## Group 6 — fix(api): add permittedOutcomes to WorkItemWithAuditResponse (#181)

`6e6e06ee` — standalone fix, 2 files.

✅ KEEP `6e6e06ee` fix(api): add permittedOutcomes to WorkItemWithAuditResponse (Closes #181)
> **Result:** 1 commit (message adequate)

---

## Group 7 — fix: excluded-users minor gaps from #171 code review (#188)

`1c25174` — standalone fix, 3 files.

✅ KEEP `1c25174` fix: excluded-users minor gaps from #171 code review (Closes #188)
> **Result:** 1 commit (message adequate)

---

## Group 8 — fix(template): validate inputDataSchema/outputDataSchema (#183)

`a499518` — standalone fix, 2 files.

✅ KEEP `a499518` fix(template): validate inputDataSchema/outputDataSchema are JSON objects at creation (Closes #183)
> **Result:** 1 commit (message adequate)

---

## Group 9 — feat(template): UNIQUE constraint on WorkItemTemplate.name (#174)

`810c6a6` — standalone feature, 15 files, V28 migration.

✅ KEEP `810c6a6` feat(template): DB-level UNIQUE constraint on WorkItemTemplate.name (Closes #174)
> **Result:** 1 commit (message adequate)

---

## Group 10 — PolicyDecision SPI + blocked attempt audit (#186)

*Compaction group — 5 commits → 1*

| Commit | Action | Curated result |
|--------|--------|----------------|
| `93a1cc0` feat: merge epic-exclusion-audit — PolicyDecision SPI + blocked attempt audit (#186) | ✅ KEEP | *(message adequate — unchanged)* |
| `626ff50` feat(api): PolicyDecision SPI + blocked attempt audit (Closes #186) | 🔽 SQUASH ↑ | *(absorbed — branch commit already included in merge)* |
| `fa1965b` docs: sync DESIGN.md and CLAUDE.md to ExclusionPolicy SPI change (#186) | 🔽 SQUASH ↑ | *(absorbed — doc sync follow-on)* |
| `a530de7` feat: promote exclusion-policy audit design spec from epic-exclusion-audit | 🔽 SQUASH ↑ | *(absorbed — spec promotion follow-on)* |
| `e797a82` docs(epic-exclusion-audit): apply design journal — 2026-05-19 | 🔽 SQUASH ↑ | *(absorbed — journal application, 1-line DESIGN.md change)* |

> **Result:** 1 commit.

---

## Group 11 — WorkItemCreateRequest builder (#182)

*Compaction group — 4 commits → 1*

| Commit | Action | Curated result |
|--------|--------|----------------|
| `1f4d404` feat: WorkItemCreateRequest final class + enforced builder (Closes #182) | ✅ KEEP | *(message adequate — unchanged)* |
| `5648fcc` refactor(runtime): WorkItemCreateRequest final class + enforced builder; CreateWorkItemRequest inner Builder (Refs #182) | 🔽 SQUASH ↑ | *(absorbed — implementation step, full work included in merge)* |
| `4148a62` refactor: migrate all cross-module WorkItemCreateRequest call sites to builder (Refs #182) | 🔽 SQUASH ↑ | *(absorbed — call site migration step)* |
| `d91b497` fix(review): toBuilder test covers all 24 fields; restore intent comment in WorkItemMapper (Refs #182) | 🔽 SQUASH ↑ | *(absorbed — code review fix)* |

> **Result:** 1 commit.

---

## Group 12 — DESIGN.md exhaustive audit (#182 follow-on)

*Compaction group — 2 commits → 1*

| Commit | Action | Curated result |
|--------|--------|----------------|
| `989cff5` docs(design): exhaustive DESIGN.md update — #182 roadmap entry, V28 Flyway, corrected test counts all modules | ✅ KEEP | *(message adequate — standalone doc work worth preserving)* |
| `a2aca56` docs(claude): WorkItemCreateRequest enforced builder — no positional constructor (#182) | 🔽 SQUASH ↑ | *(absorbed — 1-line CLAUDE.md gotcha, follow-on to same work)* |

> **Result:** 1 commit.

---

## Group 13 — fix(core): SelectionContext 8th arg (#197)

*Compaction group — 2 commits → 1*

| Commit | Action | Curated result |
|--------|--------|----------------|
| `8c4c73a` fix(core): SelectionContext 8th arg in strategy tests (Closes #197) | ✅ KEEP | *(message adequate)* |
| `71ab531` fix(core): update SelectionContext constructor to 8 args in strategy tests (Closes #197, Refs #171) | 🔽 SQUASH ↑ | *(absorbed — branch commit already in merge)* |

> **Result:** 1 commit.

---

## Group 14 — fix(queues): claim uses query param not body (#196)

*Compaction group — 2 commits → 1*

| Commit | Action | Curated result |
|--------|--------|----------------|
| `6a6bd4d` fix(queues): claim endpoint uses query param, not body (Closes #196) | ✅ KEEP | *(message adequate)* |
| `84eb357` fix(queues): use query param for claim in WorkItemQueueEventTest (Closes #196, Refs #188) | 🔽 SQUASH ↑ | *(absorbed — branch commit already in merge)* |

> **Result:** 1 commit.

---

## AFTER — what git log --oneline will show (estimated)

```
33  commits (original)
-1  dropped (09c17aa — zero files)
-18 absorbed by squash/merge
──────────────────────────────────────────────────
14  commits remaining

  6a6bd4d  fix(queues): claim endpoint uses query param, not body (Closes #196)
  8c4c73a  fix(core): SelectionContext 8th arg in strategy tests (Closes #197)
  989cff5  docs(design): exhaustive DESIGN.md update — #182 roadmap entry, V28 Flyway, corrected test counts all modules
  1f4d404  feat: WorkItemCreateRequest final class + enforced builder (Closes #182)
  93a1cc0  feat: merge epic-exclusion-audit — PolicyDecision SPI + blocked attempt audit (#186)
  810c6a6  feat(template): DB-level UNIQUE constraint on WorkItemTemplate.name (Closes #174)
  a499518  fix(template): validate inputDataSchema/outputDataSchema are JSON objects at creation (Closes #183)
  1c25174  fix: excluded-users minor gaps from #171 code review (Closes #188)
  6e6e06ee fix(api): add permittedOutcomes to WorkItemWithAuditResponse (Closes #181)
  989da78  feat(api): conflict-of-interest exclusion — ExclusionPolicy SPI, excludedUsers field, V26/V27 migrations (Closes #171)
  0f2ecc2  feat: merge epic-output-schema — template-level data schemas, WorkItemFormSchema deleted (#170)
  b2b9632  feat(template): named outcomes per WorkItemTemplate (Closes #169)
  28f6c5e  feat(template): findByRef, findByName, and payloadOverride on instantiate
  4e4695b  docs(claude): add Name field for write-blog project auto-population
```
