# WorkerSelectionStrategy — Design Spec
**Date:** 2026-04-20
**Epic:** #100 — AI-Native WorkItem features / #102 — Workload-aware routing
**Status:** Approved for implementation

---

## Overview

Adds pluggable worker selection to WorkItems. Instead of all-candidates-claim-first, a
`WorkerSelectionStrategy` pre-assigns WorkItems at creation (and on release/delegation) to
the best available candidate. The default strategy (`least-loaded`) picks the candidateUser
with the fewest active WorkItems. Custom strategies are CDI `@Alternative` beans.

The selection SPI lives in a new `quarkus-work-api` module — pure Java, no Quarkus
runtime dependencies — so CaseHub and other systems can depend on the shared types without
pulling in the full WorkItems stack. This eliminates the naming divergence between
WorkItems' `WorkItemRouter` and CaseHub's `WorkerSelectionStrategy`.

---

## Cross-System Alignment

| Concept | Old WorkItems name | CaseHub name | Canonical name (in `-api`) |
|---|---|---|---|
| Routing SPI | `WorkItemRouter` | `WorkerSelectionStrategy` | **`WorkerSelectionStrategy`** |
| Candidate type | `CandidateWorker` | `HumanWorkerProfile` | **`WorkerCandidate`** |
| Group resolver | `GroupMemberResolver` | `WorkerRegistry` (implied) | **`WorkerRegistry`** |
| Routing outcome | `RoutingDecision` | *(not defined)* | **`AssignmentDecision`** |
| Trigger enum | `RoutingTrigger` | *(not defined)* | **`AssignmentTrigger`** |

### CaseHub coordination (after `-api` is published)

Once `quarkus-work-api` is available, CaseHub must:

1. Add `quarkus-work-api` as a **compile-scope** dependency in `casehub-engine` (no runtime dependency on the full WorkItems stack).
2. Align `HumanWorkerProfile` to `WorkerCandidate` — either implement it directly or provide a mapping method.
3. Implement `WorkerRegistry` using CaseHub's `WorkerRegistry`/`WorkerRegistrar` to resolve group names to `WorkerCandidate` lists (with capabilities and workload pre-populated).
4. Align `WorkerSelectionStrategy` (CaseHub) to the shared interface — or implement the shared interface directly in the strategies (`CapabilityMatchStrategy`, `LoadAwareStrategy`).
5. The `quarkus-work-casehub` integration adapter bridges remaining gaps between the two runtimes.

A GitHub issue will be filed in `casehubio/engine` after `quarkus-work-api` ships, with these steps as the acceptance criteria.

---

## Module Structure

```
quarkus-work-api   (new — pure Java, zero dependencies)
  spi/
    WorkerCandidate           record
    WorkerSelectionStrategy   interface
    AssignmentDecision        record
    AssignmentTrigger         enum
    WorkerRegistry            interface

quarkus-work (runtime — gains dep on quarkus-work-api)
  service/
    WorkItemAssignmentService  @ApplicationScoped — orchestrates resolution + selection
    ClaimFirstStrategy         @ApplicationScoped — no-op, pool stays open
    LeastLoadedStrategy        @ApplicationScoped — pre-assigns to min(activeWorkItemCount)
  config/
    WorkItemsConfig            gains routing().strategy() (default: "least-loaded")

casehub-engine (future — depends on api only, NOT workitems runtime)
  WorkerSelectionStrategy implements shared interface
  HumanWorkerProfile      aligns to WorkerCandidate

quarkus-work-casehub (future integration adapter)
  depends on: workitems runtime + casehub-engine
  bridges HumanWorkerProfile ↔ WorkerCandidate
```

**Dependency graph (acyclic):**
```
quarkus-work-api   ← pure Java, no runtime deps
       ↑                          ↑
quarkus-work            casehub-engine
  (runtime)                  (api dep only)
       ↑                          ↑
       └──── quarkus-work-casehub ────┘
```

---

## SPI Contracts

```java
// quarkus-work-api — pure Java

/** A potential assignee for a WorkItem. */
record WorkerCandidate(
    String id,
    Set<String> capabilities,
    int activeWorkItemCount) {

    /** Convenience factory — capabilities empty, workload unknown (0). */
    static WorkerCandidate of(final String id) {
        return new WorkerCandidate(id, Set.of(), 0);
    }
}

/** Immutable outcome of a selection — null field means "no change to this field". */
record AssignmentDecision(
    String assigneeId,
    String candidateGroups,
    String candidateUsers) {

    static AssignmentDecision noChange()                                      { ... }
    static AssignmentDecision assignTo(final String id)                       { ... }
    static AssignmentDecision narrowCandidates(final String groups,
                                               final String users)             { ... }
}

/** Lifecycle events that trigger worker (re-)selection. */
enum AssignmentTrigger { CREATED, RELEASED, DELEGATED }

/**
 * Pluggable worker selection SPI.
 * Implement as @ApplicationScoped @Alternative @Priority(1) to override the
 * built-in strategy selected by quarkus.work.routing.strategy.
 */
interface WorkerSelectionStrategy {
    AssignmentDecision select(WorkItem workItem, List<WorkerCandidate> candidates);

    /** Events that trigger this strategy. Default: all three. */
    default Set<AssignmentTrigger> triggers() {
        return Set.of(AssignmentTrigger.values());
    }
}

/**
 * Resolves a group name to its member WorkerCandidates.
 * Implement as @ApplicationScoped @Alternative @Priority(1) to connect LDAP,
 * Keycloak, CaseHub WorkerRegistry, or any other directory.
 * Default (built-in): returns empty list — groups remain claim-first.
 */
interface WorkerRegistry {
    List<WorkerCandidate> resolveGroup(String groupName);
}
```

