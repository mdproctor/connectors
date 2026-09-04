---
layout: post
title: "Atomic by Design"
date: 2026-05-18
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-engine]
tags: [quarkus, jpa, transactional, spi, atomicity]
---

The bug in `HumanTaskScheduleHandler` was easy to describe: if WorkItem creation fails after `item.markRunning()`, the PlanItem ends up stuck RUNNING with no WorkItem to complete it. The engine won't reschedule it — the PlanItem is no longer PENDING — and the case is blocked indefinitely.

The obvious fix is to invert the calls: create the WorkItem first, then mark RUNNING. That closes the exception gap. But it doesn't help if the process crashes between the two operations. And it leaves PlanItem status as ephemeral in-memory state with no durable record.

I wanted the durable answer.

## The platform already knew how to do this

The question of "how do you make something persistably pluggable?" has already been answered in casehub-work: the `WorkItemStore` SPI. A pure-Java interface in the runtime module, a JPA default, a MongoDB alternative, and an in-memory test double. Consumers pick the backend; the domain logic doesn't care which one.

`PlanItemStore` follows the same shape — blocking variant for synchronous contexts, `Uni<>` reactive mirror for Vert.x IO threads. We extracted `PlanItemStatus` out of its nested position inside `PlanItem` and placed it in `casehub-engine-common`, where the SPI interfaces live. The reactive engine SPIs (`CaseInstanceRepository` etc.) are already there; `PlanItemStore` and `ReactivePlanItemStore` join them.

## Where the blocking impl lives — and why that's interesting

`casehub-persistence-hibernate` uses Hibernate Reactive throughout. Panache, `withTransaction()`, the works. The work-adapter is the opposite: `WorkItemService.create()` runs blocking JPA in the casehub-work persistence unit.

Atomicity requires that `planItemStore.save(RUNNING)` and `workItemService.create()` participate in the same JTA transaction. A reactive Panache impl can't do that on a blocking worker thread — the two Hibernate stacks don't share a transaction context. So the blocking `JpaPlanItemStore` lives in the work-adapter module itself, using `EntityManager` from the casehub-work datasource. Two entity classes mapping to the same `plan_item` table: `PlanItemEntity` (reactive Panache, in `persistence-hibernate`) and `WorkAdapterPlanItemEntity` (blocking JPA, in `work-adapter`). Same schema, different Hibernate stacks, never on the same classpath simultaneously.

The handler is `@ConsumeEvent(blocking=true) @Transactional`. All three operations — create WorkItem, `planItemStore.save(RUNNING)`, `item.markRunning()` — run inside that single transaction. If anything throws, the JPA writes roll back and `markRunning()` is never reached. The PlanItem stays PENDING.

## What the code reviewer caught

After the implementation, a code reviewer flagged a critical issue I'd missed: we had called `planItemStore.save()` from `DefaultCasePlanModel.addPlanItem()`, which runs inside the reactive `PlanningStrategy` chain — on the Vert.x IO thread with no JTA context. The JPA blocking store would either silently fail or throw `TransactionRequiredException`. Claude flagged this as blocking before I'd noticed the thread mismatch.

The fix was to remove `PlanItemStore` from `DefaultCasePlanModel` entirely. The store call moves to the handler, where a real `@Transactional` boundary exists.

The reviewer also caught a subtler issue in `JpaReactivePlanItemStore`: the `updateStatus()` method used a find-and-mutate pattern. If `save()` had been called earlier in the same reactive session but not yet flushed, the find returns null and the update silently no-ops. The blocking impl already handled this correctly — `em.flush()` before the JPQL UPDATE, `em.clear()` after to evict the stale L1 cache entry. The reactive impl had skipped the flush.

That one ends up in the garden.
