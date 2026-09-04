---
layout: post
title: "Building  WorkItems.From Scaffold to Native in One Session"
date: 2026-04-14
type: phase-update
entry_type: note
subtype: diary
projects: [quarkus-work]
tags: [quarkus, tdd, native-image, workflow, human-tasks]
excerpt: "Six phases of quarkus-work ship in one session — the WS-HumanTask research expands the WorkItem model to 27 fields before a line of code is written, and TDD catches two real bugs in Phase 2."
---

I came into this session with a skeleton — a Maven multi-module layout, a pom.xml tree, and a design spec with eight phases marked pending. Nothing built yet. I wanted to see how far we'd get in one run.

Six phases shipped. Two are waiting on upstream projects that aren't finished.

## The design got richer before we wrote a line

Before touching code I sent a separate Claude to research WS-HumanTask, BPMN 2.0 UserTask, CMMN, Camunda 8, and Flowable. What came back was useful in ways I hadn't expected.

The CNCF Serverless Workflow spec has no concept of a human task — no proposal, no planned extension, nothing. That confirmed WorkItems needs to define this space independently.

WS-HumanTask surfaced things I'd missed: the `owner` vs `assignee` distinction (who delegated vs who has it now), separate claim and completion deadlines, candidate groups for work-queue routing, and a `followUpDate` field distinct from the due date. We pulled all of it in. The WorkItem model grew to 27 fields before a single line of implementation was written.

I also aligned Tarkus's status names with Quarkus-Flow's `WorkflowStatus` where it made sense: `SUSPENDED` and `CANCELLED` are identical. `IN_PROGRESS` maps to `RUNNING`, `REJECTED` to `FAULTED`. Small thing; matters when building integrations.

## TDD caught two real bugs in Phase 2

Phases 1 through 4 built cleanly. Phase 2 is where the tests paid for themselves.

The REST integration tests failed on first run with two bugs. One: `WorkItemService.create()` wasn't defaulting `priority` to NORMAL when the field was absent — the DB column was NOT NULL and H2 rejected it with a constraint violation. Two: `JpaWorkItemRepository.findInbox()` was using `wi.assigneeId` in the JPQL WHERE clause. Panache's short-form `find()` generates its own internal alias; `wi.` refers to nothing and silently returns empty.

Both were caught because the tests were written first. Neither would have surfaced until production.

## The Quarkus-Flow SPI was a dead end

Phase 5 needed more research. The `CallableTaskBuilder` SPI routes by Java class type — `accept(Class<? extends TaskBase>)`. For `call: tarkus` in workflow YAML, the SDK produces a `CallFunction` instance — the same class it produces for *every* user-defined callable name. We couldn't distinguish "tarkus" from any other function reference at the class level.

A separate Claude dug through the SDK sources and confirmed it. The CDI bridge (`HumanTaskFlowBridge`) ended up being the right approach anyway: inject it into a Java-defined flow function, return a `CompletableFuture<String>`, complete it when the human acts via a CDI event listener. Cleaner than a YAML-level SPI.

## Native needed nothing

Static analysis predicted 17–18 classes would need `@RegisterForReflection`. Quarkus 3.32.2 handled all of them automatically at build time. The native binary passes all 19 integration tests. Startup: 0.084 seconds. We added zero annotations.

The official guidance still says to annotate REST DTO records manually. It's just not true for recent Quarkus versions — and we only know that because we skipped the annotations and ran the build.

---

234 tests across six modules. Two phases blocked on CaseHub and Qhorus — both waiting for those projects to reach a stable API. The design for both is done; the targets aren't ready.
