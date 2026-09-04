---
layout: post
title: "The Enum That Was Always a Path"
date: 2026-06-13
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-work]
tags: [path, scope, refactoring, multi-tenancy]
---

## The Enum That Was Always a Path

casehub-work had a `VocabularyScope` enum — four values: GLOBAL, ORG, TEAM, PERSONAL — controlling which label vocabularies a caller could see. Accessibility was ordinal comparison: `definitionScope.ordinal() <= callerScope.ordinal()`. It worked, but it was a linear four-step hierarchy hardcoded in an enum, sitting right next to `casehub-platform-api`'s `Path` type which already provides `isAncestorOf()`, `root()`, `depth()`, and tree-shaped scope traversal.

The platform boundary rule is explicit: "Do not define parallel path, scope, preference, or principal types." `VocabularyScope` was a parallel scope type. Replacing it with `Path` isn't a preference — it's removing a violation.

## What the Type Change Exposed

I expected a mechanical migration: swap enum for Path, update the JPA converter, write a Flyway migration, delete the enum. The actual shape was different.

The entity had two fields — `scope` (the enum) and `ownerId` (the identity within that scope level). They formed a compound key: `(TEAM, "hr-team")` uniquely identified a vocabulary. But with Path, the compound key collapses. `Path.of("acme-corp", "hr-team")` encodes both the tier (depth 2 = team-level) and the identity ("hr-team" under "acme-corp"). The ownerId field wasn't just replaceable — it was redundant from the moment the scope became a Path. Two sources of truth for the same information, with no consumer keeping them in sync.

The more interesting discovery was in `findGlobalVocabulary()`. The method did `scanAll().stream().filter(v -> v.scope == GLOBAL).findFirst()`. `scanAll()` is tenant-scoped — it filters by `currentPrincipal.tenancyId()`. The GLOBAL vocabulary was seeded by Flyway V3, but the tenancy migration (V35) stamped it with a fixed default tenant UUID. Any other tenant calling `findGlobalVocabulary()` got null → 500. A pre-existing multi-tenant bug hidden behind the enum-era special case.

With Path, root is just another path. No special case. `findOrCreateVocabulary(Path.root(), "Global")` auto-creates a root vocabulary per tenant on first use. The seeded row still works for the default tenant. The 500 disappears.

## The Race Nobody Had Tested

The store SPI — `LabelVocabularyStore` — had `get(UUID)`, `scanAll()`, `put()`, `delete()`. No targeted lookup by scope. `findOrCreateVocabulary` used `scanAll().stream().filter()` to find one vocabulary by scope — loading every vocabulary for the tenant to find a single row. That's an efficiency problem, but the deeper issue was atomicity.

`findOrCreate` is a classic TOCTOU: find returns empty, create inserts, but a concurrent request does the same between the find and the create. Without a database constraint, both succeed — orphaning the duplicate row.

The fix is three layers, each necessary:
- `findByScope(Path)` on the store SPI — targeted JPQL query, not scan-and-filter
- `UNIQUE(scope, tenancy_id)` in the migration — data integrity at the DB level
- `findOrCreate(Path, String)` on the store SPI — `@Transactional(REQUIRES_NEW)` isolates the insert, `EntityManager.clear()` resets the persistence context on constraint violation, re-query finds the winning thread's committed row

Each backend implements its own concurrency mechanism — JPA uses the transaction isolation, in-memory uses `synchronized`. The service layer delegates without knowing which.

The enum-to-Path migration started as a boundary rule cleanup and ended as a concurrency fix for a store SPI that was missing its natural key.
