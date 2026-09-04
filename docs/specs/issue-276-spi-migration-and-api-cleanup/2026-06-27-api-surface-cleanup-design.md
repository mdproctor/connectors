# API Surface Cleanup — Event Hierarchy, SPI Migration, Template Consolidation

**Issues:** #276, #277, #278 (single branch)
**Order:** #278 → #276 → #277
**Cross-repo:** engine#585 (observer type migration, prerequisite for WorkLifecycleEvent deletion)

---

## #278 — Event Hierarchy Alignment

### Problem

Three competing event abstractions coexist:

1. `WorkLifecycleEvent` — abstract class in api/: `eventType()`, `context(): Map`, `source(): Object`
2. `WorkItemEvent` — interface in api/: `ref(): WorkItemRef` with typed defaults
3. `WorkItemLifecycleEvent` — final class in runtime/: extends #1, implements #2

`source()` returns `Object`, forcing all 5 callers to downcast to `WorkItemEntity`. Every caller is in a module that already depends on runtime — the `Object` return buys nothing. `NotificationPayload` references `WorkLifecycleEvent` in api/ solely to have a non-runtime event type to hold.

### Design

**Enrich `WorkItemEvent`** (api/) with event metadata:

```java
public interface WorkItemEvent {
    WorkItemRef ref();
    WorkEventType eventType();
    Instant occurredAt();
    String actor();
    String detail();

    // existing default methods from ref()
    default UUID workItemId() { return ref().id(); }
    default WorkItemStatus status() { return ref().status(); }
    default String callerRef() { return ref().callerRef(); }
    default String assigneeId() { return ref().assigneeId(); }
    default String resolution() { return ref().resolution(); }
    default String candidateGroups() { return ref().candidateGroups(); }
    default String outcome() { return ref().outcome(); }
    default String tenancyId() { return ref().tenancyId(); }
}
```

**Delete `WorkLifecycleEvent`** abstract class. Method disposition:

| Method | Destination |
|--------|-------------|
| `eventType()` | Promoted to `WorkItemEvent` (abstract) |
| `context()` | Runtime-only on `WorkItemLifecycleEvent` (not on any interface) |
| `source()` | Replaced by typed `workItem(): WorkItem` on `WorkItemLifecycleEvent` |

**Update `WorkItemLifecycleEvent`** (runtime/):
- Remove `extends WorkLifecycleEvent`
- Keep `implements WorkItemEvent`
- Replace `source(): Object` with `workItem(): WorkItem` — returns null for wire-reconstructed events (via `fromWire()` factory); non-null for local events (via `of()` factories)
- `context()` stays as a concrete method. When `workItem` is null (wire events), `WorkItemContextBuilder.toMap()` will NPE — this matches the existing contract documented on `fromWire()`: "Callers must not invoke workItem() or context() on wire-reconstructed events." No behavioral change needed; only update the Javadoc to reference `workItem()` instead of `source()`.

**Update `NotificationPayload`** (api/):
- Field type: `WorkLifecycleEvent event` → `WorkItemEvent event`
- Notification channel implementations that need entity access cast to `WorkItemLifecycleEvent` and call `workItem()`. This downcast is structurally unavoidable: `NotificationPayload` is in api/ and cannot reference `WorkItemLifecycleEvent` (which is in runtime/). The notification channels (Slack, Teams, HTTP) are in notifications/ which depends on runtime/, so the downcast is legitimate at that module boundary. The new cast is strictly narrower than the old one (`WorkItemEvent → WorkItemLifecycleEvent` vs `Object → WorkItem`).

**Observer migration:**

| Observer / Method | Module | Change |
|----------|--------|--------|
| `FilterRegistryEngine.onLifecycleEvent()` | runtime/ | `@Observes WorkLifecycleEvent` → `@Observes WorkItemLifecycleEvent` |
| `FilterRegistryEngine.processEvent()` | runtime/ | Parameter `WorkLifecycleEvent` → `WorkItemLifecycleEvent` (package-private, used by tests) |
| `FilterRegistryEngine.applyFilters()` | runtime/ | Parameter `WorkLifecycleEvent` → `WorkItemLifecycleEvent`; body changes `event.source()` → `event.workItem()` to pass typed `WorkItemEntity` to `FilterAction.apply()` |
| `EscalationSummaryObserver` | ai/ | `@Observes WorkLifecycleEvent` → `@Observes WorkItemLifecycleEvent`, use `event.workItem()` |
| `WorkCloudEventAdapter` | runtime/ | **Not affected** — already observes `WorkItemLifecycleEvent` and uses typed field accessors (`type()`, `sourceUri()`, `subject()`, `occurredAt()`, `tenancyId()`). Never calls `source()`. |

