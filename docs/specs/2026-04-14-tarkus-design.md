# Quarkus WorkItems — Design Specification v1.0

> *Human-scale WorkItem lifecycle management for the Quarkus Native AI Agent Ecosystem.*

---

## Glossary

Three systems in this ecosystem all use the word "task" to mean different things. This glossary is authoritative.

**Task** *(io.serverlessworkflow.api.types.Task — CNCF Serverless Workflow / Quarkus-Flow)*
A step within a workflow definition executed under machine control. Has lifecycle:
started → completed / failed / suspended / resumed. Executes in milliseconds to seconds.
No assignee, no delegation, no expiry. The engine decides when and how it runs.
Examples: call an HTTP endpoint, emit a CloudEvent, evaluate a JQ expression.

**Task** *(io.casehub.worker.Task — CaseHub)*
A unit of work within a CaseHub case, following CMMN terminology. Assigned to a worker
(human or agent) via capability matching. Has lifecycle: PENDING → ASSIGNED → RUNNING →
WAITING → COMPLETED / FAULTED / CANCELLED. The task model is unified — human workers
and agent workers are the same concept in CaseHub. When a CaseHub Task is routed to a
human worker, the `quarkus-work-casehub` adapter creates a corresponding WorkItems WorkItem.

**WorkItem** *(io.casehub.work.runtime.model.WorkItem — Quarkus WorkItems)*
A unit of work requiring human attention or judgment. Has lifecycle:
PENDING → ASSIGNED → IN_PROGRESS → COMPLETED / REJECTED / SUSPENDED / CANCELLED / EXPIRED → ESCALATED.
Persists minutes to days. Has assignee, candidate groups, priority, deadlines, delegation chain,
follow-up date, category, form reference, and full audit trail. Any system creates one —
Quarkus-Flow, CaseHub, Qhorus, or a plain REST call. A human resolves it.

**The one-sentence rule:** A `Task` is controlled by a machine. A `WorkItemEntity` waits for a human.

---

## What WorkItems Is

Quarkus WorkItems is a CaseHub platform module providing a **human task inbox** — a place for human workers to see what needs their attention, act on it, delegate it, and have it automatically escalate when it expires.

It is **not** a workflow engine, a case manager, or an agent communication mesh. It is the layer that sits between those systems and the human who needs to make decisions.

Any Quarkus application can embed WorkItems to get:
- A `WorkItemEntity` entity with full lifecycle management
- A REST inbox API that any UI (Claudony dashboard, custom frontend) can consume
- Expiry detection and pluggable escalation policies
- Delegation chains with full audit trail
- CloudEvent emission for integration with external systems

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│  Quarkus WorkItems (standalone)                                    │
│                                                                  │
│  REST API /workitems  ←── Claudony dashboard             │
│         │                        (or any UI)                    │
│         ▼                                                        │
│  WorkItemService                                                 │
│  ├── create / assign / claim / complete / reject / delegate     │
│  ├── ExpiryCleanupJob (@Scheduled)                               │
│  └── EscalationPolicy SPI                                       │
│         │                                                        │
│         ▼                                                        │
│  WorkItemStore SPI       ←── JpaWorkItemStore (default)        │
│  AuditEntryStore SPI     ←── JpaAuditEntryStore (default)      │
│         │                     (Panache, H2/PostgreSQL)          │
│         │                                                        │
│         └── InMemoryWorkItemStore (quarkus-work-testing)   │
└─────────────────────────────────────────────────────────────────┘

Substrate SPI layer (shared across domains):
  quarkus-work-api            →  Pure-Java SPIs: WorkerSelectionStrategy, WorkerRegistry,
                                 WorkloadProvider, EscalationPolicy, SkillProfile, SkillMatcher
  quarkus-work-core           →  Jandex library: WorkBroker, routing strategies, filter engine

