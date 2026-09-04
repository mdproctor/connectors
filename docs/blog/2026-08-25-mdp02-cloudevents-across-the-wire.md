---
layout: post
title: "CloudEvents Across the Wire"
date: 2026-08-25
entry_type: note
subtype: diary
projects: [casehubio/engine]
tags: [cloudevents, distributed, humantask, spi, module-design]
series: issue-972-distributed-humantask-cloudevent
---

# CloudEvents Across the Wire

Until now, the engine could only dispatch human tasks and action gates when the work service lived in the same JVM. `HumanTaskScheduleHandler` consumed Vert.x events, `WorkItemLifecycleAdapter` observed CDI events — both requiring co-location. For a distributed deployment where engine and work run as separate services, none of this works.

The work repo already shipped both halves of its side: `io.casehub.work.workitem.create` as the inbound CloudEvent consumer (work#299), and `WorkCloudEventAdapter` for outbound lifecycle events (work#273). What was missing was the engine side — the emitter that produces creation CloudEvents and the consumer that handles the lifecycle responses.

The interesting design question was where to draw the boundary. The co-located adapter (`casehub-work-engine-adapter`) owns about 480 lines of PlanItem completion logic — looking up the PlanItem, validating resolution types via `BridgeResolver`, applying JQ output mappings with `ConflictResolver`, firing CDI state-change events, publishing `CONTEXT_CHANGED`. Duplicating that in a new module would create two copies of the same orchestration that drift independently.

Instead we pulled the completion logic into shared appliers — `PlanItemCompletionApplier` and `GateCompletionApplier` — that both the co-located adapter and the CloudEvent module can delegate to. They live in the planning module, which is where `BlackboardRegistry` and `PlanItem` already live. The appliers accept `TaskStatus` (engine-native), so callers map from whatever external type they use. The co-located adapter maps from `WorkItemStatus`; the CloudEvent consumer maps from CloudEvent type strings. Neither needs to know about the other's types.

The CloudEvent module itself is a thin transport adapter. `CloudEventHumanTaskScheduler` maps `HumanTaskScheduleRequest` fields to a JSON data payload — title, candidateGroups, callerRef, payload, outcomes, scores, experiences — builds a CloudEvent envelope, and fires it via CDI async event. A Quarkus messaging connector handles the actual wire protocol. The gate emitter follows the same pattern with one constraint: quorum (M-of-N) gates aren't supported yet because the work-side consumer handles single WorkItem creation only. Those skip with a log warning until multi-instance CloudEvent support lands.

The `CallerRefParser` is worth noting. Both emitters encode a caller reference — `case:{caseId}/pi:{planItemId}` for PlanItems, `case:{caseId}/gate:{gateId}` for gates — that travels through the CloudEvent and comes back on the lifecycle response. The consumer parses it with a sealed type (`PlanItemRef | GateRef`) and routes to the correct applier. The work-engine-adapter has its own copies of this encoding, but the engine-side parser is now the authoritative implementation.

One gotcha surfaced during implementation: Mockito silently fails to intercept `id()` on a mocked `PlanItem` when the method is an `@Override` of the `TaskDescriptor` interface. The stub returns null with no error. Using real `PlanItem` instances via the factory method and transitioning them to the desired state solved it immediately. Worth knowing if you mock concrete classes that implement interfaces with overridden methods.

The fire-and-forget emission pattern has a known gap: if CDI event delivery fails between PlanItem persistence (DELEGATED) and actual CloudEvent emission, the PlanItem stays stuck. A transactional outbox would fix this — persist the event in the same transaction, relay via poller or CDC — but that's deferred for pre-release. The failure mode is detectable via PlanItem status monitoring and recoverable manually.
