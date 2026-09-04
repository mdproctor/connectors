# Design: Human Task Lifecycle Alignment

**Issue:** casehubio/work#240
**Date:** 2026-06-18
**Status:** Draft — revision 3 after second review

---

## Goal

Align the WorkItem lifecycle state machine with industry specs (WS-HumanTask 1.1, OpenHumanTask, BPMN 2.0, CMMN 1.1) while maintaining coherence across all six lifecycle layers in the casehub platform. Produce a design that closes spec gaps, fixes cross-system inconsistencies, and establishes `docs/LIFECYCLE.md` in casehub-parent as the normative lifecycle reference for the entire platform.

---

## The Six Lifecycle Layers

The platform has six distinct lifecycle state machines, top to bottom:

| Layer | System | Enum | States | Terminal |
|---|---|---|---|---|
| 1 | Serverless Workflow (CNCF SDK) | `WorkflowStatus` | PENDING, RUNNING, WAITING, COMPLETED, FAULTED, CANCELLED, SUSPENDED | COMPLETED, FAULTED, CANCELLED |
| 2 | Case (casehub-engine) | `CaseStatus` | STARTING, RUNNING, WAITING, SUSPENDED, COMPLETED, FAULTED, CANCELLED | COMPLETED, FAULTED, CANCELLED |
| 3 | PlanItem (casehub-engine blackboard) | `PlanItemStatus` | PENDING, RUNNING, DELEGATED, COMPLETED, FAULTED, REJECTED, CANCELLED | COMPLETED, FAULTED, REJECTED, CANCELLED |
| 4 | HumanTask (casehub-engine) | Definition type — no lifecycle enum | Expressed through PlanItem (DELEGATED) + WorkItem states | — |
| 5 | WorkItem (casehub-work) | `WorkItemStatus` | PENDING, ASSIGNED, IN_PROGRESS, DELEGATED, SUSPENDED, COMPLETED, REJECTED, CANCELLED, EXPIRED, ESCALATED | COMPLETED, REJECTED, CANCELLED, EXPIRED, ESCALATED |
| 6 | Commitment (casehub-qhorus) | `CommitmentState` | OPEN, ACKNOWLEDGED, FULFILLED, DECLINED, FAILED, DELEGATED, EXPIRED | FULFILLED, DECLINED, FAILED, DELEGATED, EXPIRED |

Auxiliary: `WorkEventType` (16 values), `MessageType` (9 speech acts), `GroupStatus` (3 values), `AssignmentTrigger` (5 values), `BreachDecision` (5 variants), `DeclineTarget` (2 values).

Four `BindingTarget` types determine how PlanItems enter their active state:
- `CapabilityTarget` → PlanItem RUNNING (Quartz job executing locally)
- `HumanTaskTarget` → PlanItem DELEGATED (creates WorkItem in casehub-work)
- `SubCaseTarget` → PlanItem DELEGATED (starts child case)
- `ExtensionTarget` → PlanItem DELEGATED (dispatches to external extension)

---

## Issues Found

### Issue 1 — FAULTED gap at WorkItem layer

Every layer except WorkItem distinguishes system failure from actor refusal:

| Layer | System failure | Actor refusal |
|---|---|---|
| WorkflowStatus | FAULTED | — |
| CaseStatus | FAULTED | — |
| PlanItemStatus | FAULTED | REJECTED |
| **WorkItemStatus** | **missing** | REJECTED |
| CommitmentState | FAILED | DECLINED |

When an AI agent hits an API timeout, context overflow, or unhandled exception, that is not REJECTED (deliberate refusal) — it is a system fault. Conflating the two corrupts trust scoring: REJECTED implies a deliberate decision to refuse; a system fault implies infrastructure failure with no actor judgment.

**Naming decision:** WS-HumanTask 1.1 and OpenHumanTask use `ERROR`. The rest of the casehub platform uses `FAULTED` (WorkflowStatus, CaseStatus, PlanItemStatus). Platform coherence wins — the name is **FAULTED**.

### Issue 2 — OBSOLETE gap

WS-HumanTask 1.1 and OpenHumanTask both have OBSOLETE — task superseded by context change. Distinct from CANCELLED:

- CANCELLED: a human or system deliberately stopped this work
- OBSOLETE: the case context changed, making this work irrelevant — it was never completed but never needed to be

Example: an IRB review WorkItem becomes OBSOLETE when the clinical trial is withdrawn. The reviewer did nothing wrong. Marking it CANCELLED misattributes the termination to a deliberate action.

No other layer in the platform has OBSOLETE. This is unique to human/agent tasks — workflow steps don't become obsolete, cases can be cancelled but not obsoleted.

