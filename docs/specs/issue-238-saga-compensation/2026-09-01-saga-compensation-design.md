# Design: Saga Compensation Support Across the CaseHub Platform

**Issue:** casehubio/work#238
**Date:** 2026-09-01
**Status:** Draft

---

## 1. Problem

CaseHub has no compensation model. When a case fails mid-execution or an operator triggers rollback, completed steps cannot be undone in a structured, auditable way. Current workarounds:

- Create a manual WorkItem to undo previous work (loses the causal link)
- CANCEL the case (loses the semantic distinction between stopping and compensating)
- Leave completed steps as-is (inconsistent domain state)

This is the Saga pattern gap. CaseHub is positioned for regulated, compliance-first deployments (EU AI Act Art.12, GDPR Art.17/22) where compensation is a compliance requirement — the ledger must capture both the original action and its reversal as immutable, causally-linked entries.

---

## 2. Goals

1. **Engine-level saga coordination** — cases can enter a COMPENSATING state that executes compensating bindings in reverse-completion order
2. **Worker-agnostic compensation** — any worker type (HTN, Workflow, Judgment) can declare and execute compensating actions via a `compensate:` block on its Binding
3. **Separate-entity compensation for WorkItems** — compensating WorkItems are new entities linked to originals; the terminal-state invariant is preserved
4. **Auditable compensation chain** — every compensation action creates a ledger entry with a CompensationSupplement linking it to the original via causedByEntryId
5. **Agent notification** — Qhorus agents are notified of compensation via a COMMAND with compensation context metadata
6. **YAML-first declaration** — compensating bindings are declarable in YAML case definitions
7. **Visualization** — design-time and runtime views of compensation graphs and saga execution

## 3. Non-Goals

- Choreographic (CDI-event) compensation for standalone casehub-work — future work
- Partial compensation (compensating step N without N-1...1) at the case level — operator single-item compensation is the escape valve
- Automatic retry of failed compensation — requires human intervention
- Cross-tenant compensation
- Time-windowed compensation (compensation expires after N days)

---

## 4. Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                    casehub-engine (coordinator)                  │
│                                                                 │
│  CaseStatus: ... COMPLETED ──► COMPENSATING ──► COMPENSATED    │
│                                     │          COMPENSATION_    │
│                                     │          FAULTED          │
│                                                                 │
│  CasePlanModel tracks completion order (EventLog)               │
│  Fires compensating bindings in reverse-completion order        │
│  Each Binding can declare: compensate: <binding-ref>            │
│                                                                 │
├─────────────────┬───────────────────┬───────────────────────────┤
│                 │                   │                           │
│  Judgment       │  Workflow         │  HTN / Extension          │
│  Worker         │  Worker           │  Worker                   │
│                 │                   │                           │
▼                 ▼                   ▼                           │
┌─────────────┐ ┌──────────────┐ ┌──────────────┐               │
│casehub-work │ │quarkus-flow  │ │other workers │               │
│             │ │              │ │              │               │
│ Original WI │ │ Original     │ │ Original     │               │
│ (COMPLETED) │ │ step (done)  │ │ step (done)  │               │
│      │      │ │      │       │ │      │       │               │
│ compensating│ │ compensating │ │ compensating │               │
│ WorkItem    │ │ workflow step│ │ action       │               │
│ (new entity)│ │              │ │              │               │
└──────┬──────┘ └──────┬───────┘ └──────┬───────┘               │
       │               │               │                        │
       ▼               ▼               ▼                        │
┌─────────────────────────────────────────────────────────────┐  │
│                    casehub-ledger                            │  │
│                                                             │  │
│  LedgerEntry + CompensationSupplement                       │  │
│  causedByEntryId → original entry                           │  │
│  Merkle hash chain continues forward                        │  │
└─────────────────────────────────────────────────────────────┘  │
                                                                 │
