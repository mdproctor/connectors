# Queue Integration for Work Annotations — Design Spec

**Date:** 2026-08-20
**Status:** Approved
**Issue:** casehubio/work#356 (follow-up)
**Decisions:** [decisions.md](decisions.md) D1-D2

## Motivation

The annotations module creates WorkItems but has no queue integration — annotation-created WorkItems are invisible to label-based queue views unless labels are added programmatically after creation. This gap means annotations and queues are parallel paths that don't talk to each other.

## Changes

### @HumanApproval — two new attributes (D1)

```java
@HumanApproval(
    title = "Approve expense",
    candidateGroups = "finance-team",
    types = {"finance", "expense"},
    labels = {"finance/approval", "intake/pending"})
```

| Attribute | Type | Default | Maps to |
|-----------|------|---------|---------|
| `types` | `String[]` | `{}` | `WorkItemCreateRequest.types` (`Set<String>`) |
| `labels` | `String[]` | `{}` | `WorkItemCreateRequest.labels` as `WorkItemLabelRequest(path, MANUAL, "work-annotations")` |

Types are the primary classification dimension for JEXL label rules (`types.contains('finance')`). Labels provide explicit queue routing at creation time.

### HumanApprovalDescriptor — two new fields

```java
public record HumanApprovalDescriptor(
    // ... existing 10 fields ...
    List<String> types,
    List<String> labels
) {}
```

### WorkAnnotationsProcessor — validation

- Label paths must contain no whitespace (build-time error)
- Label paths should follow hierarchical convention (`segment/segment`) — warning if flat single-segment

### Example: queue-integrated-annotated (D2)

Demonstrates both direct labels and label-rule inference in one module:

- `ExpenseApprovalService` — two `@HumanApproval` methods:
  - `approveExpense` — normal priority, explicit `labels = {"finance/approval"}`, `types = {"finance", "expense"}`
  - `approveUrgentExpense` — URGENT priority, same labels and types
- `UrgentLabelRule` — CDI `LabelRule` bean: `priority == 'URGENT'` → infer `sla/critical`
- Both methods get `finance/approval` (direct). Only urgent gets `sla/critical` (inferred).
- Two queue views: `finance/approval` (both), `sla/**` (urgent only)

### Documentation updates

- `CAPABILITY-MATRIX.md` — add `types`, `labels`, queue integration pattern rows
- `README.md` — add queue-integrated-annotated to module table
- Aggregator pom — add `queue-integrated-annotated` module

## References

- `api/src/main/java/io/casehub/work/api/WorkItemCreateRequest.java` — labels and types fields
- `api/src/main/java/io/casehub/work/api/WorkItemLabel.java` — label record (path, persistence, appliedBy)
- `runtime/src/main/java/io/casehub/work/runtime/filter/LabelRuleEngine.java` — rule evaluation
- `queues/src/main/java/io/casehub/work/queues/service/QueueMembershipService.java` — queue membership
- `queues-examples/` — existing queue scenario patterns