### Issue 3 — DELEGATED has three incompatible meanings

| System | DELEGATED means | Terminal? |
|---|---|---|
| PlanItemStatus | Control passed to external actor — engine waiting | No |
| WorkItemStatus | Pre-acceptance hold — targeted to named actor | No |
| CommitmentState | Obligation transferred to new debtor via HANDOFF — original discharged | **Yes** |

PLATFORM.md already flags this as Known Overlap Risk #3. The WorkItem and Commitment meanings are dangerous: a developer building a Qhorus→WorkItem bridge expects DELEGATED obligations to be discharged (Qhorus semantics) but WorkItem DELEGATED means the item is still active.

No code change fixes this — the semantics are correct within each system. The fix is documentation: javadoc on all three must cross-reference the others and explicitly state the difference.

### Issue 4 — WorkEventType has 5 lifecycle event gaps

Five events are fired as lifecycle events via `WorkItemLifecycleEvent.of()` but have no `WorkEventType` enum value. The `eventType()` method silently falls back to `WorkEventType.CREATED` for these — any observer switching on `WorkEventType` misclassifies them:

| Event fired | Source | `eventType()` returns |
|---|---|---|
| DELEGATION_ACCEPTED | `WorkItemService.acceptDelegation()` | CREATED (silent fallback) |
| DELEGATION_DECLINED | `WorkItemService.declineDelegation()` | CREATED |
| SLA_REASSIGNED | `ExpiryLifecycleService.executeEscalateTo()` | CREATED |
| LABEL_ADDED | `WorkItemService.addLabel()` | CREATED |
| LABEL_REMOVED | `WorkItemService.removeLabel()` | CREATED |

**Not affected:** `BREACH_POLICY_MISCONFIGURED` and `SLA_EXTENDED` are audit-only — written via `writeAudit()` with no `fireLifecycleEvent()` call. They never hit the `eventType()` fallback.

**SLA_EXTENDED inconsistency:** `WorkItemService.extend()` fires `DEADLINE_EXTENDED` as a lifecycle event for actor-initiated extensions. `ExpiryLifecycleService.executeExtend()` writes `SLA_EXTENDED` as audit-only with explicit comment "No lifecycle event — deadline extension is not a status transition." Both are deadline extensions distinguished by provenance (actor vs policy). This inconsistency should be resolved: `SLA_EXTENDED` should also fire as a lifecycle event so SSE subscribers see all deadline changes. The actor field ("system" vs actor ID) distinguishes provenance; two distinct event types allow observers to count policy-driven vs actor-initiated extensions separately.

### Issue 5 — PlanItemStatus has no SUSPENDED

WorkItemStatus, CaseStatus, and WorkflowStatus all have SUSPENDED. PlanItemStatus does not. When a WorkItem is SUSPENDED, the PlanItem stays DELEGATED — the engine cannot distinguish "human is working" from "human has paused." Cosmetic for monitoring, does not affect execution correctness. Low priority.

### Issue 6 — ESCALATED naming overload

The word "escalation" is used for two different things:

1. **The action** — re-routing a WorkItem to new groups when an SLA breaches → status stays PENDING, event is `SLA_REASSIGNED`, trigger is `AssignmentTrigger.SLA_ESCALATED`
2. **The terminal state** — all re-routing branches exhausted → status is `ESCALATED`, set by `executeExhausted()`

The code behavior is correct. But the `WorkItemLifecycleAdapter` javadoc says "ESCALATED is not terminal" which conflates the action with the state. The javadoc describes the `EscalateTo` action (non-terminal, status stays PENDING) but labels it with the `ESCALATED` status name. When `status == WorkItemStatus.ESCALATED`, the WorkItem IS terminal — all breach policy branches have been exhausted. The adapter's `handleEscalation()` writes a `workItemEscalated` signal to the case context; it does NOT re-route the WorkItem to new groups.

### Issue 7 — Simple progress reporting gap

AI agents and human actors executing multi-step work have no way to report intermediate progress. Trust scoring benefits from progress signals: an agent reporting 80% before faulting has different trust implications than one that faults immediately.

### Issue 8 — PlanItemFaultedEvent not fired for human-task faults

`PlanItemCompletionApplier` fires `PlanItemRejectedEvent` when a WorkItem is REJECTED (line 102-105), but does NOT fire `PlanItemFaultedEvent` when a WorkItem is EXPIRED → `markFaulted()`. The engine has three CDI events for PlanItem terminal transitions (`PlanItemCompletedEvent`, `PlanItemRejectedEvent`, `PlanItemFaultedEvent`), but only `PlanItemRejectedEvent` is fired by the work-adapter bridge. Any observer of `PlanItemFaultedEvent` (trust scoring, monitoring, case-level fault aggregation) misses human-task-originated faults entirely. This is a pre-existing bug that should be fixed alongside the new FAULTED status.

