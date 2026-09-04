# Design — WorkItem Status & Lifecycle Fixes

**Branch:** `issue-243-status-lifecycle-fixes`
**Date:** 2026-06-03 (revised after review)
**Issues:** #243, #244, #245, #239 (verify), #241

---

## #243 — EXPIRED in isTerminal()

### Fix

`WorkItemStatus.isTerminal()` adds EXPIRED to the switch case:

```java
case COMPLETED, REJECTED, CANCELLED, ESCALATED, EXPIRED -> true;
```

DELEGATED stays non-terminal (PLATFORM.md Known Overlap Risk #3: Qhorus DELEGATED is terminal for the obligor; WorkItem DELEGATED is not — conflating them would break integration bridges).

### Call sites affected

`isTerminal()` is called in eight places. Adding EXPIRED changes behaviour at every one:

| Call site | Effect of fix |
|-----------|---------------|
| `cancel()` | EXPIRED items can currently be cancelled — now blocked with IllegalStateException |
| `extend()` | EXPIRED items can currently be extended — now blocked with IllegalStateException |
| `completeFromSystem()` | silently returns on terminal; currently allows EXPIRED → COMPLETED double-transition — now blocked |
| `rejectFromSystem()` | silently returns on terminal; currently allows EXPIRED → REJECTED double-transition — now blocked |
| `WorkItemSpawnService:85` | EXPIRED parents can currently spawn children — now blocked |
| `MultiInstanceCoordinator:55` | EXPIRED children already excluded from quorum count (correct) — no behavioural change |
| `InboxSummaryBuilder:60` | EXPIRED items currently appear as "active" in inbox summaries — now excluded |
| `WorkItemMetrics` (3 sites) | EXPIRED items currently counted as "active" in all metrics — now excluded |

The `completeFromSystem`/`rejectFromSystem` double-transition bug is the most consequential — it silently overwrites EXPIRED with COMPLETED/REJECTED in multi-instance scenarios, producing a misleading audit trail.

### WorkItemQuery independence

`WorkItemQuery.expired(now)` hardcodes `statusIn([PENDING, ASSIGNED, IN_PROGRESS, SUSPENDED])` — it does not derive this list from `isTerminal()`. These are independent guards. Post-#245, DELEGATED is added to `expired()`'s status list (separately — see #245 below).

### isActive()

DELEGATED is added to `isActive()` as part of #245. EXPIRED is not — it is neither active nor terminal was the bug, and after this fix it is terminal.

---

## #244 — ESCALATED gets real meaning

### Problem

`BreachExecutionFailed` is never thrown, so the Chained fallback mechanism is dead code. `WorkItemStatus.ESCALATED` is in `isTerminal()` but never set on any WorkItem. `executeEscalateTo` fires an `"ESCALATED"` audit/lifecycle event while the item is status PENDING — this is a pre-existing naming collision that this fix also resolves.

### BreachDecision.Exhausted (in casehub-work-api)

Add a fifth variant to the sealed interface:

```java
/** All configured SLA breach policy branches have been exhausted. Operator intervention required. */
record Exhausted(String reason) implements BreachDecision {}
```

Updated `permits` clause:

```java
public sealed interface BreachDecision
        permits BreachDecision.Fail, BreachDecision.EscalateTo,
                BreachDecision.Extend, BreachDecision.Chained, BreachDecision.Exhausted {
```

`executeExhausted()` returns `BreachDecision.Exhausted("policy-exhausted")` as the leaf decision for `SlaBreachEvent`. Observers see an honest type — the event correctly signals that the item is in the `ESCALATED` terminal state.

### Rename "SLA_REASSIGNED" for reassignment events

`executeEscalateTo` currently fires audit/lifecycle event `"ESCALATED"` when the item is set to PENDING with new groups. After this fix, `"ESCALATED"` the lifecycle event string would also be used by `executeExhausted()` when the item reaches the terminal `ESCALATED` status. Two operations with the same event name are ambiguous.

Rename `executeEscalateTo`'s audit and lifecycle event from `"ESCALATED"` to `"SLA_REASSIGNED"`. This accurately describes what happened: the item was reassigned to a new pool due to SLA breach, but is still active.

### ExpiryLifecycleService changes

**`executeEscalateTo`:**
- Remove the inline `if (escalate.groups().isEmpty())` guard block (including the LOG + executeFail fallback)
- Replace with: `if (escalate.groups().isEmpty()) { LOG.errorf("..."); throw new BreachExecutionFailed("EscalateTo returned empty groups"); }`
- BreachExecutionFailed is thrown BEFORE any state mutation — safe to catch externally
- Rename audit/event emission from `"ESCALATED"` to `"SLA_REASSIGNED"`

**`executeBreachDecision` EscalateTo case:**
- Remove the existing `if (escalate.groups().isEmpty())` guard block (it moves into `executeEscalateTo`)
- The case now delegates directly: `yield executeEscalateTo(item, escalate, ctx, now);`

**`executeBreachDecision` Chained case:**

```java
case BreachDecision.Chained chained -> {
    try {
        yield executeBreachDecision(item, chained.primary(), ctx, now);
    } catch (final BreachExecutionFailed e) {
        try {
            yield executeBreachDecision(item, chained.fallback(), ctx, now);
        } catch (final BreachExecutionFailed e2) {
            yield executeExhausted(item, "policy-exhausted", now);
        }
    }
}
```

Note: `BreachExecutionFailed` is a private static final class in `ExpiryLifecycleService`. The Chained handler catches it only because it is in the same class. This is intentional — the exception is a private control-flow signal, not a public contract.

**`executeExhausted(WorkItem item, String reason, Instant now)`:**

```java
private BreachDecision.Exhausted executeExhausted(final WorkItem item, final String reason, final Instant now) {
    item.status = WorkItemStatus.ESCALATED;
    item.completedAt = now;
    workItemStore.put(item);
    writeAudit(item, "ESCALATED", reason, now);
    fireLifecycleEvent("ESCALATED", item);
    return new BreachDecision.Exhausted(reason);
}
```

The `reason` parameter flows into the audit entry detail and the `BreachDecision.Exhausted` returned to `SlaBreachEvent` — consumers see the specific cause, not a hardcoded string.

**`checkExpired()` and `checkClaimDeadlines()` — batch safety:**

Wrap each item's processing in a try-catch for `BreachExecutionFailed`. A misconfigured non-Chained `EscalateTo` (empty groups) throws before any state mutation; the catch handles it:

```java
} catch (final BreachExecutionFailed e) {
    LOG.errorf("SLA breach policy misconfigured for WorkItem %s — skipping this tick: %s", item.id, e.getMessage());
    final AuditEntry entry = new AuditEntry();
    entry.workItemId = item.id;
    entry.event = "BREACH_POLICY_MISCONFIGURED";
    entry.actor = "system";
    entry.detail = e.getMessage();
    entry.occurredAt = now;
    auditStore.append(entry);
}
```

An audit entry is written (not just a log line) — the audit trail is the observable record. The item stays in its current state and is retried on the next tick.

**`executeBreachDecision` Exhausted case:** add to the switch:

```java
case BreachDecision.Exhausted exhausted -> executeExhausted(item, exhausted.reason(), now);
```

`SlaBreachPolicy` implementations may return `BreachDecision.Exhausted` directly; the reason passes through to the audit and event.

### Summary: ESCALATED semantics

ESCALATED = all configured SLA breach policy branches exhausted; operator intervention required. It is terminal (`isTerminal()` = true — correct). It requires explicit operator action to resolve (create a new WorkItem or cancel is the current resolution path).

`FilterRegistryEngine` already maps ESCALATED to `FilterEvent.REMOVE` — no change. Update the `FilterEvent.REMOVE` case comment to include EXPIRED and ESCALATED explicitly.

---

## #245 — DELEGATED lifecycle

### Status classification

`WorkItemStatus.DELEGATED` added to `isActive()`:

```java
case PENDING, ASSIGNED, IN_PROGRESS, SUSPENDED, DELEGATED -> true;
```

`WorkItemStatus.DELEGATED` stays non-terminal in `isTerminal()`.

### Expiry of DELEGATED items

DELEGATED items **can expire**. The SLA is a team/business obligation; internal hand-offs do not pause the clock. `WorkItemQuery.expired()` gains DELEGATED:

```java
.statusIn(List.of(PENDING, ASSIGNED, IN_PROGRESS, SUSPENDED, DELEGATED))
```

If the item expires while DELEGATED, the expiry job processes it normally (Fail → EXPIRED, or EscalateTo → SLA_REASSIGNED with new pool). The delegatee's `accept-delegation` call will then fail with IllegalStateException (status is no longer DELEGATED) — correct behaviour, the SLA was breached.

`claimExpired()` remains PENDING-only. Claim SLA tracking is a pool concern; DELEGATED items are not in the pool.

### Drop DelegationState

`DelegationState.java` deleted. `WorkItemStatus.DELEGATED` replaces the pre-acceptance semantics. Files updated:

- `WorkItem.java` — remove `@Column(name="delegation_state") DelegationState delegationState`
- `MongoWorkItemDocument.java` — remove `delegationState` field and mapping
- `WorkItemResponse.java` — remove `delegationState` field
- `WorkItemWithAuditResponse.java` — remove `delegationState` field
- `WorkItemMapper.java` — remove mapping

This is a **breaking API change** on `WorkItemResponse` and `WorkItemWithAuditResponse`. Acceptable for 0.2-SNAPSHOT — no compatibility guarantees; no migration path needed.

### New field: delegationDeclineTarget (typed)

`WorkItemEntity` entity:

```java
@Column(name = "delegation_decline_target")
@Enumerated(EnumType.STRING)
public DeclineTarget delegationDeclineTarget;
```

`DeclineTarget` is the enum defined in casehub-work-api (see below). Null = use scope preference. Stored as VARCHAR(10).

`MongoWorkItemDocument`: add `String delegationDeclineTarget` with mapping:

```java
doc.delegationDeclineTarget = wi.delegationDeclineTarget != null ? wi.delegationDeclineTarget.name() : null;
wi.delegationDeclineTarget = raw != null ? DeclineTarget.valueOf(raw) : null;
```

### New preference key: DeclineTarget (in casehub-work-api)

```java
public enum DeclineTarget implements SingleValuePreference {
    POOL, DELEGATOR;

    public static final PreferenceKey<DeclineTarget> KEY =
        new PreferenceKey<>("casehub.work.delegation", "decline-target", POOL,
                            s -> DeclineTarget.valueOf(s.toUpperCase(Locale.ROOT)));
}
```

Default: `POOL`. Qualifed name: `casehub.work.delegation.decline-target`.

### AssignmentTrigger.DELEGATION_DECLINED (in casehub-work-api)

Add to `AssignmentTrigger`:

```java
/** WorkItem returned to pool after a decline of a targeted delegation. */
DELEGATION_DECLINED
```

A declined delegation is distinct from a voluntary release — strategy implementations that differentiate triggers should handle this separately.

### delegate() changes

**Status assignment:** Set `item.status = WorkItemStatus.DELEGATED` **unconditionally AFTER** `assignmentService.assign()`. This overrides any `ASSIGNED` status the strategy may have set (the strategy may pick the toAssigneeId and mark ASSIGNED — we need DELEGATED instead since acceptance hasn't happened). Remove `item.status = WorkItemStatus.PENDING` from the if-branch.

**SLA tracking:** `delegate()` sets `item.claimDeadline = null` and `item.lastReturnedToPoolAt = null`. DELEGATED is a directly-addressed state; pool-SLA tracking fields do not apply while in DELEGATED.

**Decline target:** Read optional `declineTarget` from `DelegateRequest` body; if present and valid, store on `item.delegationDeclineTarget`.

**Remove:** `item.delegationState = DelegationState.PENDING`.

**assigneeId:** Unchanged — toAssigneeId or strategy-selected actor.

### delegationChain field

The `delegationChain` field is a comma-separated list of actorIds representing the chain of actors who delegated the item (most recent delegator last). The field comment currently says "JSON-serialised audit trail" — **this is wrong and must be corrected** to describe the actual CSV format.

The CSV format is safe for current data because actorIds are UUIDs (containing only hex digits and hyphens, never commas). The limitation — inability to carry structured per-hop data (timestamps, reasons) — is acknowledged as explicit debt against #240 (human task lifecycle alignment), which will redesign the delegation chain if richer semantics are needed.

### New service methods

Both are `@Transactional`.

**`acceptDelegation(UUID id, String claimantId)`:**
- Validates: `item.status == DELEGATED && item.assigneeId.equals(claimantId)`; throws `IllegalStateException` otherwise
- Concurrent calls: the state guard (`status != DELEGATED`) catches the second caller → `IllegalStateException` → 409
- Sets: `item.status = ASSIGNED`, `item.assignedAt = now`
- Clears: `item.delegationDeclineTarget = null` (no longer needed)
- Audit: `"DELEGATION_ACCEPTED"`
- Lifecycle event: `"DELEGATION_ACCEPTED"`

**`declineDelegation(UUID id, String actorId)`:**
- Validates: `item.status == DELEGATED && item.assigneeId.equals(actorId)`; throws `IllegalStateException` otherwise
- Resolves decline target:
  ```java
  final DeclineTarget target = item.delegationDeclineTarget != null
      ? item.delegationDeclineTarget
      : preferenceProvider.resolve(new SettingsScope(
              item.scope != null ? Path.parse(item.scope) : Path.root(),
              Instant.now()))
          .getOrDefault(DeclineTarget.KEY);
  ```
- **POOL path:** `item.status = PENDING`, `item.assigneeId = null`, `item.lastReturnedToPoolAt = now`, recompute `item.claimDeadline` via `computeNewClaimDeadline(item, now)`, call `assignmentService.assign(item, AssignmentTrigger.DELEGATION_DECLINED)` (not RELEASED — semantically distinct)
- **DELEGATOR path:** parse prevActor = last entry of `item.delegationChain.split(",")`. No exclusion policy check — prevActor was a verified legitimate holder who delegated the item; restoring a previous assignment state is not a new assignment, and running exclusion could trap the item if the exclusion list changed post-delegation. Set `item.assigneeId = prevActor`, `item.status = ASSIGNED`, `item.assignedAt = now`
- Both paths: `item.delegationDeclineTarget = null`
- Audit: `"DELEGATION_DECLINED"`
- Lifecycle event: `"DELEGATION_DECLINED"`

`WorkItemService` gains `@Inject PreferenceProvider preferenceProvider` (already used by `ExpiryLifecycleService` — same pattern).

`claim()` guard unchanged: only accepts PENDING — DELEGATED items must use `accept-delegation`.

### V34 Flyway migration

`runtime/src/main/resources/db/work/migration/V34__delegation_state_drop_and_decline_target.sql`

V34 is the next available version (V33 = `workitem_template_excluded_groups`, verified against source).

```sql
ALTER TABLE work_item DROP COLUMN delegation_state;
ALTER TABLE work_item ADD COLUMN delegation_decline_target VARCHAR(10);
```

### New REST endpoints

`WorkItemResource`:

```java
@PUT
@Path("/{id}/accept-delegation")
public Response acceptDelegation(@PathParam("id") UUID id, @QueryParam("claimant") String claimant) {
    // → workItemService.acceptDelegation(id, claimant)
    // IllegalStateException → 409
}

@PUT
@Path("/{id}/decline-delegation")
public Response declineDelegation(@PathParam("id") UUID id, @QueryParam("actor") String actor) {
    // → workItemService.declineDelegation(id, actor)
    // IllegalStateException → 409
}
```

`DelegateRequest` body gains optional `declineTarget` field:

```java
record DelegateRequest(String to, DeclineTarget declineTarget) {}
```

---

## #239 — GroupMembershipProvider callers

All production callers in casehub-work already use `Set<GroupMember>` correctly:
- `TemplateExpander` uses `m.actorId()` ✅
- `NoOpGroupMembershipProvider` returns `Set<GroupMember>` ✅

No `WorkBroker`, `WorkerSelectionStrategy`, or `ExclusionPolicy` calls `membersOf()` directly. Compilation confirms no errors. Close this issue on the branch.

---

## #241 — findById in WorkItemService

Add to `WorkItemService`:

```java
public Optional<WorkItem> findById(UUID id) {
    return workItemStore.get(id);
}
```

Update `WorkItemResource.getById()`:

```java
@GET
@Path("/{id}")
public WorkItemWithAuditResponse getById(@PathParam("id") final UUID id) {
    final WorkItem wi = workItemService.findById(id)
            .orElseThrow(() -> new WorkItemNotFoundException(id));
    final List<AuditEntry> trail = auditStore.findByWorkItemId(id);
    return WorkItemMapper.toWithAudit(wi, trail);
}
```

The `workItemStore` injection remains in `WorkItemResource` (used for `listAll`, `inbox`, `inboxSummary`, children, parent lookups). This method stops bypassing the service.

---

## Implementation Order

1. #243: `WorkItemStatus.isTerminal()` + `isActive()` (partial, DELEGATED deferred to #245) + tests
2. #244: `BreachDecision.Exhausted` in API; `ExpiryLifecycleService` Option B wiring + tests
3. #245: `AssignmentTrigger.DELEGATION_DECLINED` in API; `DeclineTarget` enum + preference key in API; drop `DelegationState`, add DELEGATED lifecycle, V34 migration, REST endpoints + tests; `WorkItemQuery.expired()` update
4. #239: Verify + close issue
5. #241: `WorkItemService.findById()` + update REST endpoint + test