Optional integration modules (separate artifacts):
  quarkus-work-ai        →  SemanticWorkerSelectionStrategy, EmbeddingSkillMatcher,
                                 WorkerSkillProfile entity + REST (built)
  quarkus-work-flow      →  TaskExecutorFactory SPI (Quarkus-Flow) (built)
  quarkus-work-ledger    →  Immutable ledger + Merkle hash chain + trust scores (built)
  quarkus-work-queues    →  Label-based queue filtering (built)
  quarkus-work-testing   →  InMemoryWorkItemStore for unit tests (built)
  quarkus-work-casehub   →  WorkerRegistry adapter (CaseHub) (future)
  quarkus-work-qhorus    →  MCP tools (Qhorus) (future)
  quarkus-work-mongodb   →  MongoDB-backed WorkItemStore (future)
  quarkus-work-redis     →  Redis-backed WorkItemStore (future)
```

---

## WorkItem Model

```java
// io.casehub.work.runtime.model.WorkItem
@Entity @Table(name = "work_item")
public class WorkItem extends PanacheEntityBase {
    @Id public UUID id;

    // Core description
    public String title;
    public String description;
    public String category;               // free-text classification: "finance", "legal", "security-review"
    public String formKey;                // UI form reference — how a frontend should render this item

    // Status
    @Enumerated(EnumType.STRING)
    public WorkItemStatus status;

    @Enumerated(EnumType.STRING)
    public WorkItemPriority priority;     // LOW|NORMAL|HIGH|CRITICAL

    // Assignment — work queue model
    public String assigneeId;            // who currently has it (actual owner)
    public String owner;                 // who is ultimately responsible (set on first delegation)
    public String candidateGroups;       // comma-separated groups who can claim (null = pre-assigned)
    public String candidateUsers;        // comma-separated individual users who can claim
    public String requiredCapabilities;  // comma-separated capability tags for routing
    public String createdBy;             // system or agent that created it

    // Delegation
    @Enumerated(EnumType.STRING)
    public DelegationState delegationState;  // null | PENDING | RESOLVED
    public String delegationChain;           // comma-separated prior assignees (audit trail)

    // Payload
    @Column(columnDefinition = "TEXT")
    public String payload;               // JSON context for the human to act on
    @Column(columnDefinition = "TEXT")
    public String resolution;            // JSON decision written by the human

    // Deadlines
    public Instant claimDeadline;        // must be claimed by — breach triggers claim escalation
    public Instant expiresAt;            // must be completed by — breach triggers completion escalation
    public Instant followUpDate;         // reminder date; surfaces in inbox, no escalation

    // Timestamps (aligned with quarkus-flow lifecycle event naming)
    public Instant createdAt;
    public Instant updatedAt;
    public Instant assignedAt;           // when claimed/assigned
    public Instant startedAt;            // when IN_PROGRESS began
    public Instant completedAt;          // when COMPLETED, REJECTED, CANCELLED, or EXPIRED
    public Instant suspendedAt;          // when SUSPENDED
}

// io.casehub.work.runtime.model.DelegationState
public enum DelegationState {
    PENDING,   // delegated to another; they are working it
    RESOLVED   // delegate completed; owner must confirm
}

// io.casehub.work.runtime.model.AuditEntry
@Entity @Table(name = "audit_entry")
public class AuditEntry extends PanacheEntityBase {
    @Id public UUID id;
    public UUID workItemId;
    public String event;                 // see AuditEvent enum values below
    public String actor;                 // who performed the action
    public String detail;                // JSON detail (e.g., previous assignee on delegation)
    public Instant occurredAt;
}