### Issue 9 — FilterRegistryEngine.toFilterEvent() enumeration fragility

`FilterRegistryEngine.toFilterEvent()` explicitly lists terminal event types for `FilterEvent.REMOVE`:

```java
case COMPLETED, REJECTED, CANCELLED, EXPIRED, ESCALATED -> FilterEvent.REMOVE;
```

FAULTED and OBSOLETE must be added. Without this, FAULTED/OBSOLETE WorkItems stay in filter-managed label queues indefinitely — silent data corruption where queue counts disagree with actual active WorkItems.

---

## Design

### 1. WorkItemStatus — complete state machine (12 values)

```
                     ┌──────── release ──────────────────────┐
                     │                                       ▼
   PENDING ──claim──► ASSIGNED ──start──► IN_PROGRESS
      ▲                  │    │               │      │
      │                  │    └──reject───────┤      │
      │                  │                    │      │
      │         suspend──┤           suspend──┤      │
      │                  ▼                    ▼      │
      │              SUSPENDED ◄──────────────┘      │
      │              (resume restores priorStatus)   │
      │                                              │
      │◄── delegate ─────────────────────────────────┘
      │    (DELEGATED: pre-acceptance hold)
      │         │
      │    accept → ASSIGNED    decline → PENDING or DELEGATOR
      │
      ├──── complete (from IN_PROGRESS) ───────────── COMPLETED
      ├──── reject (from ASSIGNED|IN_PROGRESS) ────── REJECTED
      ├──── fault (from any non-terminal) ─────────── FAULTED      ← NEW
      ├──── cancel (from any non-terminal) ────────── CANCELLED
      ├──── obsolete (from any non-terminal) ──────── OBSOLETE     ← NEW
      ├──── SLA Fail ──────────────────────────────── EXPIRED
      ├──── SLA EscalateTo ────────────────────────── PENDING (new groups)
      └──── SLA Exhausted ─────────────────────────── ESCALATED
```

**Terminal states (7):** COMPLETED, REJECTED, FAULTED, CANCELLED, OBSOLETE, EXPIRED, ESCALATED

**Active states (5):** PENDING, ASSIGNED, IN_PROGRESS, DELEGATED, SUSPENDED

```java
public enum WorkItemStatus {
    PENDING, ASSIGNED, IN_PROGRESS, COMPLETED, REJECTED, FAULTED,
    DELEGATED, SUSPENDED, CANCELLED, EXPIRED, ESCALATED, OBSOLETE;

    public boolean isTerminal() {
        return switch (this) {
            case COMPLETED, REJECTED, FAULTED, CANCELLED, OBSOLETE, EXPIRED, ESCALATED -> true;
            default -> false;
        };
    }

    public boolean isActive() {
        return switch (this) {
            case PENDING, ASSIGNED, IN_PROGRESS, SUSPENDED, DELEGATED -> true;
            default -> false;
        };
    }
}
```

### 2. New WorkItemService methods

**`fault(UUID id, String systemActorId, String errorDetail)`**
- Callable from any non-terminal state — throws on terminal items
- Sets status to FAULTED, completedAt to now, resolution to errorDetail
- Cancels expiry and claim deadline timers
- Fires `WorkItemLifecycleEvent("FAULTED", ...)` (sync + async)
- Typical callers: "system", AI agent IDs
- Semantic: FAULTED means the system hosting or processing this WorkItem failed. PENDING→FAULTED means the infrastructure failed before anyone could act (e.g. WorkItem store became unavailable, tenant queue system crashed, item created with invalid data that prevents processing).

**`faultFromSystem(UUID id, String actorId, String errorDetail)`**
- Idempotent variant — returns silently if the item is already terminal (`if (item.status.isTerminal()) return item;`)
- Used by infrastructure that may race with other terminal transitions (e.g. agent timeout handler that fires concurrently with the agent completing)
- Same transition logic as `fault()` otherwise

**`obsolete(UUID id, String triggeredBy, String reason)`**
- Callable from any non-terminal state — throws on terminal items
- Sets status to OBSOLETE, completedAt to now, resolution to reason
- Cancels expiry and claim deadline timers
- Fires `WorkItemLifecycleEvent("OBSOLETE", ...)` (sync + async)
- Intended caller pattern: OBSOLETE is a context-driven termination, typically triggered by the engine or an orchestrator when the case context changes. Actors who decide work is unwanted should call `cancel()`, not `obsolete()`.

