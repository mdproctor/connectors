---
layout: post
title: "The Bug Report Was Wrong — and So Was the Javadoc"
date: 2026-08-07
entry_type: note
subtype: diary
projects: [casehub-work]
tags: [engine-adapter, lifecycle, escalation, sla-breach]
---

When SOC's SLA breach policy exhausts all escalation tiers, `ExpiryLifecycleService` sets the WorkItem to `ESCALATED` — a terminal status. The engine's `PlanItemCompletionApplier` should then transition the backing PlanItem to `FAULTED`, run the output mapping, and fire `CONTEXT_CHANGED`. Except it didn't. The PlanItem sat in `DELEGATED` forever, and the case stalled.

The bug report blamed `applyStatus()` — claimed ESCALATED was hitting the default branch. Reasonable assumption, but wrong. `WorkItemLifecycleAdapter` intercepted ESCALATED at line 77, routing it to a dedicated `handleEscalation()` method that wrote a context signal and returned. ESCALATED never reached the applier at all.

The `handleEscalation()` Javadoc compounded the confusion. The class-level comment correctly described ESCALATED as terminal. The method-level comment described it as non-terminal — "the WorkItem re-enters PENDING with new candidate groups." That's the `EscalateTo` decision's behaviour, not `Exhausted`. Two different ESCALATED concepts had been conflated in the docs, and the intercept had been written to match the wrong one.

There's a subtlety here that matters for the engine integration. The engine's `TaskStatus.ESCALATED` is an active, transient state — used internally for human routing oversight, never persisted, revertible. The work module's `WorkItemStatus.ESCALATED` is terminal and irreversible. Using the engine's `markEscalated()` would have been semantically wrong and mechanically impossible — it only accepts transitions from `PENDING` or `RUNNING`, not `DELEGATED`. The right target was `markFaulted()`, which accepts any non-terminal source state via a CAS loop.

The fix was structural: remove the intercept entirely and let ESCALATED flow through the normal terminal path. The applier picks it up, transitions to FAULTED, writes the escalation signal to the case context, and fires `CONTEXT_CHANGED`. Same flow as every other terminal status.

The design review caught something I'd missed. The applier has a resolution validation guard that runs before `applyStatus()` — if `resolutionTypeName` and `resolution` are both non-null, it deserialises and validates the resolution against the type. `executeExhausted()` doesn't set resolution, so normally it's null and the guard short-circuits. But it doesn't *clear* resolution either. If a WorkItem had stale resolution data from a partial form submission before expiry, the guard could reject the deserialisation and abort the entire ESCALATED transition — silently re-introducing the original bug through a different path. The bypass was a one-line conditional: skip validation when the status is ESCALATED, because it's not an actor-submitted result.

The same review surfaced the gate-backed ESCALATED gap. Removing the intercept meant gate-backed WorkItems with ESCALATED status would now reach `ActionGateCompletionApplier`, where ESCALATED would hit the default branch and be silently dropped. Adding it to the `EXPIRED, FAULTED` case arm — one token — fixed the stall.

The signal field name changed too. The old `handleEscalation()` wrote `newGroups` — inherited from the Javadoc that described ESCALATED as a re-routing event. For terminal ESCALATED, there are no new groups. These are the last-tried groups before exhaustion. `lastCandidateGroups` says what the data actually is.
