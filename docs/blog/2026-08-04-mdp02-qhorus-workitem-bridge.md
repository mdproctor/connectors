---
layout: post
title: "The Qhorus Bridge"
date: 2026-08-04
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-work]
tags: [qhorus, event-mesh, mcp-tools, bridge-module]
series: issue-97-workitem-event-mesh
---

The Qhorus blockers cleared — both the Channel abstraction (#131) and delivery guarantees (#132) shipped while I wasn't looking. That makes #97 (WorkItem event mesh) actionable for the first time since the issue was filed.

The design question at the root: how should WorkItem lifecycle visibility cross service boundaries? The `work-and-workitems.md` document in Qhorus already describes the principled relationship — WorkItems are the oversight channel materialised. An agent posts an oversight/QUERY, a WorkItem captures it as a human-paced obligation, and the completion posts a terminal speech act back. The question was how much of that to build now.

I went with a thin MCP-tool bridge. Three tools — `request_human_work`, `check_work_status`, `wait_for_work` — give agents an explicit interface. No automatic channel observation (every oversight QUERY would become a WorkItem, including trivial ones). Only terminal events post back to the channel (DONE, FAILURE, DECLINE — not the intermediate claim/assign/delegate noise).

## Design review catches

The design review caught real things. The callerRef format needed channelId (UUID) and messageId (for `inReplyTo`) alongside the correlationId — I'd originally designed it with just the channel name, which `MessageDispatch` can't use. The `wait_for_work` started as a CompletableFuture registry modelled after Qhorus's `PendingReply`, but the review pointed out it fails silently in clusters and has unbounded memory growth. Polling via `findByCallerRef()` is simpler and cluster-safe. And the outbound adapter needed a try-catch around the entire `onStatusChange()` body — without it, a `MessageService.dispatch()` failure rolls back the WorkItem lifecycle transaction itself.

## Api-level dependencies

The biggest discovery during implementation: `casehub-qhorus-api` already provides `MessageDispatcher` and `ChannelReader` as SPI interfaces. The spec assumed a compile dependency on the runtime, but the api module gives full dispatch and query capability. The module depends on `casehub-work-api` + `casehub-qhorus-api` only — lighter than any other bridge module in the ecosystem.

## CDI classpath collision

Testing was the other surprise. Putting both `casehub-work` and `casehub-qhorus` runtimes on a `@QuarkusTest` classpath produces 77 CDI ambiguity errors — both extensions provide their own `CurrentPrincipal`, both use named persistence units. The fix was obvious in hindsight: the bridge module's production code depends only on api-level interfaces, so unit tests with recording doubles test the actual logic without CDI wiring. Faster too — 100ms vs 10s.

## What landed

The module landed as `casehub-work-qhorus` — three source files (`QhorusRef`, `QhorusWorkItemLifecycleAdapter`, `WorkQhorusMcpTools`), twenty tests. The speech act mapping covers all seven terminal statuses: COMPLETED→DONE, REJECTED/FAULTED/ESCALATED→FAILURE, CANCELLED/EXPIRED/OBSOLETE→DECLINE.

What this opens up: any Qhorus agent can now request human work and get the result back on the channel, with full normative tracing. The `work-and-workitems.md` vision — WorkItems as the oversight channel materialised — has its first concrete implementation. The next question is whether `casehub-work-qhorus` should also surface WorkItem lifecycle events as Qhorus STATUS messages for the observe channel, giving dashboards in other services live visibility into human work progress.