**`obsoleteFromSystem(UUID id, String triggeredBy, String reason)`**
- Idempotent variant — returns silently if the item is already terminal
- Used by engine infrastructure that may race with other terminal transitions

**`cancelFromSystem(UUID id, String actorId, String reason)`**
- Idempotent variant of `cancel()` — returns silently if the item is already terminal
- Completes the `*FromSystem()` pattern: `completeFromSystem`, `rejectFromSystem`, `faultFromSystem`, `obsoleteFromSystem`, `cancelFromSystem` — all five terminal transitions have idempotent variants for infrastructure that may race with other terminal transitions
- Use case: group policy "cancel remaining children on first completion"

**`progress(UUID id, String actorId, Integer percentComplete, String statusNote)`**
- Callable only from IN_PROGRESS
- Updates percentComplete and statusNote fields on the entity
- Does not validate that actorId matches item.assigneeId — follows the same pattern as `complete()` and `reject()` which trust the caller. Actor identity enforcement is an auth concern, not a lifecycle concern.
- Fires `WorkItemLifecycleEvent("PROGRESS_UPDATE", ...)` (sync only — not a terminal event)
- Does NOT change status
- Note: the `percentComplete` and `statusNote` fields persist on the entity and survive transitions to SUSPENDED and back. The `progress()` method guard restricts when new progress is *recorded*, not when progress data *exists*. If the guard needs relaxing in future (e.g. for ASSIGNED items doing preliminary analysis), the entity fields already support it.

### 3. REST API

| Method | Path | Body | Purpose |
|---|---|---|---|
| `PUT` | `/workitems/{id}/fault` | `{ "actor": "...", "errorDetail": "..." }` | System failure → FAULTED |
| `PUT` | `/workitems/{id}/obsolete` | `{ "actor": "...", "reason": "..." }` | Context superseded → OBSOLETE |
| `PUT` | `/workitems/{id}/progress` | `{ "percentComplete": 42, "statusNote": "..." }` | Progress update (IN_PROGRESS only) |

Naming follows existing pattern: verb paths (`claim`, `start`, `complete`, `reject`, `fault`, `obsolete`, `progress`).

### 4. WorkEventType — complete enum (25 values)

```java
public enum WorkEventType {
    // Status transitions
    CREATED, ASSIGNED, STARTED, COMPLETED, REJECTED, FAULTED,
    CANCELLED, OBSOLETE, EXPIRED, ESCALATED,

    // Delegation lifecycle
    DELEGATED, DELEGATION_ACCEPTED, DELEGATION_DECLINED,

    // Pool transitions
    RELEASED, SUSPENDED, RESUMED,

    // SLA events
    CLAIM_EXPIRED, SLA_REASSIGNED, SLA_EXTENDED, DEADLINE_EXTENDED,

    // Operational
    SPAWNED, SIGNAL_RECEIVED, PROGRESS_UPDATE,

    // Labels
    LABEL_ADDED, LABEL_REMOVED
}
```

**Not in this enum:** `BREACH_POLICY_MISCONFIGURED` — this is a diagnostic error marker on the audit trail, not a lifecycle event. It has no `WorkItemLifecycleEvent` and should not have enum parity with real lifecycle events.

**SLA_EXTENDED change:** `ExpiryLifecycleService.executeExtend()` gains a `fireLifecycleEvent("SLA_EXTENDED", item)` call to resolve the inconsistency with actor-initiated `DEADLINE_EXTENDED`.

The `eventType()` fallback in `WorkItemLifecycleEvent` changes from silently returning `CREATED` to letting `valueOf` throw. Any event fired without a corresponding enum value is a bug that tests must catch.

### 5. WorkItem entity — new columns

```java
@Column(name = "percent_complete")
public Integer percentComplete;

@Column(name = "status_note", columnDefinition = "TEXT")
public String statusNote;
```

### 6. Persistence layer updates

**6a. Flyway migration (JPA)**

```sql
-- V37
ALTER TABLE work_item ADD COLUMN percent_complete SMALLINT;
ALTER TABLE work_item ADD COLUMN status_note TEXT;
```

Target version: V37 (current is V36). Verify via Flyway scan script at implementation time in case another branch lands first. Per `docs/FLYWAY.md`, the V1–V999 range is owned by `runtime`.

No migration for status enum — stored as STRING via `@Enumerated(EnumType.STRING)`.

**6b. MongoWorkItemDocument**

Add fields and mappings for MongoDB persistence:

```java
// Fields
public Integer percentComplete;
public String statusNote;

// from() additions
doc.percentComplete = wi.percentComplete;
doc.statusNote = wi.statusNote;

// toDomain() additions
wi.percentComplete = percentComplete;
wi.statusNote = statusNote;
```

