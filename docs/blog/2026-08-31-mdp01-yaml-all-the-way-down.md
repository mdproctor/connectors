---
title: "YAML All the Way Down"
date: 2026-08-31
author: mdp
entry_type: note
subtype: diary
projects: [casehubio/work]
series: issue-371-yaml-frontends
tags: [yaml, sla, progress, declarative-config, design]
---

Two features landed on this branch, both chasing the same goal: a YAML-only tutorial path for casehub-work that requires zero Java.

The first — `DeclarativeSlaBreachPolicy` — replaces the CDI `SlaBreachPolicy` bean with a YAML-declared deployment-wide default. The interesting design constraint was the scope hierarchy. A WorkItem at scope `casehubio/clinical/triage` needs to inherit SLA config from `casehubio/clinical` if no triage-specific config exists, then fall back to deployment-wide defaults, then to a fallback CDI policy. We walk `Path.parent()` at breach time, matching the same resolution model the platform's `PreferenceProvider` uses — one scope algorithm, not two.

The self-detection guard was the subtlest piece. When the declarative policy returns `EscalateTo("senior-reviewers")`, the WorkItem's `candidateGroups` changes to `senior-reviewers`. On the next breach, the same scope matches, the same YAML rule fires, and the item re-escalates to the same group forever. The per-item escalation path in `ExpiryLifecycleService` already has a guard for this — `currentGroups.equals(Set.of(target))` falls through to the policy. But the policy itself had no equivalent. We added one: if the resolved `EscalateToAction`'s target group is already in `candidateGroups`, skip it and continue walking the scope chain. Natural two-tier behaviour without a state machine.

The second feature — `ProgressDefinitionYamlLoader` — is more mechanical. Named progress definitions loaded from `META-INF/work-progress-definitions.yaml`, referenced by name in `ProgressCreateRequest.definitionName`. The format maps YAML `stages` to `StepDefinition` records and stores `transitions` as a sibling in the definition JSON. One wrinkle surfaced during implementation: `StepShapeValidator.parseDefinition()` expected a raw JSON array, but the YAML loader wraps steps in `{"steps": [...], "transitions": {...}}`. A three-line fix to handle both formats.

The declarative SLA policy is the more architecturally significant of the two. It sits in the breach resolution chain between per-item escalation fields (#362) and the CDI policy — a third layer that didn't exist before. The `Provider<StrategyResolver>` for lazy fallback resolution is the same pattern `RoundRobinAssignmentStrategy` uses, dodging a circular CDI dependency that's invisible at the API level: `DefaultStrategyResolver` eagerly iterates all `NamedStrategy` beans at construction, which triggers `@PostConstruct` on any bean it discovers, which can't access `StrategyResolver` because it's mid-construction.

Both features leave one integration test deferred — Flyway V42 (`ALTER TABLE work_items`) fails in H2 mode, a pre-existing issue from the #362 escalation fields branch. The unit test coverage (56 tests for SLA, 64 for progress) covers the logic paths. The IT waits for the migration fix.
