---
layout: post
title: "The half that was missing"
date: 2026-05-13
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-work, casehub-engine]
tags: [cdi, engine, adapter, human-task, design, binding]
---

The issue said the casehub-work adapter wasn't implemented. Claude opened the `work-adapter` module and immediately found `WorkItemLifecycleAdapter.java`, `CallerRef.java`, and eight passing tests. The inbound path — WorkItem completes, adapter parses `callerRef`, marks the PlanItem, fires `CONTEXT_CHANGED` — was done. The issue description was wrong, or at least incomplete.

What wasn't done was the outbound path. When a case binding activates for a human task, nothing creates the WorkItem. The `ProvisionContext` the engine passes to `WorkerProvisioner.provision()` doesn't include `planItemId` — only `caseId` and the capability name. Without `planItemId`, there's no way to encode `callerRef = CallerRef.encode(caseId, planItemId)`. The provisioner approach didn't work.

## The path that couldn't work

We spent time on whether `WorkerProvisioner` could serve as the hook. The design looked reasonable: the adapter registers as a provisioner, intercepts the capability, creates the WorkItem with `callerRef` set.

The problem is completion. The `WorkerProvisioner` pattern assumes a worker lifecycle tracked by `WorkerStatusListener`. The work adapter takes a different path: `WorkItemLifecycleEvent` fires, the adapter marks the `PlanItem` directly, fires `CONTEXT_CHANGED`. Two separate completion mechanisms for the same PlanItem. The engine would think it had a live worker while the adapter had already closed it out.

So we took the event-bus approach, following the `SubCase` pattern already in the codebase.

## Naming matters

The engine API module shouldn't know anything about casehub-work. When I first drafted the `Binding` extension, I called it `WorkItemRef`. Claude flagged it: that's casehub-work vocabulary, not engine vocabulary. The engine API should be domain-agnostic.

Renamed to `HumanTaskTarget`. It expresses the concept without coupling the API to the specific system that implements it. A different adapter could handle the same `HumanTaskTarget` by creating a JIRA ticket or a ServiceNow incident.

```java
HumanTaskTarget.template("irb-72h-review")
    .inputMapping("{ description: (\"Trial \" + .trialId) }")
    .outputMapping("{ irbOutcome: .decision }")
    .build()
```

Both `inputMapping` and `outputMapping` take either a JQ string or any `ExpressionEvaluator` — lambda included.

## Where to put the outputMapping

When a WorkItem completes, the adapter has `callerRef`. From that it recovers `caseId` and `planItemId`. What it can't easily recover is the `HumanTaskTarget` set at schedule time — specifically the `outputMapping` expression.

I proposed a `HumanTaskRegistry` CDI singleton: a `ConcurrentHashMap<(caseId, planItemId), HumanTaskTarget>`. Claude and I worked through it. The problem is lifecycle: populate at schedule time, clean up at completion, lost on restart. Three things to manage, all for information that already exists on the domain entity.

Storing `BindingTarget` directly on `PlanItem` solved all three. The plan item knows its own configuration. At completion time, the adapter walks `BlackboardRegistry → CasePlanModel.getPlanItem(planItemId) → planItem.target()`. No separate state, restart-safe, and it makes `outputMapping` available for all binding types — SubCase included, for free.

## The hardcoded null in casehub-work

Only one thing needed changing in casehub-work: `WorkItemTemplateService.instantiate()` hardcoded `callerRef = null` in its `toCreateRequest()` call. We added a 5-arg overload that accepts `callerRef` and passes it through. The existing 4-arg delegates with null — all existing callers untouched.

Code review caught that the 5-arg's `@Transactional` annotation is dead when reached via the delegation path. `this.instantiate(5-arg)` bypasses the CDI proxy — interceptors don't fire on internal calls. The annotation is active when called directly from outside, as the test does. But it's a false guarantee for future callers who might call the 5-arg directly without a surrounding transaction. Tracked for the next pass.
