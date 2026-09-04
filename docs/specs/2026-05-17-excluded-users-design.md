# Design Spec — Excluded Users / Conflict-of-Interest Enforcement (Issue #171)

**Epic:** epic-excluded-users
**Date:** 2026-05-17
**Status:** Approved

---

## Problem

OHT defines an `excludedOwners` role — users explicitly prevented from acting on a task.
The canonical use case: the person who initiated a task cannot also approve it.
Currently casehub-work has no conflict-of-interest enforcement; exclusion must be
implemented at the application layer where it is easily forgotten or inconsistently applied.

---

## Solution

### New SPI — `ExclusionPolicy` (casehub-work-api, Tier 1)

```java
public interface ExclusionPolicy {
    boolean isExcluded(String userId, String excludedUsers);
}
```

Pure Java, no JPA, no Quarkus. Placed in `casehub-work-api` consistent with
`WorkerSelectionStrategy`, `EscalationPolicy`, `ClaimSlaPolicy`.

**Default implementation** (`CommaSeparatedExclusionPolicy`, runtime, `@DefaultBean`):
Checks whether `userId` appears in the comma-separated `excludedUsers` string.
Returns `false` when `excludedUsers` is null or blank. Case-sensitive match,
whitespace-trimmed per token.

Future implementations can plug in LDAP group membership, role-based rules, or
time-window logic without touching the core (see #185).

---

### Data model

Three field additions, all `TEXT`, comma-separated, consistent with `candidateUsers`:

| Entity | Field | Description |
|--------|-------|-------------|
| `WorkItemTemplate` | `excludedUsers` | User IDs excluded from claiming instances of this template |
| `WorkItemEntity` | `excludedUsers` | Snapshotted from template at instantiation; set directly on direct creation |
| `WorkItemCreateRequest` | `excludedUsers` | String param — positioned after `permittedOutcomes`, before `inputDataSchema` |

**`SelectionContext`** gains `excludedUsers` String — carries the raw value so
external `WorkerSelectionStrategy` implementations can apply the policy.

**Flyway:** Two separate `ALTER TABLE` statements (H2 does not support
multi-column `ADD COLUMN` in one statement):
- `work_item_template ADD COLUMN excluded_users TEXT`
- `work_item ADD COLUMN excluded_users TEXT`

---

### Enforcement points (all five)

`ExclusionPolicy` is injected into `WorkItemService` and `WorkItemAssignmentService`.

| Point | Location | Behaviour |
|-------|----------|-----------|
| Auto-assignment | `WorkItemAssignmentService.resolveCandidates()` | Filter excluded users from candidate list before strategy runs |
| Manual claim | `WorkItemService.claim()` | Reject if claimant is excluded → `IllegalStateException` → 409 |
| Direct assignee at creation | `WorkItemService.create()` | Reject if `request.assigneeId()` is excluded → `IllegalArgumentException` → 400 |
| Delegation | `WorkItemService.delegate()` | Reject if `toAssigneeId` is excluded → `IllegalArgumentException` → 400 |
| External strategies | `SelectionContext.excludedUsers` | Carried in context; strategies read and apply via `ExclusionPolicy` or own logic |

`completeFromSystem()` is **not** checked — system completions bypass exclusion,
consistent with schema and outcome validation bypass.

**`resolveCandidates()` filter:**
```java
candidates.removeIf(c -> exclusionPolicy.isExcluded(c.id(), workItem.excludedUsers));
```
Applied after resolving `candidateUsers` + `candidateGroups`, before passing to strategy.

---

### REST API surface

**`CreateTemplateRequest`**: add `excludedUsers` String (nullable) to CRUD.

**`WorkItemResponse`** and **`WorkItemWithAuditResponse`**: add `excludedUsers` String.

**`WorkItemMapper`**: map the new field.

**`WorkItemContextBuilder.toMap()`**: add `excludedUsers` to JEXL context map — enables
filter rules that reference `excludedUsers` (e.g., label a WorkItem when its excluded
list is non-empty).

---

### Template instantiation

`WorkItemTemplateService.toCreateRequest()` passes `template.excludedUsers` as the
`excludedUsers` parameter. All three template-to-request paths (standard instantiation,
`WorkItemSpawnService`, `MultiInstanceSpawnService`) pass the field.

---

### Out-of-Scope

| Issue | Description |
|-------|-------------|
| #184 | `excludedGroups` — group-level exclusion complement |
| #185 | Custom `ExclusionPolicy` examples (LDAP, role-based, time-window) |
| #186 | Audit trail for blocked claim/delegate attempts |

---

## Testing

**Key scenarios:**

- Template with `excludedUsers` → instantiation snapshots onto WorkItem
- Excluded user attempts `claim()` → 409
- Non-excluded user claims → 200
- Excluded user is in `candidateUsers` → not auto-assigned (filtered in `resolveCandidates()`)
- `create()` with `assigneeId` = excluded user → 400
- `delegate()` to excluded user → 400
- Direct WorkItem creation (no template) with `excludedUsers` set → exclusion enforced on claim
- Null/blank `excludedUsers` → no exclusion (all users can claim)
- Custom `ExclusionPolicy` CDI bean → overrides default via `@Alternative @Priority(1)`
- `SelectionContext.excludedUsers` carries the value when strategy runs
