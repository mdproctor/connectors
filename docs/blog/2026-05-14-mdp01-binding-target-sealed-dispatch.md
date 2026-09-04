---
layout: post
title: "BindingTarget and the sealed dispatch"
date: 2026-05-14
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-engine]
tags: [java, quarkus, sealed-interface, hitl, vertx]
---

The `Binding` class in casehub-engine has had two nullable fields since the beginning — `capability` for worker routing and `subCase` for spawning child cases. Mutually exclusive by construction, which meant dispatch code everywhere had to check `binding.getSubCase() != null` to know which path to take. Every new binding type would add another nullable field and another branch, scattered.

I wanted to fix that before adding the third type. The result is a sealed interface — `BindingTarget` — with four permits: `CapabilityTarget`, `SubCaseTarget`, `HumanTaskTarget`, and `ExtensionTarget`. `Binding` now carries a single `target()`. The project is on Java 17, so dispatch uses if-else instanceof chains rather than exhaustive switch — but the sealed semantics are there. No new subtype can be introduced silently.

`HumanTaskTarget` is the new permit. It covers the human-in-the-loop case: a binding condition fires, a WorkItem is created in casehub-work, a human acts, and the case context is updated from the resolution. Two modes — inline (self-contained, title/candidateGroups/expiresIn) and template (references a `WorkItemTemplate` by ID). The builder is clean enough that it's worth showing:

```java
HumanTaskTarget.template("irb-review-template")
    .inputMapping("{ applicantId: .applicantId }")
    .outputMapping("{ irbOutcome: .decision }")
    .build()

HumanTaskTarget.inline()
    .title("IRB Ethics Review")
    .candidateGroups(Set.of("ethics-committee"))
    .expiresIn(Duration.ofHours(72))
    .build()
```

`inputMapping` evaluates against the CaseContext before the event is published — the outbound handler receives a resolved `Map<String, Object>`. `outputMapping` works the other direction: when a WorkItem completes, `WorkItemLifecycleAdapter` parses the resolution JSON into a temporary context, evaluates the JQ expression against it, then calls `CaseContext.setAll()` before firing `CONTEXT_CHANGED`.

I brought Claude in for the implementation and the subsequent review. The outbound handler — `HumanTaskScheduleHandler` — needs one non-obvious annotation: `@ConsumeEvent(blocking = true)`. Without it, calling `WorkItemService.create()` from the Vert.x event loop silently fails. The PlanItem goes RUNNING (in-memory, no transaction needed), but the WorkItem is never created. No exception, no warning. We tracked it down to the missing flag.

The review surfaced a second failure mode. The handler was calling `planItem.markRunning()` before checking whether the target was template or inline mode. For inline tasks that's fine, but template mode is currently a stub — it logs a warning and returns. Which would leave the PlanItem permanently stuck in RUNNING state, with no WorkItem, and `addPlanItemIfAbsent` rejecting every future attempt to schedule the same binding. Claude flagged this as a state leak — correctly. The fix: check `target.isTemplateMode()` early and return before touching the plan item.

Template mode wire-up is deferred (`engine#255`). Java 21 exhaustive switch — which would let the compiler enforce that dispatch handles all four permits — is tracked as `engine#254`. The inline path is fully exercised: 14 tests pass, including the outputMapping round-trip.
