---
layout: post
title: "Policy owns the reason"
date: 2026-05-19
type: phase-update
entry_type: note
subtype: log
projects: [casehub-work]
tags: [exclusion-policy, audit, jta, requires-new, spi]
---

`ExclusionPolicy.isExcluded()` returned a boolean. It answered the wrong question.

The question isn't "is this user excluded?" It's "why is this user excluded, and
what should the audit trail say?" A boolean can't carry that. The policy
implementation — whether that's a comma-separated list check, an LDAP group query,
or a time-window rule — is the entity that knows why it denied. `WorkItemService`
doesn't. It can only reconstruct something hardcoded like `"user in exclusion list"`,
which is useless once you have custom implementations.

The change was to return `PolicyDecision` instead: `denied` boolean plus `reason`
string. Denied decisions carry a human-readable reason the policy wrote. That reason
flows into the exception message and the audit trail without the service knowing
anything about the policy's internals. A future LDAP implementation can say
`"user 'alice' is member of 'conflict-of-interest-reviewers'"` without touching
`WorkItemService`. The `ALLOW` constant is a shared instance to avoid allocation on
the hot path; `PolicyDecision.deny(reason)` is the factory for the other case.

I wanted this partly for the new audit feature — blocked claim and delegate attempts
now get `CLAIM_DENIED` and `DELEGATE_DENIED` entries — and partly because a boolean
SPI is always the wrong shape for a policy. Policies have context.

## Getting the audit entry to survive the rollback

`claim()` is `@Transactional`. When it throws `IllegalStateException` for an excluded
user, JTA rolls back. Any audit entry written in the same transaction disappears
with it.

The answer is `@Transactional(REQUIRES_NEW)`: a new transaction that suspends the
outer one, commits independently, then lets the outer rollback proceed. But there's
a non-obvious invariant: the REQUIRES_NEW method must return normally. If it throws
— say, a transient datasource failure during the `INSERT` — its own REQUIRES_NEW
transaction also rolls back. The audit entry is lost, and the exception propagates
to replace the caller's 409 with a 500.

```java
@Transactional(TxType.REQUIRES_NEW)
public void record(UUID workItemId, String event, String actor, String detail) {
    try {
        // ... populate and append entry
    } catch (Exception e) {
        LOG.log(Level.WARNING, "Failed to record blocked attempt audit", e);
    }
}
```

Swallowing exceptions here is correct. A write failure during audit recording must
not convert a policy rejection into an unexpected server error.

## The bug that only appears once you add a committed side effect

Claude caught this in code review before any commit landed: in both `claim()` and
`delegate()`, the exclusion check fired before the status guard.

A user who happens to be excluded trying to claim a COMPLETED WorkItem would get a
409 — correct — but the audit trail would show `CLAIM_DENIED`. The status guard
would have rejected the attempt regardless; the exclusion policy never actually
mattered. Phantom audit entries, representing an enforcement that had no effect.

The fix is status guard first, exclusion check only if status is valid. The ordering
issue is invisible until you attach a committed side effect to the second guard —
before `REQUIRES_NEW`, both orderings produce the same observable result. It's the
kind of bug that passes every test you'd think to write.

There were also a handful of smaller fixes this session: `permittedOutcomes` missing
from the audit-trail response type, null `claimantId` reaching the exclusion check
before validation, the RoundRobin strategy not pre-filtering excluded users from
candidates, and a UNIQUE constraint on template names that surfaced hidden test
isolation debt across a shared H2 instance.
