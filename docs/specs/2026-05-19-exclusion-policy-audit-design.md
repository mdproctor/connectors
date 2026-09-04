# Exclusion Policy Audit — Design Spec
**Issue:** casehubio/work#186
**Date:** 2026-05-19

## Problem

When an excluded user attempts to `claim()` or `delegate()` a WorkItem, the operation is
rejected with 409/400 but no audit entry is written. For compliance scenarios (conflict-of-
interest enforcement, SOX controls, regulatory audits), the *attempt* is as significant as the
*outcome* — an auditor needs to see that alice tried to claim a WorkItem she was excluded from,
and that the platform blocked it.

The root obstacle is transactional: `claim()` and `delegate()` are `@Transactional`, and JTA
rolls back on unchecked exceptions. Any `audit()` call inside the same transaction is lost with
the rollback. The audit entry must commit in an independent transaction before the outer one
rolls back.

A second gap: `ExclusionPolicy.isExcluded()` returns `boolean`, discarding the *reason* for the
denial. The policy implementation knows why it denied — "user in comma-separated list",
"user in LDAP group X", "within 30-day cooling-off period" — but has no channel to communicate
that to the exception message or audit detail. Both end up hardcoded in `WorkItemService`, which
is wrong: the service should not be in the business of constructing denial narratives.

## Scope

**In scope:** `claim()` and `delegate()` enforcement points.

**Out of scope:** `create()` with excluded direct assignee (WorkItem has no ID before persist;
tracked in #192), `resolveCandidates()` silent filter (no actor, no exception — different
semantics).

## Design

### 1 — `PolicyDecision` record (api module, Tier 1)

```java
package io.casehub.work.api;

public record PolicyDecision(boolean denied, String reason) {

    public static final PolicyDecision ALLOW = new PolicyDecision(false, null);

    public static PolicyDecision deny(final String reason) {
        return new PolicyDecision(true, reason);
    }

    public boolean allowed() { return !denied; }
}
```

Pure Java, no dependencies. Lives in `casehub-work-api` alongside `ExclusionPolicy`.

### 2 — `ExclusionPolicy` SPI change (api module, Tier 1)

`isExcluded(String userId, String excludedUsers) : boolean` →
`check(String userId, String excludedUsers) : PolicyDecision`

```java
public interface ExclusionPolicy {
    PolicyDecision check(String userId, String excludedUsers);
}
```

`CommaSeparatedExclusionPolicy` updated to return:
- `PolicyDecision.ALLOW` when not excluded
- `PolicyDecision.deny("user '" + userId + "' in comma-separated exclusion list")` when excluded

The reason string is now owned by the policy implementation that knows why it denied.
Future implementations — LDAP groups, role-based, time-window — provide their own reasons
without touching `WorkItemService`.

### 3 — `BlockedAttemptAuditService` (runtime module)

A dedicated `@ApplicationScoped` CDI bean responsible exclusively for writing blocked-attempt
audit entries in independent transactions.

```java
@ApplicationScoped
public class BlockedAttemptAuditService {

    private final AuditEntryStore auditStore;

    @Inject
    public BlockedAttemptAuditService(final AuditEntryStore auditStore) {
        this.auditStore = auditStore;
    }

    /**
     * Records a blocked lifecycle attempt in its own committed transaction.
     *
     * Must be called BEFORE throwing — the method returns normally so that
     * the REQUIRES_NEW transaction commits. The caller then throws, rolling
     * back the outer transaction. The audit entry survives.
     */
    @Transactional(TxType.REQUIRES_NEW)
    public void record(final UUID workItemId, final String event,
                       final String actor, final String detail) {
        final AuditEntry entry = new AuditEntry();
        entry.workItemId = workItemId;
        entry.event = event;
        entry.actor = actor;
        entry.detail = detail;
        auditStore.append(entry);
    }
}
```

The `record()` method **must return normally** — it does not throw. If it throws, its own
REQUIRES_NEW transaction rolls back too, losing the entry. The caller is responsible for
throwing after `record()` returns.

### 4 — `WorkItemService` call sites