Without this, progress data is silently lost for MongoDB-backed deployments.

**Pre-existing gap (separate issue):** `MongoWorkItemDocument` is also missing 13+ fields present on the JPA `WorkItemEntity` entity: `version`, `accumulatedUnclaimedSeconds`, `lastReturnedToPoolAt`, `confidenceScore`, `callerRef`, `parentId`, `scope`, `templateId`, `permittedOutcomes`, `excludedUsers`, `outcome`, `inputDataSchema`, `outputDataSchema`. MongoDB-backed deployments silently lose this data on round-trip. File as a separate casehub-work issue — not introduced by this spec.

### 7. Response DTOs and context builder

**7a. WorkItemResponse and WorkItemWithAuditResponse**

Both response records gain `percentComplete` (Integer, nullable) and `statusNote` (String, nullable). `WorkItemMapper.toResponse()` and `toWithAudit()` map the new fields. This is a REST API change — new fields in the JSON response. Acceptable for 0.2-SNAPSHOT.

**7b. WorkItemContextBuilder.toMap()**

The context builder manually maps every field (no reflection — Hibernate bytecode enhancement requires direct access). Add:

```java
map.put("percentComplete", workItem.percentComplete);
map.put("statusNote", workItem.statusNote);
```

Without this, filter rules cannot evaluate conditions based on progress (e.g. "if percentComplete > 80 and status == FAULTED, apply high-priority-recovery label").

### 8. FilterRegistryEngine.toFilterEvent()

Add FAULTED and OBSOLETE to the terminal event types that trigger `FilterEvent.REMOVE`:

```java
case COMPLETED, REJECTED, FAULTED, CANCELLED, OBSOLETE, EXPIRED, ESCALATED -> FilterEvent.REMOVE;
```

Without this, FAULTED/OBSOLETE WorkItems remain in filter-managed label queues indefinitely — queue counts disagree with actual active WorkItems.

### 9. Bridge mapping — WorkItemLifecycleAdapter + PlanItemCompletionApplier

Three changes in casehub-engine's work-adapter module:

**9a. WorkItemLifecycleAdapter — use isTerminal() instead of explicit enumeration**

The `onWorkItemLifecycle()` method's status filter (lines 80–83) currently explicitly lists terminal statuses. This is the same fragility pattern that caused the original EXPIRED bug (#243). Replace with `isTerminal()`:

```java
// Before (fragile — every new terminal status must be added manually):
if (status != WorkItemStatus.COMPLETED
    && status != WorkItemStatus.REJECTED
    && status != WorkItemStatus.CANCELLED
    && status != WorkItemStatus.EXPIRED) return;

// After (future-proof — ESCALATED is handled before this guard):
if (!status.isTerminal()) return;
```

The ESCALATED pre-check at line 75 already returns before reaching this guard. Any future terminal status automatically flows through to PlanItemCompletionApplier.

**9b. PlanItemCompletionApplier.applyStatus() — two new cases + PlanItemFaultedEvent**

| WorkItemStatus | PlanItem transition | CDI event | Rationale |
|---|---|---|---|
| COMPLETED | `markCompleted()` | *(none currently)* | Success |
| REJECTED | `markRejected()` | `PlanItemRejectedEvent` | Actor refusal |
| FAULTED | `markFaulted()` | `PlanItemFaultedEvent` ← NEW | System failure — direct alignment |
| CANCELLED | `markCancelled()` | *(none)* | Deliberate stop |
| OBSOLETE | `markCancelled()` | *(none)* | Context-driven termination — cancel from engine's view |
| EXPIRED | `markFaulted()` | `PlanItemFaultedEvent` ← FIX (pre-existing gap) | Deadline breach — a type of fault |
| ESCALATED | context signal, stays DELEGATED | *(none)* | All breach policies exhausted |

**PlanItemFaultedEvent parity fix:** Currently `PlanItemRejectedEvent` is fired for REJECTED but no `PlanItemFaultedEvent` is fired for EXPIRED→markFaulted(). This is a pre-existing asymmetry — any observer of `PlanItemFaultedEvent` (trust scoring, monitoring, fault aggregation) misses human-task-originated faults entirely. Fix: fire `PlanItemFaultedEvent` for both FAULTED and EXPIRED.

**EXPIRED→markFaulted() rationale:** PlanItemStatus javadoc already says "FAULTED (computation or timeout failure)." An expired deadline IS a timeout. `markCancelled()` would mean someone deliberately stopped the work — but nobody stopped it; time stopped it. That's a fault, not a cancellation. PlanItemStatus.FAULTED javadoc should gain a clarifying note: "Includes both computation failures (worker exceptions, retry exhaustion) and timeout failures (WorkItem deadline breaches at the casehub-work layer)."

