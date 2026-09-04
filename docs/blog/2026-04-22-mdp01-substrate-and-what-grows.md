---
layout: post
title: "The substrate and what grows on it"
date: 2026-04-22
type: phase-update
entry_type: note
subtype: diary
projects: [quarkus-work]
tags: [architecture, ai-native, casehub, semantic-routing, langchain4j]
excerpt: "Splitting quarkus-work-api into pure contract and quarkus-work-core with CDI implementations reveals that WorkBroker and WorkItems are specialisations of a shared Work substrate that CaseHub also needs."
---

The insight that started this session: `quarkus-work-api` was already a shared contract layer. The follow-on question — what else belongs below the WorkItems inbox, and what's actually inbox-specific? — turned into a day's work.

## From WorkItems to Work

I'd been calling it `quarkus-work-api` and thinking of it as a WorkItems artifact. But `WorkerSelectionStrategy`, `WorkBroker`, `WorkloadProvider` — none of those are inbox-specific. CaseHub has a `TaskBroker` doing roughly the same thing. Two systems reinventing the same substrate separately.

The solution was to pull it into two new modules under groupId `io.quarkiverse.work`: `quarkus-work-api` (pure Java, zero dependencies — the shared contract language) and `quarkus-work-core` (CDI implementations: `WorkBroker`, routing strategies, the filter engine). `quarkus-work` becomes the human-inbox specialisation on top. CaseHub depends on `quarkus-work-core` for `WorkBroker` and writes its own work-unit types.

The naming resolved a tension I'd been ignoring. "WorkBroker" fits better than "TaskBroker" for what CaseHub is actually doing — brokering units of work, not just tasks. "Work" as the base concept, "WorkItems" and "Tasks" as specialisations of it.

The filter engine moved with it. `FilterRegistryEngine` now observes `WorkLifecycleEvent` — any subtype, from any system. CDI's observer matching uses type assignability, not exact equality, which means WorkItems fires `WorkItemLifecycleEvent` and the engine catches it automatically. Worth noting because it's not in the docs and the symptom when it's wrong (observer silently doesn't fire) takes time to diagnose.

We deleted `quarkus-work-api` and `quarkus-work-filter-registry` entirely. Clean break.

## Routing that reads the work

With the substrate in place, the next Epic #100 feature had a clear home: semantic skill matching in `quarkus-work-ai`, using SPIs that live in `quarkus-work-api`.

The interesting design question was the `SkillProfile` shape. A candidate's profile could come from capability tags (just join the set into a string), from a stored narrative (free text in a DB entity), or from resolution history (aggregate past completed WorkItems by category). Different sources produce different kinds of evidence. A string is fine for embedding — the LLM handles prose and structured text alike. But a history-based profile also knows "47 NDA reviews, 0.95 completion rate" as numbers, which a different matcher could exploit. So `SkillProfile` carries both: `narrative()` for embedding matchers, `attributes()` for numerical ones.

The three built-in providers are all pluggable — CDI `@Alternative @Priority(1)` to override. One gotcha: all three were initially `@ApplicationScoped` with no qualifier, which triggers `AmbiguousResolutionException` at Quarkus boot. We made `WorkerProfileSkillProfileProvider` the default and marked the other two `@Alternative`.

The LangChain4j integration had its own trap. Adding `io.quarkiverse.langchain4j:quarkus-langchain4j-core` to a non-extension library module causes `@QuarkusTest` augmentation to hang indefinitely — the Quarkus extension activates and waits for a model provider that doesn't exist. The fix: use `dev.langchain4j:langchain4j-core` (the plain Java library) for the interface, and `Instance<EmbeddingModel>` for optional CDI injection. When no provider is configured the matcher returns -1.0 and the strategy falls back to `noChange()`. No configuration, no stall.

One more: integration test classes named `*IT` are silently skipped by Surefire — they belong to the failsafe lifecycle. `SemanticRoutingIT` became `SemanticRoutingTest` after Claude caught it when the test count didn't change after the file was created.

CaseHub can now pull `quarkus-work-core` for `WorkBroker` without touching the human-inbox stack. That was the point.
