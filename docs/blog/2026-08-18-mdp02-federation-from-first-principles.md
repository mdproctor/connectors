---
layout: post
title: "Federation from First Principles"
date: 2026-08-18
entry_type: article
subtype: diary
projects: [casehub-work]
tags: [federation, distributed-systems, cqrs, cloudevents, design]
series: issue-92-distributed-workitems
---

The last open items on epic #92 (Distributed WorkItems) were the hard ones — cross-service federation and coordinated rollback. Single-cluster distribution was done months ago: PostgreSQL LISTEN/NOTIFY for SSE, `@Version` OCC for atomic claims, exactly-once schedule execution. All the problems you can solve with a shared database.

Federation is different. Different databases. Different JVM clusters. A WorkItem created by an AI agent in Service A needs to appear in a human operator's inbox in Service B, be claimable there, and produce lifecycle events that flow back to Service A. The shared-database assumption stops holding.

## Starting from the literature

I didn't want to design this from vibes. The OASIS WS-HumanTask specification defines a three-party architecture — Task Parent (creates), Task Processor (manages lifecycle), Task Client (presents to humans) — with a formal coordination protocol between them. The critical insight: the Task Processor is the single owner. The spec explicitly says "composite tasks follow the design principle that they are managed by a single task processor." Federation happens at the client level, not by replicating state across processors.

Google's A2A protocol (now a Linux Foundation standard, 150+ organisations) takes a different angle: "fluid roles" where any agent can be client or server depending on the interaction. The CaseHub engine already has A2A infrastructure — `A2AClient`, `AgentCard`, `A2AWorkerFunction`. But A2A's 8 task states are too simple for WorkItem's 15+ lifecycle transitions. Claim with OCC, delegate with target, escalate with SLA policy — these don't map to A2A's generic message model.

Camunda 8.8 was instructive in what NOT to do — they moved away from distributed components and toward a unified Orchestration Cluster. Multi-engine task federation isn't a built-in feature. The industry consensus is clear: don't replicate mutable state across services.

## The architecture that fell out

Five axioms drove the design:

1. **Single-writer ownership.** Each WorkItem has one owner. Period.
2. **Events out, commands in.** CQRS — lifecycle events flow outbound via CloudEvents, mutations flow inbound via REST.
3. **Per-interaction asymmetry, system-level symmetry.** One owner per WorkItem, but any service can play either role.
4. **Transport-agnostic.** casehub-work defines WHAT; Qhorus/webhook/Kafka handles HOW; A2A handles WHERE.
5. **Layering.** casehub-work provides federation primitives. CaseHub orchestrates. This line doesn't blur.

The concrete design: shadow WorkItems. Service B receives CloudEvents from Service A and creates local projection rows — real `WorkItemEntity` rows with `originServiceId` set. All existing inbox queries, filter chains, queue membership, and reports work transparently because shadows ARE WorkItems. When an operator claims a shadow, the `FederationProxyService` decorator intercepts the call and routes it to the owning service's REST API.

The mutation safety model is a CDI `@Decorator` on `WorkItemStore.put()` that rejects writes to shadow WorkItems unless a `FederationSyncContext` (AutoCloseable, ThreadLocal) is active. One guard at the persistence boundary catches every mutation path — 30+ service methods, timers, schedulers, assignment strategies — instead of scattering checks across the codebase.

## What got built

Two new modules: `federation/` (optional integration module, same structural position as `engine-adapter/` and `qhorus/`) and `client/` (lightweight REST client, no JPA, no CDI — usable by anyone who needs to talk to a remote casehub-work instance).

The subscription model uses filter-on-creation with lifecycle lock-on. Service B registers a filter predicate (candidateGroups, tenancyId). When a matching WorkItem is created on Service A, the subscription locks on and delivers ALL subsequent lifecycle events for that WorkItem until terminal state. Efficient — O(creations × subscribers) rather than O(all events × subscribers).

Events are CloudEvents with HMAC-SHA256 signing. The receiver verifies the signature, checks `originVersion` for stale event detection (catches out-of-order delivery), namespaces the `callerRef` to prevent collisions with other adapters, and uses `FederationSyncContext` for the store guard pass-through.

Error handling is fail-fast. The proxy calls the owner with a 5-second timeout. Success or 409 — no tentative states, no background retry, no reconciliation. At human scale, the operator can click again. The staleness window between a failed proxy call and the corrective CloudEvent is bounded by delivery latency.

## What I found along the way

The `WorkItemStore` SPI is cleaner than I expected — `put()` really is the single persistence boundary for all mutations. That made the decorator guard viable. If even one mutation path bypassed `put()`, the whole approach would have failed.

Extracting `WorkItemOperations` as an interface from `WorkItemService` was mechanical but architecturally important. REST resources now inject the interface (decorated by the proxy); internal runtime code keeps injecting the concrete class. The decorator scope is controlled by injection type, not by configuration — a pattern I hadn't seen used this way before.

The `originVersion` field (separate from JPA `@Version`) was a review finding. Without it, you can't distinguish stale from current events — the shadow's own JPA version counts local puts, not owner mutations. Two independent version counters serving different purposes on the same entity.

## Where this goes

The protocol is designed for Qhorus channel transport (the webhook implementation is the bootstrap). When Qhorus channels carry the CloudEvents, delivery guarantees, dead-letter handling, and speech act semantics come for free. The A2A adapter module maps between A2A tasks and WorkItem lifecycle for agent interoperability.

Coordinated rollback (#332) builds on this — extending the protocol with subtree-wide undo messages. And ProvenanceLink (#39) will connect the dual audit trails into a unified PROV-O causal graph across service boundaries.

The federation fields are on the `WorkItem` record in `api/` now. Every consumer of casehub-work — CaseHub engine, claudony, standalone apps — can detect shadows and act accordingly. The foundation is in place for the next layer.