Filed as casehub-engine issue.

**9c. HumanTaskRecoveryService — use isTerminal() instead of explicit EnumSet**

`HumanTaskRecoveryService` (engine work-adapter) has an explicit `EnumSet` at line 53-58:

```java
private static final Set<WorkItemStatus> TERMINAL_STATUSES =
    EnumSet.of(COMPLETED, REJECTED, CANCELLED, EXPIRED);
```

This is the same fragility pattern as the adapter status filter. A FAULTED or OBSOLETE WorkItem that completes during JVM downtime is skipped — the PlanItem stays DELEGATED forever. Fix: replace `TERMINAL_STATUSES.contains(workItem.status)` with `workItem.status.isTerminal()` and delete the `TERMINAL_STATUSES` constant.

ESCALATED passes through to `applier.apply()` which hits the `applyStatus()` default branch and returns false — no PlanItem transition, which is correct (ESCALATED keeps PlanItem DELEGATED). The missing escalation context signal during recovery is a pre-existing gap unrelated to this spec.

**9d. ActionGateCompletionApplier — explicit FAULTED and OBSOLETE mapping**

The `isTerminal()` change in 9a widens the blast radius: FAULTED and OBSOLETE now reach `routeGate()` where previously the explicit status filter would have dropped them. The gate applier's switch must handle both:

```java
switch (status) {
    case COMPLETED -> handleApproved(gateRef, workItem);
    case REJECTED, CANCELLED, OBSOLETE -> handleRejected(gateRef, workItem);
    case EXPIRED, FAULTED -> handleExpired(gateRef);
    default -> LOG.debugf("...");
}
```

Rationale:
- **FAULTED → `handleExpired()`** — system failure prevented a human decision on the gate. Semantically identical to EXPIRED: no approval was given, infrastructure is to blame. Fires `ActionGateExpiredEvent`.
- **OBSOLETE → `handleRejected()`** — case context changed, the gated action is no longer relevant. Semantically closest to CANCELLED: gate terminated without approval, action should not proceed. Fires `ActionGateRejectedEvent`.

**9e. MultiInstanceGroupPolicy.cancelRemainingChildren() — isTerminal() + cancelFromSystem()**

`cancelRemainingChildren()` has an explicit exclusion list at line 120-122:

```java
final List<WorkItemStatus> terminalStatuses = List.of(
    WorkItemStatus.COMPLETED, WorkItemStatus.CANCELLED,
    WorkItemStatus.REJECTED, WorkItemStatus.EXPIRED);
```

Missing FAULTED, OBSOLETE, and ESCALATED. A FAULTED child passes the filter → `cancel()` → `isTerminal()` throws `IllegalStateException` → group policy execution fails. This is a latent runtime bug activated by FAULTED/OBSOLETE.

Two fixes:
1. Replace the explicit list with `isTerminal()` filtering — either change the store query or filter in-memory
2. Use `cancelFromSystem()` (Section 2) instead of `cancel()` for race safety — a child may reach terminal state between the query and the cancel call

`suspendRemainingChildren()` at lines 129-130 is unaffected — it explicitly lists suspendable states (ASSIGNED, IN_PROGRESS) rather than excluding terminal ones.

**9f. ReportService — replace hardcoded status lists with isActive()/isTerminal()**

`ReportService` has four hardcoded status lists with pre-existing bugs that FAULTED/OBSOLETE compound:

| Location | Label | Issue |
|---|---|---|
| Lines 62-63 | activeStatuses | Includes ESCALATED and EXPIRED (both terminal — wrong) |
| Line 65 | terminalStatuses | Missing ESCALATED, EXPIRED, FAULTED, OBSOLETE |
| Lines 220-222 | activeStatuses | Same bug as lines 62-63 |
| Lines 370-371 | terminalStatuses | Has ESCALATED but missing EXPIRED, FAULTED, OBSOLETE |

Fix: derive all status lists from `isActive()`/`isTerminal()` — e.g. `Arrays.stream(WorkItemStatus.values()).filter(WorkItemStatus::isActive).toList()`. Eliminates the fragility pattern permanently for ReportService. Pre-existing bug — can be a separate issue or folded into the casehub-work deliverable.

### 10. WorkItemLifecycleAdapter — javadoc fix

The class javadoc (line 54) says: "ESCALATED is not terminal: the WorkItem re-enters PENDING with new candidate groups." This is wrong. It describes the `EscalateTo` action (non-terminal, status stays PENDING, event is SLA_REASSIGNED) but labels it with the ESCALATED status name.