**FilterAction SPI:** parameter type changes from `apply(Object workUnit, ...)` to `apply(WorkItem workItem, ...)` in runtime/filter/. The `Object` parameter was stranded generality — every implementation (`ApplyLabelAction`, `SetPriorityAction`, `OverrideCandidateGroupsAction`) already casts to `WorkItemEntity`, there is no other domain type in the filter engine, and no external consumer implements it. Typing to `WorkItemEntity` eliminates three unchecked casts and adds compile-time safety. Intentionally NOT moved to api/spi/ — the `WorkItemEntity` parameter is a runtime type, which reinforces the placement in runtime/filter/.

**`WorkItemGroupLifecycleEvent`:** stays as-is. Tracked in #279 for future evaluation.

**Cross-repo:** engine#585 — `WorkItemLifecycleAdapter.onWorkItemLifecycle()` (line 76) observes `@ObservesAsync WorkLifecycleEvent event`. This must change to `@ObservesAsync WorkItemEvent event` before `WorkLifecycleEvent` can be deleted. The method body already casts to `WorkItemEvent` at line 77 (`if (!(event instanceof WorkItemEvent wie)) return;`), so the instanceof guard becomes redundant and can be removed. The `source()` → `workItem()` migration was completed in engine#579 (closed) — no `source()` calls remain in the engine adapter.

### Test impact

- **`WorkItemEventTest`** (api/ module): Uses `WorkItemEvent` as a functional interface via lambda (`() -> ref`). Adding 4 new abstract methods (`eventType()`, `occurredAt()`, `actor()`, `detail()`) makes it non-functional. Both tests (`defaultMethods_delegateToRef`, `defaultMethods_handleNullRefFields`) must be rewritten to use anonymous implementations providing all 5 abstract methods.

- **`WorkEventTypeTest.concreteEvent_implementsAbstractMethods`**: Creates an anonymous `WorkLifecycleEvent` providing 3 methods (`eventType`, `context`, `source`). After deletion, becomes an anonymous `WorkItemEvent` requiring 5 methods (`ref`, `eventType`, `occurredAt`, `actor`, `detail`). The `context()` and `source()` assertions are removed; `ref()` requires constructing a `WorkItemRef` record.

- **`FilterRegistryEngineTest`**: Structural rewrite. Currently creates anonymous `WorkLifecycleEvent` subclasses with arbitrary `source()` values (strings like `"WORK_UNIT_1"`, `"UNIT"`) — synthetic, not real WorkItem entities. After the change, `FilterRegistryEngine` observes `WorkItemLifecycleEvent` (final class, no anonymous subclass possible). The test helper `event()` must be rewritten to use `WorkItemLifecycleEvent.of()`, which requires constructing actual `WorkItemEntity` entities. The test's simplicity was enabled by the abstract class design; with the concrete class, every test needs a WorkItem fixture.

- **`WorkItemLifecycleEventTest`**: Update assertions for `workItem()` instead of `source()`.

- **`WorkItemLifecycleEventOutcomeTest`**: Update if it uses `source()`.

---

## #276 — SPI Migration to api/spi/

### Problem

14 consumer-facing SPI interfaces sit in `io.casehub.work.api` flat, mixed with value types, events, and exceptions. 2 SPIs from #275 are correctly in `api/spi/`. Inconsistency violates the `consumer-spi-placement` protocol.

### Design

Move 14 interfaces from `io.casehub.work.api` to `io.casehub.work.api.spi`:

1. BusinessCalendar
2. CapabilityRegistry
3. ClaimSlaPolicy
4. ExclusionPolicy
5. HolidayCalendar
6. InstanceAssignmentStrategy
7. NotificationChannel
8. SkillMatcher
9. SkillProfileProvider
10. SlaBreachPolicy
11. SpawnPort
12. WorkerRegistry
13. WorkerSelectionStrategy
14. WorkloadProvider

