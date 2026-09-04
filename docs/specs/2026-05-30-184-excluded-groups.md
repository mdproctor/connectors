# Spec: excludedGroups — group-level conflict-of-interest exclusion (#184)

**Date:** 2026-05-30 (v4)
**Branch:** issue-235-sxs-sweep
**Scale:** S · Low

## Problem

`excludedUsers` (from #171) requires enumerating individual user IDs at template-definition
time. Groups are dynamic; the template author can't know who will be in "legal-team" when a
WorkItem is created.

## Semantics — Snapshot at Creation

Group membership is resolved **once at WorkItem instantiation** via `GroupMembershipProvider.membersOf()`.
Resolved actor IDs are merged into `excludedUsers`. No `excluded_groups` column exists on
`work_item`.

**Group exclusion is a template-level feature.** Direct `WorkItemService.create()` with no
template supports only `excludedUsers`. This can be lifted to request-level in a separate issue.

**Custom ExclusionPolicy interaction:** Snapshot expansion and a custom `ExclusionPolicy` are
orthogonal — both apply independently. A custom policy that already does dynamic group resolution
will run alongside the snapshot expansion at claim/delegate time. Template authors who use a
dynamic group-aware `ExclusionPolicy` do not need `excludedGroups` on the template; it is
additive for those who do.

**Partial failures in `membersOf()`:** `GroupMembershipProvider` has no partial-failure contract
— it returns an empty set for unknown or unreachable groups. An empty result silently skips
expansion. This is acceptable for the default `NoOpGroupMembershipProvider` (see §4), and
production implementations that need failure semantics should throw, allowing the expansion
to propagate the error.

## Design

### 1. V33 Flyway migration — template table only

```sql
-- Refs #184: group-level conflict-of-interest exclusion on WorkItemTemplate
ALTER TABLE work_item_template ADD COLUMN excluded_groups TEXT;
```

No column on `work_item`.

### 2. `WorkItemTemplate.excludedGroups` field

```java
/** Comma-separated group names. Expanded to actor IDs at WorkItem instantiation. */
@Column(name = "excluded_groups", columnDefinition = "TEXT")
public String excludedGroups;
```

### 3. `NoOpGroupMembershipProvider` @DefaultBean — runtime module

`casehub-platform` (which contains `MockGroupMembershipProvider`) is `scope: test` — not on
the production classpath. Without a runtime default, CDI startup fails with
`UnsatisfiedResolutionException` for any deployment that doesn't supply a
`GroupMembershipProvider`.

Add to the runtime module:

```java
@ApplicationScoped
@DefaultBean
public class NoOpGroupMembershipProvider implements GroupMembershipProvider {
    @Override
    public Set<GroupMember> membersOf(String groupName) {
        return Set.of();
    }
}
```

This mirrors the `CommaSeparatedExclusionPolicy` pattern: the no-op default makes group
exclusion safe to inject everywhere, and a real implementation (`@Alternative @Priority(1)`)
activates it when wired.

### 4. `TemplateExpander` — single expansion component

Three template-to-WorkItem paths each use `template.excludedUsers` directly:

| Path | Class | Lines |
|---|---|---|
| Standard | `WorkItemTemplateService.instantiate()` | calls `toCreateRequest()` |
| Multi-instance | `MultiInstanceSpawnService.createGroup()` | lines 148, 170 |
| Subprocess spawn | `WorkItemSpawnService.spawn()` | line 136 |

A single `TemplateExpander` component centralises the expansion logic and is injected into
all three:

```java
@ApplicationScoped
public class TemplateExpander {

    @Inject GroupMembershipProvider groupMembershipProvider;

    /**
     * Expands template.excludedGroups and merges with template.excludedUsers.
     * Returns the merged string (may be null). Does not mutate the template.
     */
    public String expandExcludedUsers(WorkItemTemplate template) {
        return mergeGroupsIntoExcludedUsers(
            template.excludedGroups, template.excludedUsers, groupMembershipProvider);
    }

    static String mergeGroupsIntoExcludedUsers(String excludedGroups,
                                                String excludedUsers,
                                                GroupMembershipProvider provider) {
        if (excludedGroups == null || excludedGroups.isBlank()) return excludedUsers;
        Set<String> ids = new LinkedHashSet<>();
        if (excludedUsers != null) {
            Arrays.stream(excludedUsers.split(",")).map(String::trim)
                  .filter(s -> !s.isEmpty()).forEach(ids::add);
        }
        for (String group : excludedGroups.split(",")) {
            String g = group.trim();
            if (!g.isEmpty()) {
                provider.membersOf(g).forEach(m -> ids.add(m.actorId()));
            }
        }
        return ids.isEmpty() ? null : String.join(",", ids);
    }
}
```

### 5. Call sites — inject `TemplateExpander`, call before building request

**`WorkItemTemplateService`** — inject `TemplateExpander`. In `instantiate()`, compute
expanded users before the multi-instance early return:

```java
@Transactional
public WorkItem instantiate(WorkItemTemplate template, String titleOverride,
                             String assigneeIdOverride, String createdBy,
                             String callerRef, String payloadOverride) {
    final String expandedExcludedUsers = templateExpander.expandExcludedUsers(template);

    if (template.instanceCount != null) {
        // payloadOverride not forwarded for multi-instance (logged elsewhere)
        return multiInstanceSpawnService.get()
            .createGroup(template, expandedExcludedUsers, titleOverride, createdBy, callerRef);
    }

    final WorkItemCreateRequest request =
        toCreateRequest(template, titleOverride, assigneeIdOverride, createdBy, callerRef, payloadOverride);
    final WorkItemCreateRequest withExpansion = expandedExcludedUsers != null
        && !expandedExcludedUsers.equals(request.excludedUsers)
        ? request.toBuilder().excludedUsers(expandedExcludedUsers).build()
        : request;
    // ... create + labels
}
```

`toCreateRequest()` static methods are unchanged.

**`MultiInstanceSpawnService`** — accept `expandedExcludedUsers` as a parameter in
`createGroup()`, passing it to child request builders instead of `template.excludedUsers`.
Signature change: `createGroup(WorkItemTemplate, String expandedExcludedUsers, String title, String createdBy, String callerRef)`.

**`WorkItemSpawnService`** — inject `TemplateExpander`. Replace `template.excludedUsers` with
`templateExpander.expandExcludedUsers(template)` at line 136.

### 6. `WorkItemService.clone()` — unchanged

`clone()` copies `.excludedUsers(source.excludedUsers)` — already correct. The source item's
`excludedUsers` contains the expanded group members from its original instantiation.

### 7. Audit trail for expansion

`WorkItemService.create()` currently calls `audit(saved.id, "CREATED", request.createdBy, null)`
with hardcoded `null` detail. `WorkItemTemplateService` has no access to `AuditEntryStore`.

**Mechanism (Option A):** Add `String auditDetail` to `WorkItemCreateRequest` (and its Builder).
`WorkItemService.create()` passes `request.auditDetail` instead of the hardcoded `null`:

```java
audit(saved.id, "CREATED", request.createdBy, request.auditDetail);
```

`WorkItemTemplateService.instantiate()` builds the detail when expansion occurs:

```java
String auditDetail = null;
if (template.excludedGroups != null && !template.excludedGroups.isBlank()) {
    int before = countIds(template.excludedUsers);
    int after  = countIds(expandedExcludedUsers);
    if (after > before) {
        auditDetail = "excludedGroups=[\"" + template.excludedGroups.trim()
                    + "\"] resolved to " + (after - before) + " actor(s)";
    }
}
// ... set auditDetail on request builder
```

`WorkItemSpawnService.spawn()` applies the same expansion note to each spawned child's
create request.

**Warning when expansion is a no-op:** `TemplateExpander.expandExcludedUsers()` emits
`LOG.warnf` when `template.excludedGroups` is non-null but the merged result adds no IDs
beyond `template.excludedUsers` — indicates either an empty group or a provider that isn't
returning members. Fires regardless of which provider is active.

**Known limitation:** `WorkItemSpawnService.spawn()` calls `templateExpander.expandExcludedUsers(template)` once per child in the spawn loop. Templates with `excludedGroups` backed by an HTTP-based `GroupMembershipProvider` will fire N network calls where 1 would suffice. Acceptable for the current deployment profile; a template-keyed cache within the loop can be added separately if it becomes a concern.

### 8. REST layer — WorkItemTemplate endpoints

Explicit changes needed:

- `CreateTemplateRequest` record: add `String excludedGroups`
- `UpdateTemplateRequest` record: add `String excludedGroups`
- `createTemplate()` handler: `t.excludedGroups = request.excludedGroups()`
- `updateTemplate()` handler: same
- Template response (GET): include `"excludedGroups"` entry so clients can verify what groups
  are configured (parallel to existing `excludedUsers` in the response)

`WorkItemResponse` is unchanged — no `excluded_groups` on `work_item`.

## Testing

- `TemplateExpanderTest` (pure unit): `mergeGroupsIntoExcludedUsers` static — null groups,
  blank groups, single group resolved, multiple groups resolved, deduplication with
  `excludedUsers`, all-empty groups returns null, overlap between groups and `excludedUsers`
  produces no duplicates
- `WorkItemTemplateServiceResolutionTest` (@QuarkusTest):
  - `casehub-platform` is already `scope: test` — `MockGroupMembershipProvider` on test classpath
  - New test: template with `excludedGroups = "legal-team"` → WorkItem has actor IDs in
    `excludedUsers`. Needs an `@Alternative @Priority(1)` test `GroupMembershipProvider` that
    maps "legal-team" → `{alice}`
  - New test: multi-instance template with `excludedGroups` → all child WorkItems have expanded
    `excludedUsers` (exercises `MultiInstanceSpawnService` path)
- `WorkItemSpawnServiceTest`: template with `excludedGroups` → spawned children have expanded
  `excludedUsers`
- `WorkItemServiceTest`: claim blocked when claimant was expanded into `excludedUsers` at creation
- `NoOpGroupMembershipProviderTest`: `membersOf("anything")` returns empty set
- V33 migration: runs cleanly in H2 test suite
