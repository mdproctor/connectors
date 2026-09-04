# Design: Compensation Visualization APIs

**Issue:** casehubio/work#390
**Date:** 2026-09-04
**Status:** Draft
**Parent spec:** `2026-09-01-saga-compensation-design.md` §11

---

## 1. Problem

The saga compensation spec (§11) defines three visualization views but only at the visual-concept level. No API contract exists for consumers (claudony dashboard) to query compensation graph data, saga execution timelines, or ledger causal chains. The underlying data exists across Binding model, EventLog, PlanItem state, and ledger supplements — but no projection layer composes it into visualization-ready structures.

---

## 2. Approach

GraphQL-first in `engine-graphql`, following the existing pattern of `CaseQueryResolver` + `@Type`-annotated records. Computation logic for graph projection lives in `engine-api` (pure functions) or `engine-planning` (services needing PlanItemStore/EventLog).

Three views, three GraphQL additions:

| View | GraphQL surface | Data source |
|------|----------------|-------------|
| Design-time graph | `compensationGraph` field on `CaseDefinitionType` | `CaseDefinition.getBindings()` |
| Runtime timeline | `compensationTimeline(caseId)` query | PlanItemStore + EventLog |
| Ledger chain | `compensationChain(caseId)` query | CaseLedgerEntryRepository |

---

## 3. Design-Time: Compensation Graph (D20)

### 3.1 GraphQL Types

```java
@Type("CompensationGraph")
record CompensationGraphType(
    List<CompensationNodeType> nodes,
    List<CompensationEdgeType> edges,
    List<String> gaps) {}

@Type("CompensationNode")
record CompensationNodeType(
    String bindingName,
    String targetType,
    boolean compensationOnly) {}

@Type("CompensationEdge")
record CompensationEdgeType(
    String sourceBinding,
    String compensatingBinding) {}
```

### 3.2 Field on CaseDefinitionType

`CaseDefinitionType` gains a `compensationGraph` field. Computed by a static utility in `engine-api` — `CompensationGraphProjection.project(List<Binding>)`. Pure function, no I/O.

```java
@Type("CaseDefinitionResponse")
public record CaseDefinitionType(
    String namespace, String name, String version,
    String title, String summary, List<String> capabilities,
    CompensationGraphType compensationGraph) { ... }
```

### 3.3 Computation: CompensationGraphProjection

Location: `engine-api` (`io.casehub.api.model.CompensationGraphProjection`)

```java
public final class CompensationGraphProjection {

  public static CompensationGraphType project(List<Binding> bindings) {
    List<CompensationNodeType> nodes = new ArrayList<>();
    List<CompensationEdgeType> edges = new ArrayList<>();
    List<String> gaps = new ArrayList<>();

    for (Binding b : bindings) {
      nodes.add(new CompensationNodeType(
          b.getName(),
          targetTypeName(b.target()),
          b.isCompensation()));

      if (b.getCompensateRef() != null) {
        edges.add(new CompensationEdgeType(b.getName(), b.getCompensateRef()));
      } else if (!b.isCompensation()) {
        gaps.add(b.getName());
      }
    }
    return new CompensationGraphType(nodes, edges, gaps);
  }
}
```

**Gaps** = bindings that are not compensation-only and have no `compensateRef`. These are bindings whose effects cannot be reversed by the saga. The visualizer highlights them as coverage gaps.

### 3.4 Target Type Mapping

`targetTypeName()` maps `BindingTarget` sealed subtypes to display strings:
- `CapabilityTarget` → `"capability"`
- `JudgmentTarget` → `"judgment"`
- `SubCaseTarget` → `"sub-case"`
- `SignalTarget` → `"signal"`
- `ExtensionTarget` → `"extension"`

---

## 4. Runtime: Compensation Timeline (D21)

### 4.1 GraphQL Types

```java
@Type("CompensationTimeline")
record CompensationTimelineType(
    UUID caseId,
    String status,
    String triggeredBy,
    String reason,
    Instant compensationStartedAt,
    Instant compensationCompletedAt,
    List<TimelineStepType> forwardSteps,
    List<CompensationStepType> compensationSteps) {}

@Type("TimelineStep")
record TimelineStepType(
    String planItemId,
    String bindingName,
    String targetType,
    String status,
    Instant createdAt,
    Instant completedAt) {}

@Type("CompensationStep")
record CompensationStepType(
    String planItemId,
    String bindingName,
    String targetType,
    String status,
    Instant createdAt,
    Instant completedAt,
    String compensatesBinding,
    String compensatesItemId) {}
```

### 4.2 Query in CaseQueryResolver

```java
@Query
@Description("Compensation timeline for a case — forward steps and compensation steps")
public CompensationTimelineType compensationTimeline(UUID caseId) { ... }
```

### 4.3 Data Assembly

