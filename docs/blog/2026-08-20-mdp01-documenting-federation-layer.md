---
layout: post
title: "Documenting the Federation Layer"
date: 2026-08-20
entry_type: note
subtype: diary
projects: [casehub-work]
tags: [arc42, federation, documentation, architecture]
---

The federation and client modules have been sitting on `issue-92-distributed-workitems` for a while now, built but undocumented in ARC42STORIES.MD. This session closes that gap — adding the C4 diagram entry, Module Index rows, and a full §9.4 layer entry covering both modules.

The interesting decision was where to place federation in the layer hierarchy. The obvious candidates were L6 Distribution (since federation distributes WorkItems across services) and L7 Optional Modules (since it activates by classpath presence). Neither fit. L6 is about same-cluster SSE fan-out via PostgreSQL LISTEN/NOTIFY — a fundamentally different concern from cross-service shadow replication. L7 groups modules that add a single capability via `@Alternative @Priority(1)` displacement. Federation does something architecturally heavier: two `@Decorator` layers (one on `WorkItemStore`, one on `WorkItemOperations`), a subscription model with delivery tracking, and HMAC-secured CloudEvent transport. It earned its own L8.

The architecture itself is worth noting. The core idea is shadow replication with decorator-guarded immutability. `FederationGuardStore` decorates `WorkItemStore.put()` and blocks all writes to items with `originServiceId` set — unless a ThreadLocal flag (`FederationSyncContext`) is active. Only `FederationReceiver` activates that flag, inside a try-with-resources block, so the guard cannot be bypassed accidentally. The second decorator, `FederationProxyService`, sits on `WorkItemOperations` and intercepts claim/complete/reject/delegate/release on shadow items, routing them back to the owning service via `WorkItemClient` — a zero-dependency REST client in its own module.

The subscription model uses a filter-on-creation + lock-on pattern. When a WorkItem is created, `FederationEventRouter` evaluates subscription filters and locks matching subscriptions to that item. All subsequent lifecycle events reuse the locked set without re-evaluating. This trades filter-change responsiveness for O(1) event routing — acceptable because subscriptions represent stable service-to-service relationships, not dynamic queries.

The federation code is still on its branch, waiting for CaseHub and Qhorus APIs to stabilise before #95 (cross-service creation) and #97 (event mesh) can complete. But the architecture is settled enough to document. Having the layer entry in ARC42STORIES.MD means anyone reading the design document can understand the federation model without spelunking through branch source.