Fix: "ESCALATED is terminal — all SLA breach policy branches have been exhausted. The adapter writes a `workItemEscalated` signal to the case context so the engine can react (e.g. notify supervisor, create replacement task). The PlanItem stays DELEGATED. The SLA_REASSIGNED event (fired when an EscalateTo decision re-routes the WorkItem to new groups without terminating it) is a completely separate concern that does not go through the adapter — the WorkItem's status is PENDING, not ESCALATED, so the adapter's status filter skips it."

### 11. DELEGATED cross-system documentation

All three DELEGATED declarations gain cross-references in javadoc:

- `WorkItemStatus.DELEGATED`: "Pre-acceptance hold — targeted to a named actor who must accept or decline. Non-terminal. Note: `CommitmentState.DELEGATED` (Qhorus) has opposite terminal semantics — obligation transferred and discharged. `PlanItemStatus.DELEGATED` means control passed to an external actor (broader). See `docs/LIFECYCLE.md` for cross-system DELEGATED semantics."
- `CommitmentState.DELEGATED`: "Obligation transferred to new debtor via HANDOFF — terminal, original obligation discharged. Note: `WorkItemStatus.DELEGATED` (casehub-work) is non-terminal — pre-acceptance hold. See `docs/LIFECYCLE.md`."
- `PlanItemStatus.DELEGATED`: "Control passed to external actor (HumanTask, SubCase, Extension) — engine waiting for completion signal. Non-terminal. Distinct from `WorkItemStatus.DELEGATED` (pre-acceptance hold within the task) and `CommitmentState.DELEGATED` (terminal obligation transfer). See `docs/LIFECYCLE.md`."

### 12. PLATFORM.md updates

**Capability ownership table** — "Human task inbox" entry updated: "12 statuses" (was "10 statuses"). Add: "FAULTED: system failure (distinct from REJECTED actor refusal). OBSOLETE: context superseded. Simple progress: percentComplete + statusNote."

**New implementation protocol entry:**

> **Lifecycle coherence:** Any repo adding, renaming, or removing lifecycle states must update `docs/LIFECYCLE.md`. New states must be justified against the existing cross-system map — naming must be consistent with peer layers unless a documented reason exists for divergence. Adding a terminal status requires auditing ALL consumers of `isTerminal()`/`isActive()` across the codebase — hardcoded status lists are the number one source of silent lifecycle bugs. Use `isTerminal()`/`isActive()` in consumer code; never enumerate statuses explicitly unless the switch is semantically distinct per status (e.g. `ActionGateCompletionApplier` maps each status to a different gate event). See `docs/LIFECYCLE.md` for the authoritative cross-system state machine map.

**Systemic audit note for implementers:** Four review rounds surfaced 6 locations with hardcoded status enumeration across 4 modules. The implementation plan must include an IntelliJ find-references search on `WorkItemStatus` to catch any remaining sites not listed in this spec. Known sites:

| Location | Fixed by this spec | Module |
|---|---|---|
| WorkItemLifecycleAdapter status filter | Yes (9a — isTerminal()) | engine |
| HumanTaskRecoveryService.TERMINAL_STATUSES | Yes (9c — isTerminal()) | engine |
| FilterRegistryEngine.toFilterEvent() | Yes (Section 8 — explicit, semantically distinct) | runtime |
| MultiInstanceGroupPolicy.cancelRemainingChildren() | Yes (9e — isTerminal() + cancelFromSystem()) | runtime |
| ReportService (4 locations) | Yes (9f — isActive()/isTerminal()) | reports |
| ActionGateCompletionApplier | Yes (9d — explicit, semantically distinct per status) | engine |

### 13. docs/LIFECYCLE.md (casehub-parent)

New document — the authoritative cross-system lifecycle reference. This is the primary deliverable of #240 — the code changes implement it.

Contents:

1. **Contract** — this document is normative. Any repo adding lifecycle states must update it and ensure consistency and cohesiveness with the existing cross-system map.
2. **The six lifecycle layers** — complete state machine for each, with state diagrams.
3. **Cross-layer bridges** — WorkItem→PlanItem mapping, Commitment→WorkItem conceptual parallels, Workflow→PlanItem mapping.
4. **Naming coherence** — where terminology aligns (FAULTED at 4 layers, COMPLETED at all layers, CANCELLED at all layers) and where it diverges (DELEGATED: 3 meanings; REJECTED vs DECLINED; EXPIRED at WorkItem/Commitment but not PlanItem/Case).
5. **Industry spec alignment** — WS-HumanTask 1.1, OpenHumanTask, BPMN 2.0, CMMN 1.1 mapping tables.
6. **Known gaps** — PlanItem SUSPENDED, COMPENSATING/COMPENSATED (BPMN), CMMN ManualActivation.
7. **Design rationale** — why FAULTED not ERROR, why OBSOLETE exists only at WorkItem layer, why DELEGATED semantics intentionally diverge, why EXPIRED→FAULTED at PlanItem layer.