┌─────────────────────────┐  ┌──────────────────────────────┐   │
│    casehub-qhorus       │  │    casehub-connectors        │   │
│                         │  │                              │   │
│ COMMAND with compensation│  │ Email/Slack/Teams/Webhook    │   │
│ context on channel      │  │ notification of compensation │   │
│ New commitment for      │  │                              │   │
│ compensating work       │  │                              │   │
└─────────────────────────┘  └──────────────────────────────┘   │
```

---

## 5. casehub-engine — Saga Coordinator

### 5.1 CaseStatus Changes

```java
public enum CaseStatus {
    STARTING, RUNNING, WAITING, SUSPENDED,
    COMPLETED, FAULTED, CANCELLED,
    COMPENSATING,          // ← NEW: compensation in progress
    COMPENSATED,           // ← NEW: all compensating bindings completed
    COMPENSATION_FAULTED   // ← NEW: compensation attempted but failed
}
```

**State transitions:**

```
  COMPLETED ──compensate──► COMPENSATING
                                │
                    ┌───────────┼───────────┐
                    │           │           │
              all bindings   binding      (timeout /
              compensated    faults       unrecoverable)
                    │           │           │
                    ▼           ▼           ▼
              COMPENSATED  COMPENSATION_FAULTED
              (terminal)   (active, not terminal)
                                │
                           operator retry
                                │
                                ▼
                           COMPENSATING
```

- COMPENSATING is reachable only from COMPLETED or COMPENSATION_FAULTED (retry)
- COMPENSATED is terminal (`isTerminal()=true, isActive()=false`)
- COMPENSATING is active (`isTerminal()=false, isActive()=true`)
- COMPENSATION_FAULTED is active, not terminal (`isTerminal()=false, isActive()=true`) — analogous to SUSPENDED. Intervention required; can be retried (→ COMPENSATING). Terminal means done; COMPENSATION_FAULTED is not done.
- A COMPENSATING case cannot be SUSPENDED or CANCELLED — compensation must run to completion or fault
- Sub-cases: compensating a parent case propagates compensation to child cases that are COMPLETED (recursive, depth-first)
- Idempotency: concurrent compensation triggers on a COMPENSATING case are rejected (IllegalStateException). Re-compensation of a COMPENSATED case is rejected. Retry from COMPENSATION_FAULTED is allowed (re-enters COMPENSATING from the faulted step).

**Prerequisite — CaseStatus LIFECYCLE.md registration:** CaseStatus is currently unregistered in LIFECYCLE.md and lacks `isTerminal()`/`isActive()` methods. Per LIFECYCLE.md §4, adding compensation states requires: (1) add `isTerminal()` and `isActive()` to CaseStatus, (2) register in LIFECYCLE.md, (3) audit all CaseStatus consumers, (4) file cross-repo issues. This must be completed as a predecessor task before adding COMPENSATING/COMPENSATED/COMPENSATION_FAULTED.

**Design note — asymmetric model (D2 vs D7):** Cases use post-terminal transition (COMPLETED → COMPENSATING) while WorkItems use separate entities. This is intentional. A case IS the orchestration being compensated — its state reflects the saga lifecycle. A WorkItem IS work — compensation creates new work (a new entity). The asymmetry follows from the domain: cases are orchestrations (compensation is a phase), WorkItems are work units (compensation is new work).

### 5.2 Compensating Bindings

Each Binding in a CasePlanModel can declare an optional compensating binding:

```java
public class Binding {
    // ... existing fields ...
    
