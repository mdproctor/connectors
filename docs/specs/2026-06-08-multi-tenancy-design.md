# Multi-Tenancy — tenancyId Column and Filtering for casehub-work

**Issue:** casehubio/work#256
**Date:** 2026-06-08
**Status:** Design approved — revision 6

---

## Problem

casehub-work has zero tenant scoping. All WorkItems and WorkItemTemplates are shared across tenants. The platform foundation (`CurrentPrincipal.tenancyId()`, `TenancyConstants`) is complete; casehub-engine and casehub-eidos have implemented tenant scoping; casehub-work has not.

## Approach

Application-managed tenancy filtering. Every entity gets a `tenancy_id` column. Every store implementation injects `CurrentPrincipal` and applies `tenancyId` unconditionally to every query. Polling schedulers are replaced with per-item Quartz timers that carry tenant context as job data. A `TenantContextRunner` utility establishes tenant context for async and background paths. SSE event streams are tenant-scoped at the stream level, not just at serialisation.

---

## 1. Entity Changes

Add `tenancy_id VARCHAR(255) NOT NULL` to every JPA entity:

**Runtime:** WorkItem, WorkItemTemplate, AuditEntry, WorkItemNote, WorkItemLink, WorkItemSpawnGroup, WorkItemSchedule, WorkItemRelation, RoutingCursor, LabelDefinition, LabelVocabulary, FilterRule.

**Optional modules:** WorkItemQueueState, QueueView, WorkItemFilter, FilterChain, WorkItemQueueMembership (queues); WorkItemIssueLink (issue-tracker); WorkItemNotificationRule (notifications); WorkerSkillProfile, EscalationSummary (ai).

**casehub-work-ledger:** `WorkItemLedgerEntry` extends `LedgerEntry` (casehub-ledger) via JOINED inheritance. `LedgerEntry` gains `tenancy_id` on the base `ledger_entry` table via casehub-ledger's V1000 migration — `WorkItemLedgerEntry` inherits it automatically. No separate `tenancy_id` column or migration is needed in `work_item_ledger_entry`. `WorkItemLedgerEntryRepository` and its JPA implementation must add tenant filtering to all query methods (`findByWorkItemId`, `findLatestByWorkItemId`, `findEarliestByWorkItemId`) — the `tenancy_id` column is already present on the joined base table.

**Collection table** `work_item_label`: no separate `tenancy_id` — owned by WorkItem via `@CollectionTable`, inherits parent's tenancy filter through JOINs.

**Constraint changes:**
- `WorkItemTemplate` unique constraint: `UNIQUE(name)` → `UNIQUE(name, tenancy_id)`
- `RoutingCursor` PK: `(pool_hash)` → `(pool_hash, tenancy_id)` — two tenants with identical candidate pools get independent cursor state

---

## 2. Store Layer — Tenancy Filtering

Every store implementation injects `CurrentPrincipal` and applies `tenancyId` unconditionally to every query. Per protocols `tenancy-repository-pattern` and `no-conditional-tenancy-filtering`.

### Cross-tenant access — separate types, not a flag

Cross-tenant access follows the engine's pattern (engine#405): separate `CrossTenant*` store interfaces, a `@CrossTenant` CDI qualifier, and a dedicated `SystemCurrentPrincipal`. Tenant-scoped stores always filter — they never check `isCrossTenantAdmin()` and never conditionally omit the tenant predicate.

**`@CrossTenant` qualifier** (`runtime/repository/`): a CDI `@Qualifier` annotation marking injection points that require cross-tenant data access. Convention-based marker — enforced by code review, not CDI machinery. Greppable: "where does cross-tenant access happen?" → `grep @CrossTenant`.

**Cross-tenant store interfaces** (`runtime/repository/`):

| Interface | Methods | Used by |
|-----------|---------|---------|
| `CrossTenantWorkItemStore` | `findActiveWithDeadlines()` — active items with non-null expiresAt or claimDeadline | Startup recovery scan |
| `CrossTenantWorkItemScheduleStore` | `findActive()` — active schedules | Startup recovery scan |
| `CrossTenantRoutingCursorStore` | `cleanupStale(Instant cutoff)` — delete cursors older than cutoff | RoutingCursorCleanupJob |