// Audit event values (aligned with quarkus-flow task event naming)
// CREATED | ASSIGNED | STARTED | COMPLETED | REJECTED | DELEGATED
// RELEASED | SUSPENDED | RESUMED | CANCELLED | EXPIRED | ESCALATED
```

**WorkItemStatus — aligned with quarkus-flow (`SUSPENDED`, `CANCELLED` are identical names):**

```
PENDING      — available for claiming; no assignee yet (or returned to pool after DELEGATED/RELEASED)
ASSIGNED     — claimed by a specific person; not yet actively working
IN_PROGRESS  — being actively worked (≈ RUNNING in quarkus-flow)
COMPLETED    — successfully resolved (identical to quarkus-flow)
REJECTED     — human declined or declared uncomplete-able (≈ FAULTED in quarkus-flow)
DELEGATED    — transitional: ownership transferred, pending new assignment
SUSPENDED    — on hold; will resume (identical to quarkus-flow SUSPENDED)
CANCELLED    — externally cancelled by system or admin (identical to quarkus-flow CANCELLED)
EXPIRED      — passed completion deadline without resolution; triggers escalation policy
ESCALATED    — escalation policy has fired post-expiry (terminal or awaiting admin action)
```

**Lifecycle transitions:**
```
PENDING → ASSIGNED (claim)
        → CANCELLED (admin)

ASSIGNED → IN_PROGRESS (start)
         → DELEGATED → PENDING (for new assignee)
         → RELEASED → PENDING (relinquish back to pool)
         → SUSPENDED
         → CANCELLED (admin)

IN_PROGRESS → COMPLETED
            → REJECTED
            → DELEGATED → PENDING
            → SUSPENDED
            → CANCELLED (admin)

SUSPENDED → ASSIGNED | IN_PROGRESS (resume to prior state)
          → CANCELLED (admin)

PENDING | ASSIGNED | IN_PROGRESS | SUSPENDED → EXPIRED (ExpiryCleanupJob)
EXPIRED → ESCALATED (EscalationPolicy SPI)
```

**DelegationState transitions:**
```
(null) → PENDING  (delegate operation)
PENDING → RESOLVED  (delegate completes; owner must confirm)
RESOLVED → (null)   (owner confirms; item re-enters normal lifecycle)
```

---

## REST API

Base path: `/workitems`

**Inbox and query:**

| Method | Path | Description |
|---|---|---|
| `GET` | `/inbox` | Human inbox: `?assignee=X&candidateGroup=Y&candidateUser=Z&status=PENDING&priority=HIGH&category=finance&followUp=true` |
| `GET` | `/` | List all WorkItems (admin) |
| `GET` | `/{id}` | Get full WorkItem with audit log |
| `POST` | `/` | Create a WorkItem |

**Lifecycle operations:**

| Method | Path | Transition | Notes |
|---|---|---|---|
| `PUT` | `/{id}/claim` | PENDING → ASSIGNED | Caller becomes assignee |
| `PUT` | `/{id}/start` | ASSIGNED → IN_PROGRESS | Begin work |
| `PUT` | `/{id}/complete` | IN_PROGRESS → COMPLETED | Body: resolution JSON |
| `PUT` | `/{id}/reject` | ASSIGNED\|IN_PROGRESS → REJECTED | Body: reason |
| `PUT` | `/{id}/delegate` | → DELEGATED → PENDING | `?to=assigneeId`; sets owner if first delegation |
| `PUT` | `/{id}/release` | ASSIGNED → PENDING | Relinquish back to candidate pool |
| `PUT` | `/{id}/suspend` | ASSIGNED\|IN_PROGRESS → SUSPENDED | Body: reason |
| `PUT` | `/{id}/resume` | SUSPENDED → prior state | Restores ASSIGNED or IN_PROGRESS |
| `PUT` | `/{id}/cancel` | any → CANCELLED | Admin only; body: reason |

**Inbox query parameters:**

| Parameter | Meaning |
|---|---|
| `assignee` | Items directly assigned to this user |
| `candidateGroup` | Items claimable by this group |
| `candidateUser` | Items this user was individually invited to claim |
| `status` | Filter by status (default: PENDING,ASSIGNED,IN_PROGRESS) |
| `priority` | Filter by priority |
| `category` | Filter by category |
| `followUp=true` | Items where followUpDate ≤ now (reminder view) |

The inbox query uses OR across `assignee`, `candidateGroup`, `candidateUser` — returning all items the caller can see or act on.

**Create request body:**
```json
{
  "title": "Review auth-refactor analysis",
  "description": "Alice's security analysis needs sign-off before proceeding",
  "assigneeId": "alice",
  "candidateGroups": "security-team,leads",
  "priority": "HIGH",
  "category": "security-review",
  "formKey": "workitems/security-approval/v1",
  "claimDeadline": "2026-04-15T09:00:00Z",
  "expiresAt": "2026-04-15T12:00:00Z",
  "followUpDate": "2026-04-15T08:00:00Z",
  "payload": { "analysisRef": "uuid-of-shared-data-artefact", "channelName": "auth-refactor" }
}
```

---

## Configuration

`WorkItemsConfig` — `@ConfigMapping(prefix = "quarkus.work")`:

| Property | Default | Meaning |
|---|---|---|
| `quarkus.work.default-expiry-hours` | 24 | Default completion deadline if no `expiresAt` is set |
| `quarkus.work.default-claim-hours` | 4 | Default claim deadline if no `claimDeadline` is set (0 = no claim deadline) |
| `quarkus.work.escalation-policy` | notify | What happens on completion expiry: `notify`, `reassign`, `auto-reject` |
| `quarkus.work.claim-escalation-policy` | notify | What happens on claim deadline breach: `notify`, `reassign` |
| `quarkus.work.cleanup.expiry-check-seconds` | 60 | How often the expiry/claim-deadline job runs |

Consuming app owns datasource config — none in the extension's `application.properties`.

---

## Storage SPI

Persistence is pluggable via two CDI interfaces in `io.casehub.work.runtime.repository`.
The SPI uses KV-native semantics — aligned with the CNCF Serverless Workflow SDK's persistence
module conventions — rather than SQL-shaped method-per-query patterns.

```java
public interface WorkItemStore {
    WorkItem put(WorkItem workItem);          // persist/update
    Optional<WorkItem> get(UUID id);          // retrieve by PK
    List<WorkItem> scan(WorkItemQuery query); // query via value object
    default List<WorkItem> scanAll() { return scan(WorkItemQuery.all()); }
}