    /** Optional reference to the binding that compensates this one. */
    private String compensateRef;
}
```

When compensation is triggered, the engine:

1. Queries EventLog for all COMPLETED PlanItems whose Binding has a `compensateRef`
2. Builds a reverse dependency graph from the completion DAG (using Binding.produces/consumes/contextWrite relationships)
3. Executes compensating bindings in reverse topological order — dependent steps compensate sequentially (in reverse dependency order), independent steps compensate concurrently:
   a. Resolves the compensating Binding
   b. Checks if the underlying work was already compensated (e.g., operator action via D3) — if so, marks the compensating PlanItem COMPLETED and skips
   c. Fires the compensating binding (creates a new PlanItem with `isCompensation=true`)
   d. Waits for the compensating PlanItem to reach a terminal state
   e. If the compensating PlanItem COMPLETES → proceed to the next step(s) in topological order
   f. If the compensating PlanItem FAULTS/REJECTS → case enters COMPENSATION_FAULTED
4. After all compensating bindings complete → case enters COMPENSATED

For a linear chain (A→B→C), topological reverse is equivalent to strict reverse (C→B→A). For cases with independent parallel branches, independent steps compensate concurrently.

### 5.3 PlanItem Compensation Tracking

```java
public class PlanItem {
    // ... existing fields ...
    
    /** True if this PlanItem is executing a compensating binding. */
    private boolean compensation;
    
    /** The PlanItem ID of the original action being compensated. Null for non-compensation PlanItems. */
    private UUID compensatesItemId;
}
```

### 5.4 EventLog — New Event Types

```java
// In EventLog or equivalent event enum
COMPENSATION_STARTED,     // case entered COMPENSATING
COMPENSATION_COMPLETED,   // case entered COMPENSATED
COMPENSATION_FAULTED,     // case entered COMPENSATION_FAULTED
COMPENSATION_STEP_STARTED,  // individual compensating binding fired
COMPENSATION_STEP_COMPLETED // individual compensating binding completed
```

### 5.5 Compensation Trigger API

```java
public interface CaseCompensationService {
    
    /**
     * Trigger compensation for a case. Valid entry points:
     * - COMPLETED → COMPENSATING (initial compensation)
     * - COMPENSATION_FAULTED → COMPENSATING (retry from faulted step)
     *
     * @param caseId the case to compensate
     * @param triggeredBy actor who triggered compensation (operator ID or "system")
     * @param reason human-readable reason for compensation
     * @throws IllegalStateException if case is not COMPLETED or COMPENSATION_FAULTED
     */
    void compensate(UUID caseId, String triggeredBy, String reason);
}
```

### 5.6 YAML Schema — Compensating Bindings

```yaml
casePlan:
  bindings:
    - name: irb-review
      target:
        type: judgment
        title: "IRB Protocol Review"
        scope: "casehubio/clinical/irb-review"
      compensate: irb-review-reversal      # ← NEW: reference to compensating binding

    - name: irb-review-reversal
      target:
        type: judgment
        title: "Reverse IRB Protocol Approval"
        scope: "casehubio/clinical/irb-reversal"
      compensation: true                    # ← marks this as a compensation-only binding
                                            #    (not executed in normal forward flow)

    - name: data-export
      target:
        type: capability
        capability: exportService.export
      compensate: data-export-cleanup

    - name: data-export-cleanup
      target:
        type: capability
        capability: exportService.rollback
      compensation: true
```

**Note:** YAML examples use `type: judgment` (JudgmentTarget). `type: humanTask` (HumanTaskTarget) is `@Deprecated(forRemoval = true)` and not in the `BindingTarget` sealed permits list. The YAML parser may maintain backward compatibility for `type: humanTask` during the deprecation period, but new definitions should use `type: judgment`.

Key YAML elements:
- `compensate:` on any binding — references the compensating binding by name
- `compensation: true` — marks a binding as compensation-only (not executed in normal forward flow)
- Compensating bindings can target any worker type — a JudgmentTarget binding can be compensated by a capability binding and vice versa

**Dormant binding activation prevention:** Bindings marked `compensation: true` are excluded from PlanItem creation during forward execution. The CasePlanModel parser marks these bindings, and the engine's binding activation logic (blackboard evaluation) skips them — no PlanItem is ever created for a compensation-only binding during forward flow. During compensation, the compensation coordinator explicitly creates PlanItems for these bindings on demand. This means compensation PlanItems do not exist in any state during forward execution — there is no "dormant" status to manage.

**Validation rules (build-time):**
- `compensate:` must reference an existing binding name in the same casePlan — build error otherwise
- A binding with `compensation: true` must be referenced by at least one `compensate:` — warning if orphaned
- Circular compensation references are rejected (A compensates B compensates A)
- A binding cannot reference itself as its own compensating binding

---

## 6. casehub-work — Compensating WorkItems

### 6.1 WorkItem Entity Changes

```java
public class WorkItemEntity {
    // ... existing fields ...
    