Each interface exposes only the methods the cross-tenant use case requires — no `put()`, no `delete()` (except GC), no `scanAll()`. The type system bounds the blast radius.

Each gets a JPA implementation that omits the tenant predicate. When #257 adds PostgreSQL RLS, these implementations switch to `SET LOCAL ROLE casehub_crosstenancy` — the same mechanism the engine uses — without changing any caller.

**`SystemCurrentPrincipal`** (`runtime/service/`): an `@ApplicationScoped` bean qualified with a `@WorkSystem` annotation (casehub-work's equivalent of engine's `@EngineSystem`). Implements `CurrentPrincipal` with `actorId = "system"`, `tenancyId = TenancyConstants.DEFAULT_TENANT_ID`, `isCrossTenantAdmin() = true`. Never `@DefaultBean` — never displaces `MockCurrentPrincipal`. Accessed only via `@WorkSystem` qualifier from `CrossTenantProducer`.

**`CrossTenantProducer`** (`runtime/service/`): produces `@CrossTenant`-qualified cross-tenant store beans. Validates at startup that `SystemCurrentPrincipal.isCrossTenantAdmin() == true` — a contract assertion that fails loudly if the system principal is misconfigured.

```java
@Produces @CrossTenant @ApplicationScoped
public CrossTenantWorkItemStore produceWorkItemStore() {
    if (!systemPrincipal.isCrossTenantAdmin()) {
        throw new IllegalStateException(
            "SystemCurrentPrincipal.isCrossTenantAdmin() must return true");
    }
    return crossTenantWorkItemStore;
}
```

**What `isCrossTenantAdmin()` is NOT used for:** tenant-scoped stores never read it. It exists on `CurrentPrincipal` for the producer's startup assertion and for future platform-level system-actor contracts. Normal store queries are unconditionally tenant-filtered — no flags, no conditions, no escape hatches.

### Insert vs update contract