**Method:** IntelliJ `ide_move_file` for each — updates all imports across all modules.

**What stays in `io.casehub.work.api`:** value types (19 records/enums), events (WorkItemEvent, WorkItemGroupLifecycleEvent), exceptions (2), utilities (WorkCapabilities, WorkItemCallerRef, WorkItemCreateRequest).

**Intentionally NOT moved — runtime-internal SPIs:**
- `FilterAction` (runtime/filter/) — takes `WorkItem workItem` (typed in #278, was `Object`), runtime-internal, no external consumers. Stays in `io.casehub.work.runtime.filter` per the `consumer-spi-placement` protocol: its parameter type is a runtime class (`WorkItemEntity`), and the SPI exists to be implemented within the extension's own modules only.

---

## #277 — Template Consolidation

### Problem

Two template creation paths: `instantiate()` (3 positional-arg overloads, template-first) and `createFromTemplate(WorkItemCreateRequest)` (request-wins merge). Both call `workItemService.create()`. The REST endpoint uses the old path.

### Design

**Delete all `instantiate()` overloads** (3 methods) and **all `toCreateRequest()` static methods** (3 methods).

**Caller migration:**

| Caller | Module | Migration |
|--------|--------|-----------|
| `WorkItemTemplateResource.instantiate()` | runtime/ | Build `WorkItemCreateRequest` with templateId + overrides → `createFromTemplate()` |
| `WorkItemScheduleService.fireSchedule()` | runtime/ | Build request with templateId + createdBy → `createFromTemplate()` |
| `FormSchemaScenario` (2 calls) | examples/ | Build requests with templateId + overrides |
| 8 test classes (~17 call sites) | runtime/test | Build `WorkItemCreateRequest` instead of positional args |

Test classes: WorkItemInstancesResourceTest, MultiInstanceInboxTest, MultiInstanceCoordinatorTest, WorkItemGroupLifecycleEventTest, MultiInstanceCreateTest, WorkItemTemplateInstantiateTest, WorkItemTemplateServiceResolutionTest, MultiInstanceClaimGuardTest.

**Fix audit gap:** `createFromTemplate()` must add `auditDetail` for excludedGroups expansion. Insertion point: after `mergeRequestWithTemplate()` (line 205), before the multi-instance branch (line 207). When `expandedExcludedUsers != null` and differs from the template's `excludedUsers`, rebuild `merged` with `buildExpansionAuditDetail()`. This mirrors the `instantiate()` pattern at lines 159–167.

**Behavioral equivalence:** `mergeRequestWithTemplate()` (request-wins) is a superset of `toCreateRequest()` (template-first). Equivalence holds for the current callers because they don't set fields exclusive to the request-wins path (`tenancyId`, `formKey`, absolute deadlines, `confidenceScore`). For the fields they do override — `title`, `assigneeId`, `createdBy`, `callerRef`, `payload` — both merge functions produce identical results. New callers using `createFromTemplate()` gain access to the full field set.

---

## Platform Coherence Review

| Check | Result |
|-------|--------|
| consumer-spi-placement | Aligned — all consumer-facing SPIs move to api/spi/ |
| spi-evolution-default-methods | N/A — `WorkItemEvent` is a CDI event interface with a single runtime implementation (`WorkItemLifecycleEvent`), not a consumer-implemented SPI. Abstract methods are safe because no external class implements it. |
| work-event-type-enum-coverage | No new event types added |
| lifecycle-enum-classification-on-enum | No changes to lifecycle enums |
| bridge-module-spi-placement | N/A — no bridge SPIs affected |
| domain-event-not-workitem-lifecycle-for-triggers | N/A — observer changes preserve existing patterns |
| no-workarounds-fix-the-design | No wrappers or shims — `WorkLifecycleEvent` deleted, `source()` replaced |

## Out-of-scope (tracked)

- engine#585: migrate `WorkItemLifecycleAdapter` observer from `WorkLifecycleEvent` to `WorkItemEvent` (prerequisite for deleting `WorkLifecycleEvent`)
- #279: evaluate `WorkItemGroupLifecycleEvent` hierarchy integration