    /**
     * Denormalized compensation status of this WorkItem.
     * NONE by default. Set to COMPENSATING when a compensating WorkItem is created,
     * and to COMPENSATED when the compensating WorkItem completes.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "compensation_status")
    public CompensationStatus compensationStatus = CompensationStatus.NONE;
    
    /**
     * FK to the original WorkItem that this WorkItem compensates.
     * Null for non-compensating WorkItems.
     */
    @Column(name = "compensates_work_item_id")
    public UUID compensatesWorkItemId;
}
```

```java
public enum CompensationStatus {
    /** No compensation activity. */
    NONE,
    /** A compensating WorkItem has been created and is in progress. */
    COMPENSATING,
    /** The compensating WorkItem has completed — this WorkItem's effects are reversed. */
    COMPENSATED
}
```

### 6.2 WorkItemService — New Methods

```java
/**
 * Create a compensating WorkItem for a completed WorkItem.
 * 
 * Sets the original's compensationStatus to COMPENSATING.
 * The new WorkItem has compensatesWorkItemId pointing to the original.
 * 
 * @param originalId the completed WorkItem to compensate
 * @param request the compensation WorkItem configuration
 * @param triggeredBy who triggered compensation
 * @param reason why compensation is needed
 * @return the new compensating WorkItem
 * @throws IllegalStateException if original is not COMPLETED
 * @throws IllegalStateException if original's compensationStatus is not NONE
 * @throws IllegalStateException if original is itself a compensating WorkItem (compensatesWorkItemId != null)
 */
public WorkItem compensate(UUID originalId, WorkItemCreateRequest request,
                           String triggeredBy, String reason);

/**
 * Mark the original WorkItem as COMPENSATED.
 * Called when the compensating WorkItem reaches COMPLETED.
 * 
 * @param originalId the original WorkItem
 */
void markCompensated(UUID originalId);
```

### 6.3 REST API

| Method | Path | Body | Purpose |
|---|---|---|---|
| `POST` | `/workitems/{id}/compensate` | `{ "title": "...", "candidateGroups": [...], "reason": "..." }` | Create compensating WorkItem for a completed original |
| `GET` | `/workitems/{id}/compensation-status` | — | Check compensation status of a WorkItem |

### 6.4 WorkEventType — New Values

```java
COMPENSATION_STARTED,    // compensating WorkItem created, original marked COMPENSATING
COMPENSATION_COMPLETED   // compensating WorkItem completed, original marked COMPENSATED
```

### 6.5 Compensation Lifecycle Observer

A new CDI observer watches for compensating WorkItem terminal events:

```java
@ApplicationScoped
public class CompensationLifecycleObserver {
    
    void onCompensatingWorkItemCompleted(@Observes WorkItemLifecycleEvent event) {
        if (event.workItem().compensatesWorkItemId != null
                && event.status() == WorkItemStatus.COMPLETED) {
            workItemService.markCompensated(event.workItem().compensatesWorkItemId);
        }
    }
}
```

### 6.6 Flyway Migration

```sql
-- V<next> — compensation support
ALTER TABLE work_item ADD COLUMN compensation_status VARCHAR(20) DEFAULT 'NONE';
ALTER TABLE work_item ADD COLUMN compensates_work_item_id UUID;
ALTER TABLE work_item ADD CONSTRAINT fk_compensates_work_item
    FOREIGN KEY (compensates_work_item_id) REFERENCES work_item(id);
CREATE INDEX idx_work_item_compensates ON work_item(compensates_work_item_id)
    WHERE compensates_work_item_id IS NOT NULL;
