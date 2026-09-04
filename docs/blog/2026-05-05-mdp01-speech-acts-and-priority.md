---
layout: post
title: "Speech acts, priority names, and a Panache coupling"
date: 2026-05-05
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-work]
tags: [webhooks, github, jira, normative-layer, priority, testing, panache]
excerpt: "NormativeResolution maps GitHub and Jira close events to speech-act semantics, CRITICAL gets renamed URGENT after finding it borrows ITSM severity vocabulary into a task scheduling context where it doesn't belong."
---

The `close` event from GitHub doesn't just mean "done." It might mean the work was completed, or abandoned as not worth doing, or attempted and failed. Those three outcomes — fulfilled, refused, failed — map to COMPLETED, CANCELLED, REJECTED in WorkItems.

I'd been thinking of this as a mapping problem until the Qhorus normative layer docs clarified it. Speech act theory has names for these: DONE, DECLINE, FAILURE. GitHub's `state_reason: not_planned` is a DECLINE. Jira's "Cannot Reproduce" is a FAILURE. GitHub has no FAILURE equivalent at all. That asymmetry is real and documented — the tracker vocabulary doesn't need to match, but the transition semantics do.

So `WebhookEvent` carries a `NormativeResolution` field — DONE, DECLINE, or FAILURE — and `WebhookEventHandler` acts on that rather than on GitHub or Jira strings. The tracker knowledge stays inside the parser. The handler never knows which tracker fired.

## The priority that had no reason

Midway through the design I noticed something: `WorkItemPriority.CRITICAL` had no documented rationale anywhere. Not in the original spec, not in any commit message.

Looking at what other systems actually use: Jira's top priority is "Highest" — a scheduling signal, just "before High." Linear uses "Urgent." ITSM tools like ServiceNow use "Critical" for incident severity — a different concept entirely. A WorkItem is a task, not a production incident. CRITICAL was borrowed vocabulary from the wrong domain.

We renamed to URGENT/HIGH/MEDIUM/LOW — Linear's vocabulary. CRITICAL→URGENT is a priority signal, not a severity level. NORMAL→MEDIUM because "Medium" is the standard term in task management and Jira maps there directly. Fifty-seven files touched, a Flyway V5001 migration to update stored values, GitHub labels updated from `priority:critical` and `priority:normal` to `priority:urgent` and `priority:medium`.

This is the kind of rename you do before you have users.

## Designing for both trackers at once

I made a mistake during the initial design: I kept thinking in GitHub terms and backfilling Jira. The session got corrected mid-way — any field mapping or event handling decision should look at both trackers simultaneously, not sequentially.

It changed a few concrete decisions. GitHub has no native priority field; it uses labels. So inbound priority from GitHub comes from `labeled`/`unlabeled` events for our own `priority:*` labels — a round-trip of what outbound sync creates. Jira has a native five-level priority field (Highest/High/Medium/Low/Lowest) that maps to four WorkItem values. The behaviour is different enough that designing for one then adapting for the other gets the edge cases wrong.

Description sync had a wrinkle too. When `createIssue` runs, it appends a footer to the GitHub body: `---\n*Linked WorkItem: {id}*`. Inbound body sync strips that footer before writing to `WorkItem.description`. Otherwise the WorkItem's description ends with its own identifier, which is both redundant and ugly.

## Testability, and a Panache coupling

Once the webhook implementation was done, a code review flagged that `WebhookEventHandler.handle(WebhookEvent)` called Panache statics directly — `WorkItemIssueLink.findByTrackerRef()` and `WorkItem.findById()`. The public entry point required a running Quarkus container to test.

We introduced `IssueLinkStore`, following the `WorkItemStore`/`AuditEntryStore` pattern already in the project. `JpaIssueLinkStore` delegates to the Panache entity methods. `InMemoryIssueLinkStore` lives in `casehub-work-testing`, backed by a `LinkedHashMap`. Both `WebhookEventHandler` and `IssueLinkService` now inject `IssueLinkStore`. The public `handle(WebhookEvent)` is now a plain Mockito unit test.

One thing Claude caught in the test rewrite: mocking `Instance<IssueTrackerProvider>` with `when(instances.stream()).thenReturn(List.of(provider).stream())` exhausts the stream after the first call. `onLifecycleEvent` calls `providerFor()` once per linked issue — two links, two calls, the second gets an empty stream and no providers are found. `thenAnswer(inv -> List.of(provider).stream())` creates a fresh stream on each invocation.
