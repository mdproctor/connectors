---
layout: post
title: "The Hardcoded Seven"
date: 2026-06-22
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-work]
tags: [jpa, terminal-status, occ, bug-fix]
---

The #270 review surfaced two follow-up issues. #271 was a pre-existing bug: `JpaWorkItemStore.countByParentAndAssignee()` hardcoded a terminal status exclusion list — COMPLETED, REJECTED, CANCELLED, ESCALATED — four of seven. FAULTED, OBSOLETE, and EXPIRED leaked through, meaning a user whose previous WorkItem instance had faulted could be permanently blocked from claiming new instances in an `allowSameAssignee=false` group.

The interesting part was the scope. The issue named one method in one store. Searching for the same pattern found a second instance in `JpaCrossTenantWorkItemStore.findActiveWithDeadlines()` — five of seven there, a slightly different subset but the same root cause. Meanwhile, both MongoDB stores had independently gotten it right from the start: `Arrays.stream(WorkItemStatus.values()).filter(WorkItemStatus::isTerminal)`. The JPA stores were written earlier, before all seven terminal statuses existed. The Mongo stores were written after #240 added FAULTED and OBSOLETE, so they used the dynamic pattern from day one.

The fix was to centralise the derivation. `WorkItemStatus.TERMINAL_STATUSES` is now a static constant on the enum itself — derived once from `isTerminal()`, referenced by all four stores. A guard test verifies the constant stays in sync with `isTerminal()` if someone adds a new terminal status to the switch expression but forgets the constant (though since the constant *derives from* `isTerminal()`, this can't actually drift — the test is a documentation backstop, not a safety net).

#272 was pure docs: the `WorkItemStore.put()` javadoc now records that production implementations must provide OCC. The service layer relies on it for claim atomicity — two nodes racing to claim the same WorkItem need exactly one `OptimisticLockException`. The InMemory store is exempt because it's test-only.

Both issues closed. The #270 review follow-ups are done.
