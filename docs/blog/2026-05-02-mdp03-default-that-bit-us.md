---
layout: post
title: "The default that bit us, and why defaults matter"
date: 2026-05-02
type: phase-update
entry_type: note
subtype: diary
projects: [quarkus-work]
tags: [testing, ci, multi-instance, defaults, design]
excerpt: "Three unrelated CI failures arrive at once — a Friday business-hours assertion, a SNAPSHOT API drift, and a multi-instance default whose effects only appear under concurrent load — all sharing the shape 'works locally, fails in CI.'"
---

CI was failing on three separate tests. None of them were related — except
they all had the same shape: works locally, fails in CI under load.

The first two were mechanical. `BusinessHoursIntegrationTest` was asserting
`isBefore(Instant.now().plus(1, DAYS))` for a 2-business-hours deadline, but
May 1 was a Friday. Two business hours from Friday 19:35 UTC resolves to Monday
11:00 — nearly four calendar days. The calendar was correct; the test was not.
We extracted a `BusinessHoursAssert` helper that derives the calendar bound from
the business hours count: `ceil(bh/8) + 2` calendar days covers any weekend
expansion. Hard to get wrong now.

The second was a `casehub-ledger:0.2-SNAPSHOT` API drift. CI pulls the latest
SNAPSHOT from GitHub Packages; the local build uses a cached jar. Three new
abstract methods were added to `LedgerEntryRepository`; local passed,
CI broke. The fix was implementing the methods. The lesson: "it builds locally"
is not the same as "it will build in CI" when you're on a SNAPSHOT dependency.

The third took longer.

## The OCC that wasn't

`WorkItemGroupLifecycleEventTest.completedEventFiresExactlyOnceAtThreshold`
was failing with:

```
OptimisticLockException: Unexpected row count (expected row count 1 but was 0)
[update work_item set ... where id=? and version=?]
```

The stack trace pointed into the test's completion loop. Claude and I worked
through the usual suspects — Hibernate session sharing, missing `em.detach()`,
async event contamination. None of them. The OCC was on a child WorkItem, not
the spawn group, and nothing we could find should have been touching that child.

The actual cause was in `MultiInstanceSpawnService.createGroup()`:

```java
group.onThresholdReached = template.onThresholdReached != null
        ? template.onThresholdReached
        : OnThresholdReached.CANCEL.name();  // ← this
```

CANCEL was the default. The test set `instanceCount=3, requiredCount=2`. When
the second child completed and hit the threshold, the coordinator asynchronously
cancelled child[2]. The test then tried to complete child[2] — loaded it at
version V, the cancel committed version V+1, OCC.

The immediate fix: only complete `requiredCount` children in the test. The
surplus child is cancelled by the coordinator; trying to complete it races
with that cancellation.

## But the real question

Once I understood the root cause, the question wasn't "how do we fix the test?"
It was "why is CANCEL the default at all?"

A library that cancels work items by default — without an explicit opt-in — is
making an aggressive choice on behalf of every user who doesn't think about it.
Most users of a multi-instance group probably want to see all outcomes, not have
the library clean up for them.

We changed the default to KEEP (null): no side effects when the threshold fires.
CANCEL is now an explicit opt-in. We also added SUSPEND — pause active children
without cancelling them — for workflows where in-progress work should be halted
but not discarded.

The enum went from:

```java
CANCEL,  // cancel remaining
LEAVE    // leave remaining active (was the non-default)
```

To:

```java
KEEP,    // leave remaining active (now the default — null means KEEP)
SUSPEND, // pause ASSIGNED/IN_PROGRESS children
CANCEL   // opt-in only, must be set explicitly
```

Defaults encode assumptions. CANCEL as a default said "we assume you don't want
surplus work." KEEP says "we make no assumptions — you tell us what you want."
For a library, the second is almost always right.
