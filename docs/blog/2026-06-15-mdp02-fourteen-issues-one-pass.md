---
layout: post
title: "Fourteen Issues in One Pass"
date: 2026-06-15
type: phase-update
entry_type: note
subtype: diary
projects: [casehub]
tags: [docs, platform, engine, qhorus]
---

Fourteen open doc sync issues. One pass. The size of the batch is itself a signal — platform development across a dozen repos had outrun the documentation. The previous session closed five; this one closed fourteen. The gap had been growing.

Clinical's Layer 8 is now complete. The last entry noted it as partial: `ClinicalActionRiskClassifier` and `SusarCriteriaEvaluator` were wired, but the worker binding was deferred. Since then: `ClinicalSusarOversightCaseHub` shipped with `susar-oversight.yaml`, a three-phase `SusarOversightCaseService`, and `SusarGateDecisionListener` with DB-discriminated `@ConsumeEvent(blocking = true)` for all three gate outcomes (approved/rejected/expired). GDPR Art.17 consent withdrawal, W3C PROV-DM export, and `ClinicalComplianceSupplement` for EU AI Act Art.12 all landed in the same branch. Layer 8 had a busy month.

The most structurally interesting piece is `casehub-engine-inbound`. It's a new optional module that bridges qhorus `MessageReceivedEvent` to casehub-work `WorkItemEntity` creation via an `InboundWorkItemPolicy @FunctionalInterface` SPI. Drop it on the classpath — it becomes active. Remove it — inert. The policy SPI sits in the bridge module rather than `api/spi/` because it references `WorkItemCreateRequest` from casehub-work; the engine's own `api/spi/` can't take that dependency. At-most-once delivery via the qhorus `afterCompletion` callback.

Quarkmind is beginning Phase 1 of its migration from `casehub-poc` to `casehub-engine-api`. The cross-repo dependency map now carries two new rows: `casehub-engine-api` for the `CaseContext` interface, and `casehub-engine-blackboard` (test scope) for `CaseContextImpl` in unit test construction. The poc dependency stays until Phase 2. It's the first external signal in the platform docs that the POC repo's retirement is underway.
