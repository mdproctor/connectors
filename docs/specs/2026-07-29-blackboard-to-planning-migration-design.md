# Blackboard → Planning Migration

**Date:** 2026-07-29
**Root issue:** casehubio/engine#811
**Status:** Design approved

## Problem

engine#60 introduced `casehub-engine-planning` as the successor to `casehub-engine-blackboard` but never propagated the rename to downstream repos. The old `casehub-engine-blackboard` artifact is orphaned (not in engine's reactor) and its published SNAPSHOT on GitHub Packages is stale (last updated 2026-05-01). CI fails for any repo that transitively depends on it — AML is the first casualty.

## Verified Impact (IntelliJ workspace scan, 2026-07-29)

### Code-level changes (import + pom)

| Repo | Module | Files | Classes used |
|------|--------|-------|-------------|
| casehub-work | engine-adapter | 3 prod + 4 test (7 files) | `CasePlanModel`, `PlanItem`, `BlackboardRegistry` |

### POM-only changes (no Java imports)

| Repo | File(s) |
|------|---------|
| parent | `pom.xml` (BOM `<dependencyManagement>`) |
| aml | `app/pom.xml` |
| life | `app/pom.xml` |
| soc | `app/pom.xml` |
| iot | `pom.xml`, `webapp/pom.xml` |
| ops | `app/pom.xml` |
| fsitrading | `app/pom.xml` |
| quarkmind | `pom.xml` |
| scaffold | `pom.xml` |

### Cleanup only

| Repo | Action |
|------|--------|
| engine | Delete orphaned `blackboard/` directory |

### No impact

claudony, quarkus-flow, qhorus — verified no dependency or imports.

## Source Compatibility (verified via IntelliJ)

The 3 classes casehub-work uses from blackboard exist in planning with identical method signatures:

| Old package | New package | Class |
|-------------|-------------|-------|
| `io.casehub.blackboard.plan` | `io.casehub.engine.planning.plan` | `PlanItem` |
| `io.casehub.blackboard.plan` | `io.casehub.engine.planning.plan` | `CasePlanModel` |
| `io.casehub.blackboard.registry` | `io.casehub.engine.planning.registry` | `BlackboardRegistry` |

Every method casehub-work calls (`getPlanItemId()`, `getBindingName()`, `getStatus()`, `getTarget()`, `getCreatedAt()`, `markDelegated()`, `markCompleted()`, `markRejected()`, `markFaulted()`, `markObsolete()`, `markCancelled()`, `markSuspended()`, `markResumed()`, `getPlanItemByBindingName()`, `getPlanItem()`, `get(UUID)`) exists in planning with the same signature. Pure import swap — no code changes beyond imports.

## Approach: Big-bang worktree

All changes made in a single worktree slot across all 11 repos, verified locally, pushed in dependency order.

### Execution steps

1. **parent** — replace `casehub-engine-blackboard` with `casehub-engine-planning` in BOM → `mvn install -N`
2. **casehub-work/engine-adapter** — swap pom dependency, update `<description>` ("blackboard" → "planning"), swap imports in 7 files (3 prod + 4 test) → `mvn install -pl engine-adapter`
3. **app repos** (aml, life, soc, iot, ops, fsitrading, quarkmind) — swap artifact ID in pom.xml; update stale XML comments referencing blackboard (quarkmind) → `mvn compile` each
4. **scaffold** — swap artifact ID in template pom
5. **engine** — delete `blackboard/` directory

### Push order (SNAPSHOT dependency chain)

1. parent (BOM publishes first)
2. engine (cleanup, no downstream impact)
3. casehub-work (engine-adapter SNAPSHOT publishes)
4. app repos + scaffold (parallel — resolve from published SNAPSHOTs)

### Verification

- Each module compiles clean after the swap
- AML CI green post-push (the original failure)

## Issue structure

- **casehubio/engine#811** — root issue (already exists, updated with full impact)
- Child issues per repo: parent, work, aml, life, soc, iot, ops, fsitrading, quarkmind, scaffold, engine (cleanup)