**Config (WorkItems runtime):**
```properties
quarkus.work.routing.strategy=least-loaded   # default
# Options: least-loaded | claim-first
```

---

## Data Flow

```
WorkItemService.create(request)
  → builds WorkItem (candidateGroups, candidateUsers, requiredCapabilities)
  → WorkItemAssignmentService.assign(workItem, AssignmentTrigger.CREATED)

WorkItemAssignmentService.assign(workItem, trigger):
  1. Resolve active strategy:
     - CDI @Alternative WorkerSelectionStrategy present → use it
     - Else → read quarkus.work.routing.strategy → built-in
  2. strategy.triggers() does not contain trigger → return (skip)
  3. Resolve candidates:
     a. Parse workItem.candidateUsers (comma-sep) → List<WorkerCandidate>
        activeWorkItemCount populated from DB
     b. For each group in workItem.candidateGroups:
        WorkerRegistry.resolveGroup(group) → List<WorkerCandidate>
     c. Filter by workItem.requiredCapabilities (if non-blank)
     d. Merge into resolvedCandidates (deduped by id)
  4. strategy.select(workItem, resolvedCandidates) → AssignmentDecision
  5. Apply non-null fields of AssignmentDecision to workItem

WorkItemService.release() / delegate()
  → WorkItemAssignmentService.assign(workItem, RELEASED / DELEGATED)
  → same flow

ClaimFirstStrategy.select(workItem, candidates):
  → AssignmentDecision.noChange()

LeastLoadedStrategy.select(workItem, candidates):
  → candidates empty → AssignmentDecision.noChange()
  → else → min(activeWorkItemCount) → AssignmentDecision.assignTo(id)
  (activeWorkItemCount already populated in step 3 above)
```

**Default `WorkerRegistry`:** returns `List.of()` — groups remain claim-first until
an application registers a real implementation.

---

## Built-in Strategies

| Strategy | Config value | Behaviour |
|---|---|---|
| `ClaimFirstStrategy` | `claim-first` | No pre-assignment; whoever claims first wins |
| `LeastLoadedStrategy` | `least-loaded` (default) | Pre-assigns to candidate with fewest active (non-terminal) WorkItems |
| *(future)* `RoundRobinStrategy` | `round-robin` | Distributes sequentially — requires persistent cursor; separate issue |

---

## Testing Strategy

**Unit tests (no Quarkus boot) — `quarkus-work-api`:**
- `WorkerCandidateTest` — record construction, `of()` factory
- `AssignmentDecisionTest` — `noChange()`, `assignTo()`, `narrowCandidates()` factories

**Unit tests (no Quarkus boot) — runtime:**
- `ClaimFirstStrategyTest` — always returns `noChange()`
- `LeastLoadedStrategyTest` — min `activeWorkItemCount`, empty list, ties
- `WorkItemAssignmentServiceTest` — trigger filtering, candidateUsers parsing,
  `requiredCapabilities` pre-filter, `AssignmentDecision` applied to WorkItem

**Integration tests (`@QuarkusTest`) — runtime:**
- `WorkerSelectionStrategyIT`:
  - `POST /workitems` with `candidateUsers=[alice,bob]`, alice has 5 active, bob has 1
    → WorkItem pre-assigned to bob
  - No candidateUsers/Groups → `noChange()`, no pre-assignment
  - `PUT /workitems/{id}/release` → strategy re-fires (RELEASED trigger)
  - `PUT /workitems/{id}/delegate` → strategy re-fires (DELEGATED trigger)
  - `quarkus.work.routing.strategy=claim-first` → no pre-assignment
  - `@Alternative` custom strategy → overrides config

**E2E:**
- AI agent creates WorkItem with `candidateUsers=[alice,bob]`; alice has 5 active,
  bob has 1 → pre-assigned to bob, no claim needed
- Custom `WorkerRegistry` resolves `"finance-team"` → `[alice, bob]` →
  least-loaded routing applies to groups

---

## Child Issues to Create

| # | Title | Epic |
|---|---|---|
| TBD | `quarkus-work-api` module — shared SPI types | #100 / #102 |
| TBD | `WorkItemAssignmentService` + `ClaimFirstStrategy` + `LeastLoadedStrategy` | #100 / #102 |
| TBD | `WorkerRegistry` default + `WorkItemsConfig` routing sub-group | #102 |
| TBD | `round-robin` strategy — stateful cursor, cluster-safe | #102 |
| TBD | CaseHub alignment — file issue in `casehubio/engine` | cross-repo |

---

## Out of Scope (this iteration)

- `RoundRobinStrategy` — stateful cursor; separate issue filed, not implemented here
- LangChain4j-backed semantic strategy — future Epic #100 feature
- Notification on pre-assignment — future Epic #103
- `quarkus-work-casehub` integration adapter — blocked on CaseHub stability
