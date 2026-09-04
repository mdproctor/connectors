---
layout: post
title: "Filtering Out the Enum"
date: 2026-06-16
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-work]
tags: [path, scope, queues, refactoring]
---

## Filtering Out the Enum

PR #236 replaced `VocabularyScope` with `Path` in the casehub-work runtime module three days ago. `FilterScope` was the same mistake in the queues module — PERSONAL, TEAM, ORG hardcoded as enum values when a hierarchical path type that already does this exists in `casehub-platform-api`. I brought Claude in to help work through the 58 call sites and we found three things worth noting beyond the mechanical replacements.

**The dead interface method**

`WorkItemFilterBean` had a `scope()` method — returning `FilterScope`, theoretically indicating which scope a CDI lambda filter applied to. When I checked whether anything called it, the answer was no. `FilterEngineImpl` invokes `bean.matches(workItem)` and `bean.actions()`. `LambdaFilterRegistry.all()` returns every bean. `scope()` was defined, implemented in two places, and completely ignored.

The reason it was always going to be ignored: `WorkItems` have no scope field. There's nothing for the engine to match a filter's declared scope *against*. The actual discriminator for a lambda filter is `matches(WorkItem)` — if a filter only applies in a particular context, that condition belongs in the match logic. We deleted the method. The interface is two methods now and cleaner for it.

**Scope as metadata, not predicate**

After the entity changes, the spec needed a precise statement about what `WorkItemFilter.scope` and `QueueView.scope` actually mean at runtime. The first draft said "scope governs management visibility." True, but incomplete — it implies scope currently restricts who sees what. It doesn't. `findActive()` returns all active tenant filters regardless of scope. `scanAll()` returns all queue views. The engine evaluates every active filter against every WorkItem.

The version that survived review: scope is stored metadata with no current enforcement, and its intended future role is access control above the store layer, not an execution predicate inside it. The critical sentence was "a future implementor adding a scope predicate to `findActive()` would be introducing a new execution semantic, not completing deferred enforcement." "Deferred enforcement" implies something already designed and just not wired. The actual state is that the execution model ignores scope entirely, and any change that starts using it is genuinely new behaviour. Getting that distinction into a sentence that couldn't be misread took three rounds.

**The import collision**

`jakarta.ws.rs.Path` (annotation) and `io.casehub.platform.api.path.Path` (scope type) share the simple name `Path`. Java can't import both. VocabularyResource already handled this in #236 by using the fully qualified name `io.casehub.platform.api.path.Path` in method bodies and keeping only the JAX-RS import. The pattern propagates here to FilterResource, QueueResource, and six scenario files.

One thing we left consistent rather than fixing in isolation: `Path.parse()` can throw `IllegalArgumentException` with a null message, which produces `"invalid scope: null"` in the 400 response body. All three resources that do scope parsing — VocabularyResource, FilterResource, QueueResource — have the same pattern. A null-safe fallback would be two lines per resource, but diverging in one without fixing the others creates inconsistency. That's a three-resource cleanup, not a one-PR fix.

The PR is on the fork. 20 files, 104 tests green, `FilterScope` gone.