```

### 6.7 MongoWorkItemDocument

Add `compensationStatus` and `compensatesWorkItemId` fields with from/toDomain mappings.

### 6.8 Response DTOs

`WorkItemResponse` and `WorkItemWithAuditResponse` gain `compensationStatus` (String, nullable) and `compensatesWorkItemId` (UUID, nullable).

### 6.9 FilterRegistryEngine

`FilterRegistryEngine.toFilterEvent()` — no changes needed. Compensating WorkItems are regular WorkItems with their own lifecycle. The `compensatesWorkItemId` field is available for filter rule conditions (e.g., "if this is a compensating WorkItem, apply compensation-queue label").

---

## 7. casehub-work-engine-adapter — Bridge Changes

### 7.1 JudgmentCompensationHandler

New handler that implements the engine's compensation interface for Judgment bindings (the current binding target type — `HumanTaskTarget` is `@Deprecated(forRemoval = true)` and not in the `BindingTarget` sealed permits list; `JudgmentTarget` is the active replacement):

```java
@ApplicationScoped
public class JudgmentCompensationHandler implements CompensationBindingHandler {
    
    /**
     * Executes a compensating Judgment binding by creating a compensating WorkItem
     * linked to the original WorkItem that was created by the forward binding.
     */
    @Override
    public void executeCompensation(PlanItem compensatingItem, PlanItem originalItem,
                                     Binding compensatingBinding, String triggeredBy, String reason) {
        // 1. Find the original WorkItem via callerRef from the original PlanItem
        //    callerRef format: case:{caseId}/pi:{planItemId}
        // 2. Check original WorkItem's compensationStatus — if COMPENSATED, skip
        //    (already compensated by operator action per D3) and mark compensatingItem COMPLETED
        // 3. Build WorkItemCreateRequest from compensatingBinding's JudgmentTarget
        // 4. Create a compensating WorkItem via workItemService.compensate()
        // 5. Set callerRef on the compensating WorkItem to link back to compensatingItem
        //    — this reuses the existing WorkItemLifecycleAdapter bridge:
        //    when the compensating WorkItem completes/faults, the adapter fires
        //    PlanItemCompletedEvent/PlanItemFaultedEvent for the compensating PlanItem,
        //    which the engine's saga coordinator observes
    }
}
```

### 7.2 WorkItemLifecycleAdapter — Compensation Event Propagation

When a compensating WorkItem reaches a terminal state, the adapter must propagate the result back to the engine:

- Compensating WorkItem COMPLETED → compensating PlanItem COMPLETED → engine checks if all compensating bindings are done
- Compensating WorkItem FAULTED/REJECTED → compensating PlanItem FAULTED → case enters COMPENSATION_FAULTED

---

## 8. casehub-ledger — CompensationSupplement

### 8.1 New Supplement Class

```java
public class CompensationSupplement extends LedgerSupplement {
    
    /** The ledger entry ID of the original action being compensated. */
    public UUID originalEntryId;
    
    /** Human-readable reason for compensation. */
    public String compensationReason;
    
    /** Regulatory basis for compensation (e.g., "GDPR Art.17", "EU AI Act Art.12"). */
    public String regulatoryBasis;
    
    /** Whether this is automated or human-driven compensation. */
    public String compensationMode; // "automated" or "human-driven"
}
```

### 8.2 Usage Pattern

When a compensation action is recorded in the ledger:

```java
LedgerEntry compensationEntry = new WorkItemLedgerEntry(...);
compensationEntry.causedByEntryId = triggerEntry.id; // causal chain — immediate trigger
compensationEntry.attach(new CompensationSupplement(
    originalActionEntry.id, // originalEntryId — the action being reversed
    "Clinical trial withdrawn — IRB approval no longer valid",
    "GDPR Art.17",
    "human-driven"
));
```

**`causedByEntryId` vs `originalEntryId` — these fields serve different purposes and can point to different entries:**
- `causedByEntryId` traces the **causal chain** — what event triggered this entry. For engine-driven compensation, this points to the COMPENSATION_STEP_STARTED entry (the immediate trigger).
- `originalEntryId` (on CompensationSupplement) traces the **compensation relationship** — what original action is being reversed. This always points to the original work entry (e.g., "IRB Approved").

In the simple case (operator direct compensation), both point to the same entry. In the engine-driven case, the causal chain goes through intermediate entries (COMPENSATION_STARTED → STEP_STARTED → compensation work), while the compensation relationship skips directly to the original action.
```