// WorkItemQuery static factories capture common query intent:
WorkItemQuery.all()                                    // no filter
WorkItemQuery.inbox(assigneeId, candidateGroups, candidateUserId)  // OR across assignment
WorkItemQuery.expired(Instant now)                     // expiresAt <= now, active statuses
WorkItemQuery.claimExpired(Instant now)                // claimDeadline <= now, PENDING
WorkItemQuery.byLabelPattern("legal/**")               // label wildcard

public interface AuditEntryStore {
    void append(AuditEntry entry);
    List<AuditEntry> findByWorkItemId(UUID workItemId);
}
```

**Default implementations** (`runtime.repository.jpa`):
- `JpaWorkItemStore` — Panache-backed; `scan(WorkItemQuery)` builds dynamic JPQL; `@ApplicationScoped`
- `JpaAuditEntryStore` — Panache-backed; `@ApplicationScoped`

**Test implementation** (`quarkus-work-testing` module):
- `InMemoryWorkItemStore` — `ConcurrentHashMap`-backed; `scan()` uses stream-filter; no datasource required
- `InMemoryAuditEntryStore` — list-backed
- Both registered `@ApplicationScoped @Alternative @Priority(1)` — override the JPA defaults automatically

**Custom implementations** — any consuming app or module can provide an alternative:
```java
@ApplicationScoped @Alternative @Priority(1)
public class MyMongoWorkItemStore implements WorkItemStore { ... }
```

The JPA default requires a datasource. When using `quarkus-work-testing`, no datasource
or Flyway migration is needed — making pure unit tests (no `@QuarkusTest`) trivial.

---

## Escalation Policy SPI

Package: `io.casehub.work.api` (part of `quarkus-work-api`).

```java
public interface EscalationPolicy {
    /** Called on WorkItem expiry and claim-deadline breach. */
    void escalate(WorkLifecycleEvent event);
}
```

Check `event.eventType()` to distinguish `WorkEventType.EXPIRED` (expiresAt breach, fired by `ExpiryCleanupJob`) from `WorkEventType.CLAIM_EXPIRED` (claim deadline breach, fired by `ClaimDeadlineJob`). Call `event.source()` to access the `WorkItemEntity` entity.

Custom implementations register as CDI beans with `@ApplicationScoped @Alternative @Priority(1)`.

---

## CloudEvent Emission

WorkItems emits CloudEvents for all lifecycle transitions (via Quarkus Messaging):

| Event type | When |
|---|---|
| `io.casehub.work.workitem.created` | WorkItem created |
| `io.casehub.work.workitem.assigned` | WorkItem claimed or assigned |
| `io.casehub.work.workitem.completed` | WorkItem completed |
| `io.casehub.work.workitem.rejected` | WorkItem rejected |
| `io.casehub.work.workitem.delegated` | WorkItem delegated |
| `io.casehub.work.workitem.expired` | WorkItem expired |
| `io.casehub.work.workitem.escalated` | Escalation policy fired |

---

## Integration Modules

### quarkus-work-ai (built)

Adds AI-native routing via `SemanticWorkerSelectionStrategy` (`@Alternative @Priority(1)` — auto-activates on classpath). Provides `WorkerSkillProfile` entity + REST at `/worker-skill-profiles`, `EmbeddingSkillMatcher` (cosine similarity via LangChain4j), and three `SkillProfileProvider` implementations (DB-backed, capability tag join, resolution history). See the integration guide Section 9 for full configuration.

### quarkus-work-flow (built)
Implements `io.serverlessworkflow.impl.executors.TaskExecutorFactory` (Java SPI via
`META-INF/services`). When a Quarkus-Flow workflow step matches the WorkItems handler
(e.g., a custom `humanTask` type), the factory:
1. Creates a WorkItems WorkItem from the step definition
2. Suspends the WorkflowInstance (returns an incomplete CompletableFuture)
3. Completes the CompletableFuture with the WorkItem resolution when the human acts
4. Quarkus-Flow resumes the workflow with the resolution as output

### quarkus-work-casehub
Registers a worker with CaseHub's `WorkerRegistry` claiming `human:*` capability tasks.
When a CaseHub Task is claimed:
1. Creates a WorkItems WorkItem with the CaseHub task context as payload
2. On WorkItem completion, calls `WorkerRegistry.submitResult()` to advance the case

### quarkus-work-qhorus
Adds MCP tools backed by the WorkItems REST API:
- `request_approval(title, description, assignee, payload, timeout_s)` → creates WorkItem, returns `workItemId`
- `check_approval(work_item_id)` → returns status and resolution
- `wait_for_approval(work_item_id, timeout_s)` → polls until resolved or timeout

---

## Build Roadmap

| Phase | Status | What |
|---|---|---|
| **1 — Core data model** | ✅ Complete | Storage SPI interfaces, JPA defaults, InMemory impl (testing module), WorkItem + AuditEntry entities, Flyway V1, WorkItemService, WorkItemsConfig |
| **2 — REST API** | ✅ Complete | WorkItemResource — all CRUD + inbox + lifecycle endpoints |
| **3 — Lifecycle engine** | ✅ Complete | ExpiryCleanupJob, ClaimDeadlineJob, EscalationPolicy SPI |
| **4 — CDI lifecycle events** | ✅ Complete | WorkItemLifecycleEvent, WorkLifecycleEvent (base) via quarkus-work-api |
| **5 — Routing SPI substrate** | ✅ Complete | quarkus-work-api + quarkus-work-core: WorkBroker, strategies, filter engine |
| **6 — AI-native features** | ✅ Partial | SemanticWorkerSelectionStrategy, EmbeddingSkillMatcher, WorkerSkillProfile (Epic #100 in progress) |
| **7 — Quarkus-Flow integration** | ✅ Complete | `quarkus-work-flow` module, HumanTaskFlowBridge |
| **8 — Audit + ledger** | ✅ Complete | quarkus-work-ledger: Merkle hash chain, attestations, trust scores |
| **9 — Native image validation** | ✅ Complete | GraalVM native build, 19 integration tests, 0.084s native startup |
| **10 — CaseHub integration** | 🔲 Blocked | `quarkus-work-casehub` — awaiting CaseHub stability |
| **11 — Qhorus integration** | 🔲 Blocked | `quarkus-work-qhorus` — awaiting Qhorus MCP stability |

---

## Future Considerations

Deliberately deferred — not in scope for v1 but worth revisiting as the ecosystem matures.
Sources: WS-HumanTask (OASIS), BPMN 2.0, CMMN, Camunda 8, Flowable, Activiti.

### Multi-tenancy (`tenantId`)

Add `tenantId: String` to `WorkItemEntity` and `AuditEntry`. Required when a single Quarkus
application serves multiple tenants. All queries would include a tenant filter.
Quarkus multi-tenancy infrastructure would need to be wired in. Deferred because the
initial integration targets (Qhorus, CaseHub, Quarkus-Flow) are single-tenant.

### Subtasks (`parentWorkItemId`)

A `WorkItemEntity` could be a child of another `WorkItemEntity`. Parent completes when all children
complete. Modelled as a `parentWorkItemId: UUID` FK and a completion rollup in
`WorkItemService`. Deferred because it adds significant lifecycle complexity and no
integration target currently requires it. CaseHub integration may surface this need.

### Multi-approver patterns

Approval flows where multiple humans must act — all must approve (quorum), any one suffices,
or a sequential chain. Current model supports this by creating multiple WorkItems and
coordinating at the calling layer (e.g., Quarkus-Flow workflow). WorkItems could natively
model it with: `approvalType: enum (ANY_OF, ALL_OF, SEQUENTIAL)`, `requiredApprovals: int`,
and a parent/child relationship. Deferred — the calling-layer composition approach is
sufficient for v1.

### Discretionary (ad-hoc) tasks

CMMN concept: a task that is *optional* — the human decides whether to perform it at all.
Modelled as `discretionary: boolean`. A discretionary WorkItem appears in the "task planner"
view; the human explicitly adds it to their work if they choose to act on it. Potentially
useful for the Qhorus integration (AI agent suggests optional investigative steps). Deferred
pending real use cases.

### `skipable` flag + skip operation

WS-HumanTask allows tasks to be marked as skipable by a business administrator without the
task being performed. Requires `skipable: boolean` on `WorkItemEntity` and a `PUT /{id}/skip`
endpoint with admin authorisation. Useful for workflow un-blocking scenarios. Deferred.

### Information-request state (`WAITING_FOR_INFO`)

Enterprise BPM pattern: an assignee asks the task creator a clarifying question, putting the
task on hold until the creator responds. Modelled as an additional status `WAITING_FOR_INFO`
with a sub-thread of messages. Complex to implement well. Deferred — `SUSPENDED` with a
detail note in the audit log approximates this for v1.

### Routing strategies

Work-queue routing beyond candidateGroups: round-robin within a group, load-balanced by
current IN_PROGRESS count, organizational-hierarchy-based. Modelled as a routing strategy
SPI alongside the escalation policy SPI. Deferred — candidateGroups + manual claim covers
most cases.

### `taskDefinitionKey` / workflow linkage

When a WorkItem is created by a workflow engine (Quarkus-Flow, CaseHub), storing the source
workflow definition ID and step ID enables dashboards to show "which workflow generated this
task" and supports analytics across workflow runs. Fields: `sourceSystem: String`,
`sourceDefinitionId: String`, `sourceInstanceId: String`. Deferred to the integration
module phases (Phases 5–7) where the integration adapters can populate these fields.

---

*This specification emerged from design discussions during the quarkus-qhorus project (2026-04-14), which identified the need for a standalone human task layer across CaseHub, Quarkus-Flow, and Qhorus. The WorkItem model was informed by WS-HumanTask (OASIS), BPMN 2.0 UserTask, CMMN HumanTask, Camunda 8, Flowable, and Activiti.*