1. Load `CaseInstance` for case status
2. Query `PlanItemStore` for all plan items of the case
3. Partition into forward items (`!isCompensation()`) and compensation items (`isCompensation()`)
4. Query `EventLog` for `COMPENSATION_STARTED` event to get `triggeredBy` and `reason` from metadata
5. Query `EventLog` for `COMPENSATION_COMPLETED` or `COMPENSATION_FAULTED` for completion timestamp
6. For each compensation item: resolve `compensatesItemId` to the original item's `bindingName`
7. Order forward steps by `createdAt` ascending; compensation steps by `createdAt` ascending

The timeline assembly logic lives in a service class in `engine-graphql` (it depends on PlanItemStore and EventLogRepository, which are engine-internal SPIs — not suitable for `engine-api`).

### 4.4 Non-compensating cases

If the case has never entered COMPENSATING, the query returns `null` — there is no timeline to show.

---

## 5. Ledger: Compensation Chain (D22)

### 5.1 GraphQL Types

```java
@Type("CompensationChain")
record CompensationChainType(
    UUID caseId,
    List<CompensationLedgerEntryType> entries) {}

@Type("CompensationLedgerEntry")
record CompensationLedgerEntryType(
    UUID entryId,
    Instant timestamp,
    String eventType,
    String caseStatus,
    UUID causedByEntryId,
    UUID originalEntryId,
    String compensationReason,
    String regulatoryBasis,
    String compensationMode) {}
```

### 5.2 Query in CaseQueryResolver

```java
@Query
@Description("Ledger compensation chain — entries with CompensationSupplement for a case")
public CompensationChainType compensationChain(UUID caseId) { ... }
```

### 5.3 Data Assembly

1. Query `CaseLedgerEntryRepository.findByCaseId(caseId)`
2. Filter to entries that have a `CompensationSupplement` attached
3. Map each entry to `CompensationLedgerEntryType` with supplement fields inlined
4. Return ordered by `sequenceNumber` ascending

The `CaseLedgerEntryRepository` already provides `findByCaseId()`. Supplement filtering happens in-memory after loading (supplements are stored as JSON on the entry — `supplementJson` field). No new repository query needed.

### 5.4 Empty chains

If no compensation entries exist for the case, returns an empty `entries` list (not null).

---

## 6. Module Impact

| Module | Changes |
|--------|---------|
| `engine-api` | New `CompensationGraphProjection` utility class |
| `engine-graphql` | 3 new `@Type` records, 2 new queries, enriched `CaseDefinitionType` |
| `engine-graphql` (pom.xml) | Add `casehub-engine-ledger` dependency (for `CaseLedgerEntryRepository`) |

No changes to: engine-rest, engine-planning, engine-ledger, work repos, ledger repos, connectors, qhorus.

### 6.1 engine-graphql → engine-ledger dependency

The `compensationChain` query needs `CaseLedgerEntryRepository`. The engine-graphql module currently depends on `engine-api`, `engine-common`, and `engine-planning`. Adding `engine-ledger` is a new dependency.

This is appropriate: the graphql module is the presentation layer that composes data from multiple engine modules. The ledger dependency is read-only (query) — no write coupling.

---

## 7. PlanItemRecord Extension

`PlanItemRecord` (in `engine-common`) currently lacks `compensation` and `compensatesItemId` fields that `PlanItem` (in `engine-planning`) has. The timeline query needs these fields from `PlanItemStore`.

Two options:
1. Add `compensation` and `compensatesItemId` to `PlanItemRecord` — the record is the externalized form of PlanItem
2. Query PlanItem directly from BlackboardRegistry in the graphql module

Option 1 is correct — `PlanItemRecord` should reflect PlanItem's full state. The fields were added to PlanItem in #386 but the record wasn't updated. This is a gap fix.

---

## 8. Testing

- **CompensationGraphProjection**: Unit tests with various binding configurations (full coverage, partial gaps, compensation-only orphans, circular — all via Binding.builder())
- **CompensationTimeline query**: Unit test with mock PlanItemStore/EventLog providing compensation scenario data
- **CompensationChain query**: Unit test with mock CaseLedgerEntryRepository returning entries with/without CompensationSupplement

All tests in `engine-graphql/src/test/` and `engine-api/src/test/`.

---

## References

- `2026-09-01-saga-compensation-design.md` §11 — visual concept specification
- `Binding.java` (engine-api) — compensateRef, compensation fields, validateCompensationBindings()
- `CaseDefinitionType.java` (engine-graphql) — current GraphQL type to extend
- `CaseQueryResolver.java` (engine-graphql) — existing query resolver pattern
- `CaseCompensationServiceImpl.java` (engine-planning) — EventLog metadata format
- `PlanItem.java` (engine-planning) — compensation/compensatesItemId fields
- `PlanItemRecord.java` (engine-common) — externalized PlanItem record (needs extension)
- `CaseLedgerEntryRepository.java` (engine-ledger) — case-scoped ledger queries
- `CompensationSupplement.java` (ledger-api) — supplement fields
- casehubio/work#390 — issue
- D20, D21, D22 in decisions.md — design decisions
