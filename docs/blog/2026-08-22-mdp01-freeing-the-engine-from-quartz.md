---
layout: post
title: "Freeing the Engine from Quartz"
date: 2026-08-22
entry_type: note
subtype: diary
projects: [casehub-engine]
tags: [scheduler, quartz, db-scheduler, refactoring, spi]
series: issue-813-alternative-scheduler-spi
---

The engine's worker scheduling was welded to Quartz. Not by design — by accumulation. Six job classes had grown to hold domain logic that had nothing to do with scheduling: case loading, definition resolution, retry policy evaluation, scoped worker registry interaction, milestone lifecycle queries. The scheduling concern was maybe 10% of each file. The other 90% was engine orchestration wearing a Quartz costume.

We're replacing Quartz with db-scheduler as the default scheduler — lighter dependency, simpler API, dual-mode storage (H2 in-memory for development, PostgreSQL for production). But you can't swap a scheduler when the domain logic is trapped inside its job classes. Phase 1 is the extraction: pull every piece of engine logic out of Quartz into scheduler-agnostic orchestrators that any backend can call.

The pattern is surgical. Take `QuartzWorkerExecutionJob` — 346 lines that resolved EventLog entries, recovered case instances, looked up definitions, built worker contexts, handled bridge types, and delegated to the executor. After extraction, `WorkerExecutionOrchestrator` holds all of that in `common/internal/executor/`. The Quartz job becomes a dozen lines: read the data map, build a `WorkerTaskData` record, call the orchestrator.

`RetryOrchestrator` followed the same shape. The interesting wrinkle was the `RecoveryCoordinator` gate — when retries exhaust, the orchestrator checks whether the multi-level recovery protocol can handle the failure before publishing `WORKER_RETRIES_EXHAUSTED`. That gate logic was buried inside `QuartzRetryService`. Now it's explicit and testable independently of any scheduler.

The three scheduled trigger jobs — unconditional, conditional, and signal — shared about 80% of their code. Case loading, status checks, definition lookup, the scoped worker registry dance (persistent sessions get a mailbox push, reinvoked sessions re-dispatch with the session's executor). The differences: conditional triggers evaluate `binding.getWhen()` via `ExpressionEngineRegistry`, signal triggers parse a JSON payload and publish a `ContextSignalEvent` instead of a `WorkerScheduleEvent`. One `ScheduledTriggerOrchestrator` with three entry points handles all of them.

`MilestoneSLAOrchestrator` is the simplest — a different loading strategy (cache then cross-tenant repository fallback instead of the recovery service), a milestone lifecycle query against EventLog, and a violation event if the milestone is still active.

Five commits. Six Quartz job/service classes extracted. Every one of them is now a thin shim — read the data map, build a record, call the orchestrator. Phase 1 is done. Phase 2 builds the db-scheduler module on top of these orchestrators.