---

## Cross-Repo Deliverables

| Repo | Change | Priority |
|---|---|---|
| casehub-parent | Create docs/LIFECYCLE.md; update PLATFORM.md (capability ownership + lifecycle protocol) | High |
| casehub-work | Add FAULTED, OBSOLETE to WorkItemStatus; `fault()`, `faultFromSystem()`, `obsolete()`, `obsoleteFromSystem()`, `cancelFromSystem()`, `progress()` methods; REST endpoints; WorkEventType alignment (25 values); SLA_EXTENDED lifecycle event; FilterRegistryEngine.toFilterEvent() update; MultiInstanceGroupPolicy: isTerminal() + cancelFromSystem(); ReportService: replace hardcoded lists with isActive()/isTerminal(); MongoWorkItemDocument fields; WorkItemContextBuilder fields; WorkItemResponse/WithAuditResponse fields; Flyway V37 | High |
| casehub-engine | WorkItemLifecycleAdapter: replace explicit status filter with `isTerminal()`; PlanItemCompletionApplier: add FAULTED→markFaulted() + OBSOLETE→markCancelled(), fire PlanItemFaultedEvent for FAULTED and EXPIRED; HumanTaskRecoveryService: replace EnumSet with `isTerminal()`; ActionGateCompletionApplier: add FAULTED→handleExpired() + OBSOLETE→handleRejected(); fix adapter class javadoc; add PlanItemStatus.FAULTED javadoc note | High |
| casehub-work | File issue: MongoWorkItemDocument 13+ field sync gap (pre-existing) | Medium |
| casehub-work | WorkItemStatus.DELEGATED javadoc cross-reference | Medium |
| casehub-qhorus | CommitmentState.DELEGATED javadoc cross-reference | Medium |
| casehub-engine | PlanItemStatus.DELEGATED javadoc cross-reference | Medium |
| casehub-engine | File issue: PlanItemStatus.SUSPENDED gap | Low |

---

## Industry Spec Alignment — After This Design

### vs WS-HumanTask 1.1

| WS-HT State | casehub-work | Notes |
|---|---|---|
| CREATED | PENDING | ✓ collapsed with READY |
| READY | PENDING | ✓ |
| RESERVED | ASSIGNED | ✓ |
| IN_PROGRESS | IN_PROGRESS | ✓ |
| SUSPENDED | SUSPENDED | ✓ with priorStatus restoration |
| COMPLETED | COMPLETED | ✓ |
| FAILED | REJECTED | ✓ actor's deliberate decision |
| ERROR | FAULTED | ✓ platform name, same semantics |
| EXITED | CANCELLED | ✓ |
| OBSOLETE | OBSOLETE | ✓ |

### vs OpenHumanTask

| OHT State | casehub-work | Notes |
|---|---|---|
| created / ready | PENDING | ✓ |
| reserved | ASSIGNED | ✓ |
| running | IN_PROGRESS | ✓ |
| completed | COMPLETED | ✓ |
| obsolete | OBSOLETE | ✓ |
| failed | REJECTED | ✓ |
| error | FAULTED | ✓ |
| exited | CANCELLED | ✓ |
| *(no suspended)* | SUSPENDED | ✓ casehub is richer |

### What casehub has beyond the specs

| Feature | Assessment |
|---|---|
| EXPIRED | Deadline-passed explicit. No spec equivalent. |
| ESCALATED | All breach policies exhausted — terminal. No spec equivalent. |
| DELEGATED (pre-acceptance) | Richer than WS-HT nomination. |
| Named outcomes | Beyond any spec — enables outcome-based routing. |
| ExclusionPolicy | Conflict-of-interest. No spec equivalent. |
| priorStatus restoration | WS-HT implies but does not specify. |
| delegationChain | Audit provenance. No spec equivalent. |
| SLA breach policy framework | Chained/EscalateTo/Extend/Exhausted — beyond any spec. |
| Simple progress (percentComplete + statusNote) | Operational. Not in any spec. |

### Remaining spec gaps (intentional)

| Gap | Spec | Reason |
|---|---|---|
| COMPENSATING / COMPENSATED | BPMN 2.0 | Tracked separately: casehub-work#238 |
| ManualActivation (enabled/disabled) | CMMN 1.1 | casehub auto-activates by design |
| PlanItem SUSPENDED | CMMN 1.1 | Observability gap — filed as engine issue |