Pattern at each enforcement point:

```java
final PolicyDecision decision = exclusionPolicy.check(claimantId, item.excludedUsers);
if (decision.denied()) {
    blockedAuditService.record(item.id, "CLAIM_DENIED", claimantId, decision.reason());
    throw new IllegalStateException(decision.reason());
}
```

Same pattern for `delegate()`:

```java
final PolicyDecision decision = exclusionPolicy.check(toAssigneeId, item.excludedUsers);
if (decision.denied()) {
    blockedAuditService.record(item.id, "DELEGATE_DENIED", actorId,
            "target:" + toAssigneeId + "; reason:" + decision.reason());
    throw new IllegalArgumentException(decision.reason());
}
```

For `delegate()`, `actorId` is the actor (who tried to delegate) and `toAssigneeId` is the
rejected target — both captured in the detail field.

`BlockedAttemptAuditService` is injected into `WorkItemService` via constructor, alongside the
existing `AuditEntryStore`.

### 5 — New audit event strings

| Event | Trigger |
|-------|---------|
| `CLAIM_DENIED` | Excluded user attempted to claim |
| `DELEGATE_DENIED` | Actor attempted to delegate to excluded user |

These appear in `GET /workitems/{id}/audit` alongside existing lifecycle events. No new
endpoint. No Flyway migration — `audit_entry` already has the correct shape.

### 6 — `InMemoryAuditEntryStore` (testing module)

Unchanged. `BlockedAttemptAuditService` is an `@ApplicationScoped` CDI bean and participates
in normal CDI discovery — the testing module's `InMemoryAuditEntryStore` is the `@Alternative`
`AuditEntryStore` used in tests. No testing-module changes needed.

## What changes

| Location | Change |
|----------|--------|
| `casehub-work-api` | Add `PolicyDecision` record |
| `casehub-work-api` | `ExclusionPolicy.isExcluded()` → `check()` returning `PolicyDecision` |
| `runtime/service` | Add `BlockedAttemptAuditService` |
| `runtime/service` | `CommaSeparatedExclusionPolicy` — return `PolicyDecision` |
| `runtime/service` | `WorkItemService` — inject `BlockedAttemptAuditService`, update two enforcement points |
| Tests | New: `PolicyDecisionTest`, `BlockedAttemptAuditServiceTest`, extend `WorkItemExcludedUsersTest` |

## What does NOT change

- `AuditEntry` entity — no new fields, no migration
- `audit_entry` table schema
- `GET /workitems/{id}/audit` endpoint — blocked attempts appear automatically
- `InMemoryAuditEntryStore` — no changes
- The enforcement logic itself — only the call sites gain an audit step

## Out-of-scope issues filed

- **#192** — `CREATE_DENIED` audit for direct-assignee exclusion in `create()` — requires
  pre-generating WorkItem ID before the exclusion check; semantic question about orphaned
  audit entries to resolve.

## Testing approach

**Unit tests (`casehub-work-api`):**
- `PolicyDecisionTest` — `ALLOW` constant, `deny()` factory, `allowed()` predicate

**Unit tests (runtime):**
- `CommaSeparatedExclusionPolicyTest` — verify `check()` returns `ALLOW` and `deny(reason)`
  correctly; reason string contains the userId

**Integration tests (`@QuarkusTest`):**
- `WorkItemExcludedUsersTest` — extend existing test class:
  - `claim_byExcludedUser_createsAuditEntry()` — audit trail contains `CLAIM_DENIED` with
    correct actor and reason
  - `delegate_toExcludedUser_createsAuditEntry()` — audit trail contains `DELEGATE_DENIED`
    with correct actorId and target in detail
  - `claim_byAllowedUser_doesNotCreateDeniedEntry()` — audit trail contains `ASSIGNED`, not
    `CLAIM_DENIED`
  - `blockedAuditEntry_persistsEvenAfterRejection()` — verify the entry survives the 409
    (i.e., is in the DB after the failed claim)

The last test is the most important: it verifies the REQUIRES_NEW semantics are correct — that
the audit entry exists in the DB even though the claim was rejected.
