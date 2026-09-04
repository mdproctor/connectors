---
layout: post
title: "The Channel Nobody Was Listening On"
date: 2026-06-23
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-work]
tags: [cdi, cloudevents, event-bus, dual-channel]
series: issue-273-work-cloudevent-adapter
---

casehub-work fires lifecycle events on every WorkItem transition — created, assigned, completed, rejected, the full twelve-status lifecycle plus operational events like spawned and claim-expired. Twenty-four distinct event types in total. The CDI event bus has been the backbone of the system since C4.

What I hadn't noticed: CDI has two independent delivery channels. `fire()` reaches `@Observes` handlers. `fireAsync()` reaches `@ObservesAsync` handlers. They don't bridge. casehub-work was firing non-terminal transitions (created, assigned, started, delegated — thirteen methods) on the sync channel only. Terminal transitions (completed, rejected, cancelled, faulted, obsolete) fired both.

This meant `QueueDashboard`, which uses `@ObservesAsync`, saw completions and rejections but never saw work arrive. The dashboard refreshed on terminal transitions and was blind to everything else.

The immediate task was straightforward: bridge WorkItem lifecycle events to the platform CloudEvent bus so casehub-ras can observe them without coupling to casehub-work types. Every other foundation module — IoT, Qhorus, Connectors — already has a CloudEvent adapter. casehub-work was the gap.

But the adapter needed `@ObservesAsync`, and thirteen methods didn't fire on that channel. The obvious fix — add `fireAsync()` to those thirteen sites — solves the immediate problem while recreating the same fragility. The next developer who adds a lifecycle method has to remember both calls. Forget one, and the async channel silently loses events. That's the exact bug we were fixing.

The answer was a `WorkItemLifecycleEmitter` bean that wraps `Event<WorkItemLifecycleEvent>` and calls both `fire()` and `fireAsync()` internally. One injection point, one `emit()` call, both channels populated structurally. The pattern can't be bypassed without injecting the raw `Event` type, which would stand out in any review.

We built matching emitters for both event types — `WorkItemLifecycleEmitter` and `WorkItemGroupLifecycleEmitter` — then migrated all five event-producing classes. Twenty-eight call sites simplified to single `emit()` calls. The null checks scattered across every site (`if (lifecycleEvent != null)`) vanished — CDI-injected beans are never null; those were artefacts of test scenarios without a container.

The CloudEvent adapter itself follows the canonical six-rule pattern from the garden. `WorkCloudEventAdapter` observes both event types via `@ObservesAsync` and bridges them to `Event<CloudEvent>.fireAsync()`. Twenty-four WorkItem types and three group types. The adapter reuses the event's own `type()`, `sourceUri()`, and `subject()` fields — WorkItemLifecycleEvent was designed with CloudEvent-shaped accessors from the start.

One design question took four review rounds to settle. I initially claimed the complete/reject methods fired `fireAsync()` only — a factual error that inverted the entire normalization direction. The actual pattern was the opposite: non-terminal transitions were sync-only, terminal transitions already had both. The review also surfaced `@Observes(during = TransactionPhase.AFTER_SUCCESS)` as an alternative — four existing observers in casehub-work already use that pattern. It would have avoided the normalization entirely for the adapter. We chose `@ObservesAsync` anyway: the normalization was needed to fix QueueDashboard regardless, `@ObservesAsync` runs on a managed executor (zero caller-thread overhead), and it keeps the adapter symmetric — both observer methods use the same annotation.

The broader question this opens: should every casehub repo fire both channels? Currently all other repos fire async-only, and that works because they have no sync observers. casehub-work is different — it has sync observers with real transactional consistency requirements. Whether other repos would benefit from dual-channel is now tracked as parent#302, framed as an evaluation rather than a mandate.