### 8.3 Flyway Migration

```sql
-- New supplement table
CREATE TABLE ledger_compensation_supplement (
    entry_id UUID PRIMARY KEY REFERENCES ledger_entry(id),
    original_entry_id UUID NOT NULL REFERENCES ledger_entry(id),
    compensation_reason TEXT,
    regulatory_basis VARCHAR(100),
    compensation_mode VARCHAR(20) NOT NULL,
    tenancy_id VARCHAR(100) NOT NULL
);
CREATE INDEX idx_comp_supp_original ON ledger_compensation_supplement(original_entry_id);
```

### 8.4 Hash Chain Continuity

Compensation entries are part of the tamper-evident record. The Merkle Mountain Range continues forward — compensation entries are new leaves, not modifications to existing ones. The `supplementJson` field includes the CompensationSupplement in the canonical bytes computation.

---

## 9. casehub-qhorus — Agent Compensation Notification

### 9.1 No New Message Type — COMMAND with Compensation Context

No new MessageType value is added. Compensation notifications use the existing `COMMAND` speech act with compensation context metadata:

```java
MessageDispatch.builder()
    .type(MessageType.COMMAND)
    .content("Compensation required: reverse prior IRB approval")
    .artefactRef("compensation:" + originalCommitmentId)
    .build();
```

The `artefactRef` field carries the compensation context (`compensation:{commitmentId}`) so the agent discovers from context that this COMMAND is compensating prior work. This avoids extending the 11-value MessageType taxonomy for a distinction that is contextual, not normatively distinct from COMMAND.

### 9.2 Compensation Flow

When a case compensates and an agent was involved:

1. `QhorusCompensationAdapter` observes the engine's COMPENSATION_STEP_STARTED event
2. Identifies the Qhorus channel associated with the original PlanItem (via callerRef)
3. Posts a COMMAND message on the channel with compensation context in artefactRef
4. If the compensating binding is another agent task, a new commitment (OPEN) is created on the channel

The original commitment stays FULFILLED — that's a historical fact. The new commitment tracks the compensating work.

### 9.3 CommitmentState — No Changes

CommitmentState does not gain new values. The separate-entity model applies: original commitment (FULFILLED) + new commitment for compensating work (OPEN → standard lifecycle).

---

## 10. casehub-connectors — External Notification

### 10.1 Compensation Notification Pattern