- `put()` on insert (entity's tenancyId is null): stamp from `CurrentPrincipal.tenancyId()`
- `put()` on update (entity's tenancyId already set): preserve existing value, never overwrite
- Defence-in-depth: on child WorkItem spawn, assert parent's tenancyId matches `CurrentPrincipal.tenancyId()`

### Existing stores extended with tenant filtering

- `WorkItemStore` / `JpaWorkItemStore` — tenant predicate on `get()`, `scan()`, `scanAll()`, `findByCallerRef()`, `scanRoots()`, `countByParentAndAssignee()`; stamp on `put()`
- `AuditEntryStore` / `JpaAuditEntryStore` — tenant predicate on all queries
- `WorkItemNoteStore` / `JpaWorkItemNoteStore` — tenant predicate on all queries
- `RoutingCursorStore` / `JpaRoutingCursorStore` — tenant predicate on all queries (GC cleanup lives on `CrossTenantRoutingCursorStore`, not here)
- `IssueLinkStore` / `JpaIssueLinkStore` — tenant predicate on all queries
- `WorkItemLedgerEntryRepository` / `JpaWorkItemLedgerEntryRepository` — tenant predicate on `findByWorkItemId()`, `findLatestByWorkItemId()`, `findEarliestByWorkItemId()`

### New store SPIs

| Store | Replaces | Key methods |
|-------|----------|-------------|
| `WorkItemTemplateStore` | Static `WorkItemTemplate.find/list/delete` | `put`, `get`, `getByName`, `scanAll`, `delete` |
| `WorkItemRelationStore` | Static `WorkItemRelation.find*` | `put`, `findBySource`, `findByTarget`, `findBySourceAndType`, `delete` |
| `WorkItemSpawnGroupStore` | Static `WorkItemSpawnGroup.find*` | `put`, `get`, `findByParentId`, `findByParentAndKey`, `findMultiInstanceByParentId` |
| `WorkItemScheduleStore` | Static `WorkItemSchedule.find*` | `put`, `get`, `scanAll`, `delete` |
| `WorkItemLinkStore` | Static `WorkItemLink.find*` in `WorkItemResource.java:687,688,708` | `put`, `get`, `findByWorkItemId`, `findByWorkItemIdAndType`, `delete` |
| `LabelDefinitionStore` | Static `LabelDefinition.findByVocabularyId/findByPath` | `put`, `findByVocabularyId`, `findByPath`, `delete` |
| `LabelVocabularyStore` | Static `LabelVocabulary` calls | `put`, `get`, `scanAll`, `delete` |
| `FilterRuleStore` | Static `FilterRule.allEnabled` | `put`, `get`, `allEnabled`, `delete` |

Each gets JPA and InMemory implementations. MongoDB where the module exists.

### Optional module static Panache calls — exhaustive enumeration

Every static Panache call in optional modules must route through a tenant-aware store. The following calls were confirmed by audit:

**Queues module** (5 stores: `QueueViewStore`, `QueueMembershipStore`, `WorkItemFilterStore`, `FilterChainStore`, `QueueStateStore`):

| Call | File | Replacement |
|------|------|-------------|
| `QueueView.listAll()` | `FilterEvaluationObserver.java:107` | `QueueViewStore.scanAll()` |
| `QueueView.findById()` | `QueueResource.java:114,162` | `QueueViewStore.get()` |
| `QueueView.findById()` | `WorkItemQueueMetrics.java:49` | `QueueViewStore.get()` |
| `WorkItemQueueMembership.findByWorkItemId()` | `QueueMembershipTracker.java:85` | `QueueMembershipStore.findByWorkItemId()` |
| `WorkItemQueueMembership.deleteByWorkItemId()` | `QueueMembershipTracker.java:107` | `QueueMembershipStore.deleteByWorkItemId()` |
| `WorkItemQueueMembership.persist()` | `QueueMembershipTracker.java:111` | `QueueMembershipStore.put()` |
| `WorkItemFilter.findActive()` | `FilterEngineImpl.java:77,148` | `WorkItemFilterStore.findActive()` |
| `WorkItemFilter.listAll()` | `FilterResource.java:57` | `WorkItemFilterStore.scanAll()` |
| `WorkItemFilter.findById()` | `FilterResource.java:90,110` | `WorkItemFilterStore.get()` |
| `FilterChain.findOrCreateForFilter()` | `FilterEngineImpl.java:82,155` | `FilterChainStore.findOrCreateForFilter()` |
| `FilterChain.findByFilterId()` | `FilterEngineImpl.java:118` | `FilterChainStore.findByFilterId()` |
| `WorkItemQueueState.findByIdOptional()` | `WorkItemQueueState.java:36`, `QueueStateResource.java:107` | `QueueStateStore.get()` |
| `WorkItemQueueState.findOrCreate()` | `QueueStateResource.java:140` | `QueueStateStore.findOrCreate()` |

Note: `WorkItemFilter.serializeActions()` is a static utility method (JSON serialization), not data access — no store routing needed.

**Notifications module** (1 store: `NotificationRuleStore`):

| Call | File | Replacement |
|------|------|-------------|
| `WorkItemNotificationRule.findEnabledForEventType()` | `NotificationDispatcher.java:72` | `NotificationRuleStore.findEnabledForEventType()` |
| `WorkItemNotificationRule.list/findById/findAllEnabled` | `NotificationRuleResource.java:85,86,93,107,140` | `NotificationRuleStore` methods |

**AI module** (2 stores: `WorkerSkillProfileStore`, `EscalationSummaryStore`):

| Call | File | Replacement |
|------|------|-------------|
| `WorkerSkillProfile.findById()` | `WorkerProfileSkillProfileProvider.java:26`, `WorkerSkillProfileResource.java:44,75` | `WorkerSkillProfileStore.get()` |
| `WorkerSkillProfile.listAll()` | `WorkerSkillProfileResource.java:63` | `WorkerSkillProfileStore.scanAll()` |
| `WorkerSkillProfile.deleteById()` | `WorkerSkillProfileResource.java:92` | `WorkerSkillProfileStore.delete()` |
| `EscalationSummary.findByWorkItemId()` | `EscalationSummaryResource.java:36` | `EscalationSummaryStore.findByWorkItemId()` |

### Store count summary

| Module | New tenant-scoped stores | New cross-tenant stores |
|--------|--------------------------|-------------------------|
| runtime | 8 (TemplateStore, RelationStore, SpawnGroupStore, ScheduleStore, LabelDefinitionStore, LabelVocabularyStore, FilterRuleStore, LinkStore) | 3 (CrossTenantWorkItemStore, CrossTenantWorkItemScheduleStore, CrossTenantRoutingCursorStore) |
| queues | 5 (QueueViewStore, QueueMembershipStore, WorkItemFilterStore, FilterChainStore, QueueStateStore) | 0 |
| notifications | 1 (NotificationRuleStore) | 0 |
| ai | 2 (WorkerSkillProfileStore, EscalationSummaryStore) | 0 |
| **Total** | **16** | **3** |

Existing stores extended with tenant filtering: 6 (WorkItemStore, AuditEntryStore, WorkItemNoteStore, RoutingCursorStore, IssueLinkStore, WorkItemLedgerEntryRepository). Grand total: **25 store implementations** (16 new + 6 extended + 3 cross-tenant).

**InMemory stores (persistence-memory):** inject `CurrentPrincipal`, filter the backing collection on `tenancyId` in every read, stamp on every write.

**MongoDB stores:** add `tenancyId` to every MongoDB filter document. Existing MongoDB documents have no `tenancyId` field. A `@Startup` migration bean backfills all documents without `tenancyId` to `TenancyConstants.DEFAULT_TENANT_ID`. This runs once, idempotently — documents already bearing a `tenancyId` are skipped.

**ReportService:** hand-built EntityManager JPQL — injects `CurrentPrincipal` directly and adds `AND w.tenancyId = :tenancyId` to every query. Acceptable because ReportService is a data access class (builds and executes queries).

**Static Panache elimination:** after this work, zero static Panache calls remain in production code outside store implementations. Every entity access flows through a tenant-aware store.

---

## 3. SSE Event Broadcasting — Tenant-Scoped Streams

### Problem

`LocalWorkItemEventBroadcaster` broadcasts to a single `BroadcastProcessor` shared across all subscribers. The `stream()` method filters by `workItemId` and `type` only — there is no tenant dimension. A tenant B subscriber sees every tenant A event on the hot stream. The same problem exists in `LocalWorkItemQueueEventBroadcaster` and both Postgres broadcaster variants.

### Design

**WorkItemEventBroadcaster SPI:** add `tenancyId` as a required filter parameter:
```
Multi<WorkItemLifecycleEvent> stream(UUID workItemId, String type, String tenancyId);
```

All implementations (`LocalWorkItemEventBroadcaster`, `PostgresWorkItemEventBroadcaster`) filter the stream by `tenancyId`. The SSE REST endpoint reads `tenancyId` from `CurrentPrincipal.tenancyId()` and passes it to `stream()`. A null `tenancyId` parameter is never permitted — it is always supplied by the endpoint.

**WorkItemQueueEventBroadcaster SPI:** add `tenancyId` as a required filter parameter:
```
Multi<WorkItemQueueEvent> stream(UUID queueViewId, String tenancyId);
```

Same pattern: all implementations filter; the REST endpoint supplies the value.

**WorkItemQueueEvent record:** add `tenancyId` field. Set from the WorkItem's `tenancyId` at event creation time in `FilterEvaluationObserver`.

### Wire DTOs for Postgres broadcasters

**WorkItemEventPayload** (postgres-broadcaster): add `tenancyId` field. `from()` reads it from `event.tenancyId()`. `toEvent()` passes it to `WorkItemLifecycleEvent.fromWire()`. Without this, the receiving node cannot scope the reconstructed event or apply tenant filtering on the stream.

**WorkItemQueueEvent** is used directly as the wire DTO in `PostgresWorkItemQueueEventBroadcaster` (serialised via ObjectMapper). Adding `tenancyId` to the record covers both the CDI event and the wire format.

---

## 4. Scheduler Architecture — Polling to Per-Item Timers

Replace polling schedulers with per-item Quartz timers. Each timer carries `{workItemId, tenancyId}` (or `{scheduleId, tenancyId}`) as Quartz job data.

### Quartz job store

**JDBC store** (`quartz.store-type=jdbc-cmt`). Timers survive restarts without a recovery scan. Quartz handles cluster dedup natively via database row locking — two nodes cannot fire the same trigger. This eliminates the startup recovery scan for the happy path. The RAM store alternative would require a recovery scan on every restart and has no cluster dedup.

**Scale characteristic:** per-item timers mean N Quartz jobs for N active WorkItems with deadlines. A system with 50K active items has 50K+ triggers. This is within Quartz JDBC store's design envelope — triggers are indexed rows, not in-heap objects.

**Startup recovery:** with JDBC store, timers that fired while the application was down are handled by Quartz's misfire policy. Set `org.quartz.jobStore.misfireThreshold` to a reasonable value (e.g. 60s). Misfired triggers execute immediately on startup. A separate recovery scan is needed only if JDBC store state is lost (disaster recovery) — in that case, a `@Startup` bean injects `@CrossTenant CrossTenantWorkItemStore` and `@CrossTenant CrossTenantWorkItemScheduleStore` and runs a one-time scan:
- WorkItems: `crossTenantWorkItemStore.findActiveWithDeadlines()` — re-schedule timers for each, using the entity's `tenancyId` as Quartz job data.
- WorkItemSchedules: `crossTenantWorkItemScheduleStore.findActive()` — re-schedule Quartz triggers for each.

This is the one bounded cross-tenant operation — runs once at startup, not recurring. Uses `@CrossTenant` stores, not `TenantContextRunner`.

### ExpiryCleanupJob → ExpiryTimerJob

- `WorkItemService.create()` with `expiresAt` → schedule Quartz job for that instant
- `WorkItemService.extend()` → reschedule the job
- Terminal transitions → cancel the job
- Job fires → `TenantContextRunner.runInTenantContext(tenancyId, () -> expireItem(workItemId))`

### ClaimDeadlineJob → ClaimDeadlineTimerJob

- Same pattern: schedule when `claimDeadline` is set, cancel on claim/terminal.

### WorkItemScheduleService → ScheduleTimerJob

- `WorkItemSchedule` creation → schedule Quartz cron/interval trigger with `{scheduleId, tenancyId}`
- Job fires → `TenantContextRunner.runInTenantContext(tenancyId, () -> instantiateFromSchedule(scheduleId))`

### State transition × timer action matrix

Every state transition that touches `expiresAt` or `claimDeadline` needs a corresponding timer management call:

| Transition | Method | expiresAt timer | claimDeadline timer |
|-----------|--------|-----------------|---------------------|
| Create | `WorkItemService.create()` | SCHEDULE (from request or config default) | SCHEDULE (from request or config default, if > 0) |
| Claim | `WorkItemService.claim()` | — (unchanged) | CANCEL (item is claimed) |
| Start | `WorkItemService.start()` | — | — |
| Complete | `WorkItemService.complete()` | CANCEL | CANCEL |
| Reject | `WorkItemService.reject()` | CANCEL | CANCEL |
| Cancel | `WorkItemService.cancel()` | CANCEL | CANCEL |
| Extend | `WorkItemService.extend()` | RESCHEDULE (new expiresAt) | — |
| Delegate | `WorkItemService.delegate()` | — | CANCEL (delegatee has new acceptance window, not claim SLA) |
| Accept delegation | `WorkItemService.acceptDelegation()` | — | — |
| Decline delegation | `WorkItemService.declineDelegation()` | — | SCHEDULE (new claimDeadline from `ClaimSlaPolicy`) |
| Release | `WorkItemService.release()` | — | SCHEDULE (new claimDeadline from `ClaimSlaPolicy`) |
| Suspend | `WorkItemService.suspend()` | PAUSE (cancel timer, record remaining duration) | PAUSE |
| Resume | `WorkItemService.resume()` | RESUME (schedule with remaining duration from now) | RESUME |
| Expire (SLA Fail) | `ExpiryLifecycleService.executeFail()` | — (already fired) | CANCEL |
| Expire (SLA EscalateTo) | `ExpiryLifecycleService.executeEscalateTo()` | RESCHEDULE (new expiresAt window) | SCHEDULE (new claimDeadline) |
| Expire (SLA Extend) | `ExpiryLifecycleService.executeExtend()` | RESCHEDULE (now + extend.by()) | or RESCHEDULE (now + extend.by()) |
| Expire (Exhausted) | `ExpiryLifecycleService.executeExhausted()` | — (already fired) | CANCEL |
| Claim expired | `ExpiryLifecycleService.checkClaimDeadlines()` | — | — (already fired; policy may SCHEDULE a new one via EscalateTo) |
| Spawn child | `WorkItemSpawnService.spawn()` | SCHEDULE (if child has expiresAt) | SCHEDULE (if child has claimDeadline) |

**PAUSE/RESUME for suspend:** On suspend, cancel the timer and store the remaining duration on the entity (new field: `remainingExpirySeconds`, `remainingClaimSeconds` — transient or persisted, TBD during implementation). On resume, schedule a new timer for `now + remaining`.

### RoutingCursorCleanupJob

Remains as periodic GC. Cross-tenant bulk delete is correct for pure housekeeping — no business logic, no events, idempotent. The job injects `@CrossTenant CrossTenantRoutingCursorStore` and calls `cleanupStale(cutoff)`. The tenant-scoped `RoutingCursorStore` has no cleanup method — GC is exclusively a cross-tenant concern.

---

## 5. TenantContextRunner

`@ApplicationScoped` bean in `runtime/service/`. Establishes a CDI request context with a synthetic `CurrentPrincipal` carrying a specific tenancyId.

```
tenantContextRunner.runInTenantContext(tenancyId, () -> { ... });
```

Internally:
1. Activates a CDI `RequestContext` via `Arc.container().requestContext()`
2. Registers a `CurrentPrincipal` instance with the given tenancyId, `actorId = "system"`, and `isCrossTenantAdmin() = false`
3. Runs the work
4. Tears down the context

There is no `runInCrossTenantContext()` variant. Cross-tenant access is handled by `@CrossTenant` stores injected at the bean level (see §2), not by manipulating the principal. This keeps two concerns cleanly separated:
- **Per-tenant async work** → `TenantContextRunner.runInTenantContext()` (Quartz jobs, `@ObservesAsync` handlers)
- **Cross-tenant system work** → `@CrossTenant` stores (startup recovery, GC)

**Used by:**
- Quartz expiry/claim-deadline/schedule job handlers — each job carries tenancyId in its job data; the handler calls `runInTenantContext(tenancyId, () -> ...)` before processing
- `MultiInstanceCoordinator.onChildTerminal()` (`@ObservesAsync`) — reads tenancyId from event's WorkItem
- `NotificationDispatcher` — requires both: (a) tenant-scoped rule loading via `NotificationRuleStore.findEnabledForEventType()` (runs synchronously in the `AFTER_SUCCESS` observer where `CurrentPrincipal` is still available), and (b) `runInTenantContext()` wrapping for channel `send()` calls dispatched to virtual threads via `CompletableFuture.runAsync()` (which loses request context)
- Any future `@ObservesAsync` handler that needs tenant context

---

## 6. CDI Events

**WorkItemLifecycleEvent** — add `tenancyId` field. Factory methods read from `workItem.tenancyId`. `@JsonIgnore` on the field for client-facing SSE. Included in `fromWire()` for server-to-server distributed relay.

**WorkItemGroupLifecycleEvent** — add `tenancyId` field. Set from parent WorkItem's tenancyId at fire time.

**SlaBreachEvent** — add `tenancyId` field. With the timer architecture, the Quartz handler establishes tenant context before calling the expiry service, so the event is fired in the correct tenant context.

**WorkItemQueueEvent** — add `tenancyId` field (see §3).

**WorkLifecycleEvent** (abstract superclass in `casehub-work-api`): no tenancyId accessor. The API module is consumed by `casehub-work-core` which has no CurrentPrincipal dependency. The concrete `WorkItemLifecycleEvent` in runtime carries it.

---

## 7. REST API Surface

No changes. `tenancyId` never appears in request DTOs, response DTOs, JSON Schemas, or OpenAPI specs. Per protocol `PP-20260607-69eba2` (`tenancyid-server-side-only`).

Request/response types already exist separately from entities:
- Request POJOs (`WorkItemCreateRequest` etc.) — no tenancyId field
- Response POJOs (`WorkItemResponse`, `WorkItemWithAuditResponse`) — mapper does not map tenancyId out
- Entity (`WorkItemEntity`) — has tenancyId, internal only

---

## 8. Cross-Module SPI Boundary

**SpawnPort.spawn():** called by casehub-engine's work-adapter within the engine's tenant context. Flows through WorkItemSpawnService → WorkItemStore.put() → store stamps tenancyId from CurrentPrincipal. No SPI signature change needed.

**work-flow module (HumanTaskFlowBridge):** `requestApproval()` and `requestGroupApproval()` call `WorkItemService.create()` synchronously. The caller's request context propagates — `CurrentPrincipal.tenancyId()` is available if the Quarkus-Flow task function was invoked within a request context. `WorkItemFlowEventListener` is a synchronous `@Observes` observer — it inherits the caller's tenant context when resolving the `CompletableFuture`. The Uni returned by `requestApproval()` suspends waiting for the CompletableFuture; the WorkItem creation happens synchronously with tenant context; the completion event fires from a REST call (which has tenant context). No changes needed, but documented: **Quarkus-Flow callers must invoke `HumanTaskFlowBridge` within a tenant-scoped context.** If a flow task function runs on a background thread without request scope, the WorkItem will be created with the wrong (or default) tenancyId.

**Webhook resources (Jira, GitHub):** inbound with no CaseHub authentication. Webhook configuration must carry a tenancyId. Resource looks up config, establishes tenant context via TenantContextRunner, then processes. Out of scope for this issue — tracked as #258.

**CaseSignalSink:** referenced in PLATFORM.md and CLAUDE.md but does not exist as code. Removed from this spec. If/when CaseSignalSink is implemented, it will be called within a tenant context established by the Quartz timer handler (SLA escalation fires the signal).

---

## 9. Flyway Migrations

Each module's migration stays within its allocated V-number range.

| Migration | Module | Tables |
|-----------|--------|--------|
| V35 | runtime | work_item, work_item_template, audit_entry, work_item_note, work_item_link, work_item_spawn_group, work_item_schedule, work_item_relation, routing_cursor, label_definition, label_vocabulary, filter_rule |
| V2003 | queues | work_item_queue_state, queue_view, work_item_filter, filter_chain, work_item_queue_membership |
| V3001 | notifications | work_item_notification_rule |
| V4002 | ai | worker_skill_profile, escalation_summary |
| V5002 | issue-tracker | work_item_issue_link |

`work_item_ledger_entry` requires no migration — `tenancy_id` is inherited from `ledger_entry` (base table) via JOINED inheritance via casehub-ledger's V1000 (shipped with ledger#127, closed 2026-06-09).

Each migration: `ADD COLUMN tenancy_id VARCHAR(255) NOT NULL DEFAULT '278776f9-e1b0-46fb-9032-8bddebdcf9ce'`. Plus constraint and PK changes for WorkItemTemplate and RoutingCursor. Plus index on `tenancy_id` per table.

**Reports module:** no migration needed. ReportService queries WorkItem and AuditEntry tables directly via EntityManager JPQL — those tables are covered by V35.

---

## 10. Testing Strategy

### Test infrastructure

`FixedCurrentPrincipal` does not exist in the platform. `MockCurrentPrincipal` is config-bound (`@ConfigProperty`) with no programmatic `setTenancyId()` method.

**Create `MutableCurrentPrincipal`** in casehub-work's test scope (`runtime/src/test/java/`): a `@ApplicationScoped @Alternative @Priority(100)` bean that implements `CurrentPrincipal` with mutable `tenancyId`, `actorId`, and `crossTenantAdmin` fields. This displaces `MockCurrentPrincipal` in test contexts. Multi-tenant tests call `setTenancyId()` to switch context mid-test:

```java
mutablePrincipal.setTenancyId("tenant-a");
workItemStore.put(itemA);
mutablePrincipal.setTenancyId("tenant-b");
assertThat(workItemStore.get(itemA.id)).isEmpty(); // tenant isolation
```

Level 2 and Level 3 tests rely on `TenantContextRunner` establishing per-invocation context for async paths, not on `MutableCurrentPrincipal`. `MutableCurrentPrincipal` is for synchronous Level 1 store tests and Level 4 REST tests only.

### Level 1: Store isolation tests

Every store implementation (JPA, InMemory, MongoDB) gets multi-tenant tests:
- Create data as tenant A → switch to tenant B → assert B cannot see/modify/delete A's data
- Assert `put()` on update preserves existing tenancyId
- Assert cross-tenant stores (`@CrossTenant CrossTenantWorkItemStore`) see data across all tenants
- Assert tenant-scoped stores never return cross-tenant data regardless of principal configuration

### Level 2: Timer and async context propagation tests

- Create WorkItem with `expiresAt` in tenant A → Quartz job fires → expiry in tenant A's context → tenant B untouched
- Same for claim deadline timers
- Multi-instance group: `@ObservesAsync` MultiInstanceCoordinator processes in correct tenant context
- WorkItemSchedule timer: instantiated WorkItem belongs to correct tenant

### Level 3: SSE stream isolation tests

- Subscribe to SSE as tenant A → create WorkItem as tenant B → tenant A receives no events
- Subscribe to SSE as tenant A → create WorkItem as tenant A → tenant A receives the event
- Same for queue SSE streams

### Level 4: End-to-end integration tests

Two-tenant REST scenarios:
- Isolation: POST as tenant A, GET as tenant B → 404
- Lifecycle isolation: create/claim/complete as tenant A → tenant B audit empty
- Template isolation: same name in both tenants → both succeed, each sees only their own
- Spawn isolation: children visible only to parent's tenant
- Expiry isolation: both tenants' items expire independently with correct tenancyId on events
- Cross-tenant prevention: access tenant A's WorkItem ID as tenant B → 404 (not 403)
- Ledger isolation: WorkItemLedgerEntry visible only to its tenant

---

## 11. Platform Coherence — RLS Relationship

casehub-engine uses `TenantAwareRepository` with `SET LOCAL "casehub.tenancy_id"` and `RlsPolicyApplicator` for PostgreSQL Row-Level Security (in `casehub-engine/persistence-hibernate`). casehub-work uses blocking Hibernate ORM (not Reactive), so the `TenantAwareRepository` pattern does not directly port.

casehub-work implements application-level filtering only. This creates a platform divergence: in a shared deployment where both engine and work tables live in the same schema, engine tables have RLS enforced at the database level while work tables rely solely on application code.

**Path to convergence:** application-level filtering is the prerequisite for RLS — the `tenancy_id` column and the filtering logic must exist first. RLS policies will be added in #257 as a defence-in-depth layer on the same `tenancy_id` column. The blocking Hibernate equivalent of `TenantAwareRepository` (using `EntityManager.createNativeQuery("SET LOCAL ...")` within a `@Transactional` boundary) will be designed in that issue.

---

## 12. Protocols Referenced

| Protocol | Rule |
|----------|------|
| `tenancy-repository-pattern` | Filtering in data access layer, never at call sites |
| `no-conditional-tenancy-filtering` | Always on, never gated on deployment mode |
| `tenancyid-server-side-only` | Never in client-facing APIs (PP-20260607-69eba2) |
| `subcase-tenancyid-inherits-parent` | Children inherit parent's tenancyId |
| `spi-event-tenancyid-component-order` | tenancyId position in CDI event records |

---

## 13. Out of Scope (Separate Issues)

- **#257** — PostgreSQL RLS hardening — defence-in-depth layer; path to convergence with engine's `TenantAwareRepository` pattern (see §11)
- **#258** — Webhook tenant mapping configuration for issue-tracker module (Jira/GitHub inbound)
- **#259** — Update `tenancy-repository-pattern` protocol to acknowledge background/async paths that use TenantContextRunner instead of CurrentPrincipal directly
- Promotion of `TenantContextRunner` to `casehub-platform` — starts in casehub-work, promoted when other repos need it
