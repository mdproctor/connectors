# SLA Breach Policy — Design Spec

**Branch:** issue-212-sla-breach-policy  
**Issues:** casehubio/work#212, #213, #205, #208, #209, #210, casehubio/parent#41  
**Date:** 2026-05-22  
**Status:** Approved (two DevTown review rounds)

---

## Problem

`EscalationPolicy.escalate(WorkLifecycleEvent)` is a void SPI — it collapses the breach
decision and its execution into a single call. The expiry service can't control _what_ happens
(terminate vs escalate to a new group vs extend the deadline); it just fires and forgets.

`scanRoots(String userId, ...)` conflates `assigneeId` and `candidateUsers` into one parameter,
silently dropping `candidateUser` when both `assignee` and `candidateUser` are provided.

---

## Solution

### #213 — SlaBreachPolicy SPI in casehub-work-api

**pom change:** add `casehub-platform-api` compile dep to `casehub-work-api`.

**New types (io.casehub.work.api):**

```
BreachType           enum: CLAIM_EXPIRED | COMPLETION_EXPIRED
BreachedTask         record: taskId, callerRef, title, candidateGroups
SlaBreachContext     record: breachType, task, scope (Path), preferences
BreachDecision       sealed interface:
  Fail(String reason)
  EscalateTo(Set<String> groups, @Nullable Duration deadline)
    static to(String... groups)          → null deadline (use config default)
    EscalateTo withDeadline(Duration d)  → copy with deadline
    NOTE: deadline applies to COMPLETION_EXPIRED only;
          CLAIM_EXPIRED always uses ClaimSlaPolicy regardless.
  Extend(Duration by)
  Chained(BreachDecision primary, BreachDecision fallback)
    default thenOnBreach(BreachDecision fallback) → new Chained(this, fallback)
SlaBreachPolicy      interface: BreachDecision onBreach(SlaBreachContext)
  Javadoc: when scope is Path.root() and caller knows richer scope,
           resolve preferences independently via callerRef() — see engine#330.
```

**NoOpSlaBreachPolicy** — in runtime (not api), `@DefaultBean @ApplicationScoped`,
returns `Fail("no-sla-breach-policy-configured")`.

**Deprecations** — `EscalationPolicy`, `@ExpiryEscalation`, `@ClaimEscalation` all `@Deprecated`.

---

### #212 — Expiry service wiring + WorkItem.scope field

**V31 migration:**
```sql
ALTER TABLE work_item ADD COLUMN scope VARCHAR(255);
ALTER TABLE work_item_template ADD COLUMN scope VARCHAR(255);
```
Both nullable. `WorkItemCreateRequest` and `CreateTemplateRequest` gain optional `scope`.
`WorkItemService.instantiate()` copies `template.scope → item.scope`.
`WorkItemContextBuilder.toMap()` includes scope for JEXL.

**SlaBreachEvent** — record in `runtime/event/`: `(SlaBreachContext context, BreachDecision decision)`
where `decision` is the **leaf** that actually executed (not the top-level Chained wrapper).

**ExpiryLifecycleService changes:**
- Remove `@ExpiryEscalation EscalationPolicy` and `@ClaimEscalation EscalationPolicy`
- Add `SlaBreachPolicy slaBreachPolicy`
- Add `PreferenceProvider preferenceProvider`
- Add `Event<SlaBreachEvent> slaBreachEventBus`

**buildBreachContext:**
```java
Path scope = item.scope != null ? Path.parse(item.scope) : Path.root();
Preferences prefs = preferenceProvider.resolve(SettingsScope.of(scope));
BreachedTask task = new BreachedTask(item.id.toString(), item.callerRef,
        item.title, parseCandidateGroupsAsSet(item.candidateGroups));
return new SlaBreachContext(type, task, scope, prefs);
```

**executeBreachDecision — returns the leaf decision (not void):**

| Decision | COMPLETION_EXPIRED | CLAIM_EXPIRED |
|---|---|---|
| `Fail(reason)` | status=EXPIRED, completedAt=now, resolution=reason, audit "EXPIRED", fire EXPIRED event | same |
| `EscalateTo(∅)` | throw BreachExecutionFailed (private, caught by Chained only) | same |
| `EscalateTo(groups, deadline)` | candidateGroups=groups, assigneeId=null, status=PENDING, expiresAt=now+(deadline??config.defaultExpiryHours()), audit "ESCALATED", fire ESCALATED event, trigger WorkBroker | claimDeadline=computeNewClaimDeadline(), (deadline ignored), same mutations |
| `Extend(by)` | expiresAt=now+by, audit "SLA_EXTENDED", no status change | claimDeadline=now+by, same |
| `Chained(p, f)` | try executeBreachDecision(p); catch BreachExecutionFailed → executeBreachDecision(f); return executed leaf | same |

After each call:
```java
BreachDecision leaf = executeBreachDecision(item, decision, ctx, now);
slaBreachEventBus.fire(new SlaBreachEvent(ctx, leaf));
```

**checkClaimDeadlines:** accumulate unclaimed seconds + update `lastReturnedToPoolAt` BEFORE
calling policy (always, regardless of decision).

**Sequencing dependency:** `casehub-platform-api` must be installed locally with `Path.root()`
before this compiles. Platform commit `d8d8461` exists locally; user coordinates publish.

---

### #205 — scanRoots signature

**Old (removed):** `scanRoots(String userId, List<String> candidateGroups)`  
**New:** `scanRoots(String assignee, String candidateUser, List<String> candidateGroups)`

`assignee` → `assigneeId = :assignee`. `candidateUser` → `candidateUsers LIKE '%value%'`.
Independent OR predicates. Old conflation of both into one userId was a correctness bug.

`WorkItemResource.inbox()`: `workItemStore.scanRoots(assignee, candidateUser, candidateGroups)`.

---

### Mechanical (#208, #209, #210, parent#41)

- **#208** `queues/pom.xml`: `quarkus-junit5` → `quarkus-junit`
- **#209** `JqConditionEvaluatorTest`: add case where JQ returns String → `evaluate()` is false
- **#210** `JqConditionEvaluator`: `@Inject ObjectMapper` replacing static instance
- **parent#41** `PLATFORM.md`: add `casehub-platform-expression | casehub-work | queues | JQ evaluation` row

---

### Deferred issues

- **engine#330** — `HumanTaskTarget.scope` for scope propagation from engine to WorkItem