Follows the existing `WorkItemSubscriptionBridge` → platform subscription engine → `casehub-connectors` delegation (note: `casehub-work-notifications` was deleted in work#315; notifications now flow through the platform subscription engine):

- Email/Slack/Teams notification to affected parties when compensation is triggered
- Webhook outbound to external systems that need to know about the reversal
- Notification includes: original WorkItem/case reference, compensation reason, regulatory basis

### 10.2 New Notification Events

```java
CASE_COMPENSATION_STARTED,     // case entered COMPENSATING
CASE_COMPENSATION_COMPLETED,   // all compensation done
CASE_COMPENSATION_FAULTED,     // compensation failed
WORKITEM_COMPENSATION_STARTED  // individual WorkItem being compensated
```

---

## 11. Visualization

### 11.1 Design-Time: Case Definition View

The YAML case definition with `compensate:` references creates a compensation graph. Visualization tools (built on existing YAML tooling from #371) render this as:

```
┌──────────────┐      compensate:      ┌──────────────────────┐
│ irb-review   │──────────────────────►│ irb-review-reversal  │
│ (judgment)   │                       │ (judgment)           │
│              │                       │ compensation: true   │
└──────────────┘                       └──────────────────────┘

┌──────────────┐      compensate:      ┌──────────────────────┐
│ data-export  │──────────────────────►│ data-export-cleanup  │
│ (capability) │                       │ (capability)         │
│              │                       │ compensation: true   │
└──────────────┘                       └──────────────────────┘
```

The visualizer shows:
- Forward-flow bindings (solid lines)
- Compensating bindings (dashed lines, reverse direction)
- Bindings without compensating actions (highlighted as gaps)
- Worker type for each binding (icon or color)

### 11.2 Runtime: Saga Execution Timeline

During case execution and compensation:

```
Time ──────────────────────────────────────────────────────────►

Forward execution:
  ┌────────┐   ┌────────┐   ┌────────┐   ┌────────┐
  │ Step A │──►│ Step B │──►│ Step C │──►│ Step D │ ✗ FAULTED
  │  ✓     │   │  ✓     │   │  ✓     │   │        │
  └────────┘   └────────┘   └────────┘   └────────┘
                                              │
                                    compensate triggered
                                              │
Compensation (reverse):                       ▼
                              ┌────────┐ ┌────────┐
                              │ Comp C │◄│ Comp B │◄── (Comp A pending)
                              │  ✓     │ │ ▶ IP   │
                              └────────┘ └────────┘
```

The runtime view shows:
- Forward execution timeline with completion status
- Compensation timeline (reverse direction)
- Current compensation progress (which step is active)
- Failed compensation steps highlighted

### 11.3 Ledger Causal Chain Graph

The ledger's `causedByEntryId` chain visualized as a directed graph:

```
Original action:                    Compensation:
┌───────────────┐                  ┌──────────────────────┐
│ Entry #42     │◄─causedBy───────│ Entry #98            │
│ IRB Approved  │                  │ IRB Approval Reversed│
│ EVENT         │                  │ EVENT                │
│               │                  │ + CompensationSupp   │
└───────────────┘                  └──────────────────────┘
```

---

## 12. Cross-Repo Event Flow — End-to-End Compensation Sequence

```
1. Operator/system triggers: CaseCompensationService.compensate(caseId)

2. Engine:
   a. Validates case is COMPLETED or COMPENSATION_FAULTED
   b. Transitions case to COMPENSATING
   c. Fires COMPENSATION_STARTED EventLog entry
   d. Queries EventLog for completed PlanItems (reverse order)
   e. For each completed PlanItem with compensateRef:
      - Creates compensating PlanItem (compensation=true)
      - Fires COMPENSATION_STEP_STARTED EventLog entry
      - Dispatches to worker based on binding target type

3. Worker execution (per worker type):
   - Judgment → casehub-work creates compensating WorkItem (new entity)
   - Workflow → quarkus-flow executes compensating workflow step
   - HTN/Extension → external system executes compensating action

4. casehub-work (for Judgment compensation):
   a. Finds original WorkItem via callerRef
   b. Creates compensating WorkItem (compensatesWorkItemId = original.id)
   c. Sets original.compensationStatus = COMPENSATING
   d. Fires COMPENSATION_STARTED WorkEventType
   e. Human/agent completes the compensating WorkItem
   f. CompensationLifecycleObserver sets original.compensationStatus = COMPENSATED
   g. Fires COMPENSATION_COMPLETED WorkEventType

5. casehub-ledger:
   a. Compensation action recorded as new LedgerEntry
   b. causedByEntryId → original entry
   c. CompensationSupplement attached (reason, regulatory basis)
   d. Hash chain continues forward

6. casehub-qhorus:
   a. COMMAND with compensation context (artefactRef) posted on agent's channel
   b. New commitment created for compensating work (if agent-executed)
   c. Original commitment stays FULFILLED

7. casehub-connectors:
   a. Email/Slack/Teams notification to affected parties
   b. Webhook to external systems

8. Engine (completion):
   a. Compensating PlanItem reaches terminal state
   b. If COMPLETED → proceed to next compensating step
   c. If FAULTED/REJECTED → case enters COMPENSATION_FAULTED
   d. After all steps → case enters COMPENSATED
   e. Fires COMPENSATION_COMPLETED EventLog entry
```

---

## 13. Implementation Sequence — Child Issue Decomposition

The implementation follows a bottom-up dependency order:

### Phase 1: Foundation (casehub-work)
1. **CompensationStatus enum + entity fields** — add compensationStatus, compensatesWorkItemId to WorkItemEntity + Flyway migration + MongoDB document
2. **WorkItemService.compensate() + markCompensated()** — service methods + CompensationLifecycleObserver
3. **REST API** — POST /workitems/{id}/compensate, GET /workitems/{id}/compensation-status
4. **WorkEventType** — COMPENSATION_STARTED, COMPENSATION_COMPLETED
5. **Response DTOs + WorkItemContextBuilder** — expose new fields

### Phase 2: Ledger
6. **CompensationSupplement** — new supplement class + JPA entity + Flyway migration
7. **WorkItemLedgerEntry integration** — attach CompensationSupplement on compensation events

### Phase 3: Engine
8. **CaseStatus** — add COMPENSATING, COMPENSATED, COMPENSATION_FAULTED
9. **Binding.compensateRef** — compensation binding declaration
10. **YAML schema** — compensate: and compensation: YAML elements
11. **CaseCompensationService** — saga coordinator (reverse-order, step-by-step)
12. **PlanItem compensation tracking** — compensation flag, compensatesItemId
13. **EventLog types** — COMPENSATION_STARTED/COMPLETED/FAULTED/STEP_STARTED/STEP_COMPLETED

### Phase 4: Bridge
14. **JudgmentCompensationHandler** — engine→work compensation bridge
15. **WorkItemLifecycleAdapter** — compensation event propagation back to engine

### Phase 5: Integration
16. **Qhorus** — QhorusCompensationAdapter (COMMAND with compensation context, no new MessageType)
17. **Connectors** — compensation notification events

### Phase 6: Visualization
18. **Design-time** — YAML compensation graph visualizer
19. **Runtime** — saga execution timeline view
20. **Ledger** — causal chain graph view

---

## 14. Open Questions (deferred to implementation)

1. **Cross-tenant compensation** — can a compensation trigger in tenant A affect a WorkItem in tenant B? (Likely no — tenant boundaries are hard boundaries)
2. **Compensation time limits** — is there a window after which a completed WorkItem can no longer be compensated? (Configurable per binding or per case definition?)
3. **Agent influence propagation** — if an AI agent's output influenced downstream steps, can compensation un-apply that influence? (Likely a human judgment call, not automated)
4. **CNCF Serverless Workflow alignment** — how does the compensate: block map to the CNCF SDK's compensation model? (Check when implementing the Workflow worker)

---

## 15. References

- casehub-work#238 — idea: saga compensation support (issue body)
- casehub-work#240 — lifecycle alignment spec (§13: COMPENSATING/COMPENSATED listed as gap)
- `WorkItemStatus.java` — current 12-value enum, isTerminal()/isActive()
- `CaseStatus.java` — current 7-value enum
- `CommitmentState.java` — current 7-value enum with terminal invariant
- `WorkItemService.java` — current lifecycle methods (927 lines)
- `WorkEventType.java` — current 25-value event vocabulary
- `LedgerEntry.java` — causedByEntryId, supplements model, hash chain
- `ComplianceSupplement.java` — pattern for CompensationSupplement
- BPMN 2.0 §10.4 — Compensation Events and Activities
- YAML frontends epic (#371) — YAML schema extension precedent
- yaml-core migration (#379) — variable resolution infrastructure
- Structured progress (#237) — compensation progress tracking (future integration)
