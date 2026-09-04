# CrossSystemRef + Ledger Entry Back-Reference — casehubio/work#365, #366

**Date:** 2026-08-28
**Issues:** casehubio/work#365, casehubio/work#366
**Status:** Revised (post light design review)

---

## Problem

Two provenance gaps in the work→engine boundary:

1. **No inbound causedByEntryId (#365).** When `WorkItemLifecycleAdapter` observes a terminal WorkItem event and calls `PlanItemCompletionApplier`, no work-ledger entry ID flows back to the engine. The causal chain breaks: `engine SPAWN → work CREATED → work COMPLETED → engine [no back-reference]`.

2. **String-convention callerRef (#366).** Three independent parsing systems exist for the same concept — `CallerRef` (engine-adapter sealed interface), `QhorusRef` (qhorus record), and `CallerRefParser` (engine-side duplicate). Each new integration module requires a new string format and parser.

---

## Design

### Separation of concerns (revised after design review)

The original design bundled `ledgerEntryId` onto `CrossSystemRef` and placed all type variants in work-api. The design review identified three structural problems:

1. **Phantom field:** `CrossSystemRef.ledgerEntryId()` would always be null when decoded from a callerRef string (the string doesn't encode the ID). The ledger entry ID comes from a separate channel.
2. **Opacity violation:** Putting `PlanItemRef(caseId, planItemId)` in work-api means work-api knows about engine internals. ARC42STORIES §3 says work treats callerRef as opaque.
3. **Immutability concern:** Adding a mutable setter to `WorkItemLifecycleEvent` for ledger entry ID threading needs careful safety documentation.

**Revised approach:** Two independent, composable changes:
- **#365:** Thread `ledgerEntryId` via `WorkItemLifecycleEvent` (separate from callerRef)
- **#366:** Introduce `CrossSystemRef` as a non-sealed interface in work-api; keep engine-specific and qhorus-specific types in their respective modules

### Part 1: Ledger entry ID threading (#365)

#### WorkItemLifecycleEvent — new ledgerEntryId field

Add a `UUID ledgerEntryId` field to `WorkItemLifecycleEvent` with a package-private setter. The field starts as null and is set by `LedgerEventCapture` after the ledger entry is persisted.

```
WorkItemService fires WorkItemLifecycleEvent (ledgerEntryId = null)
  │
  ├─ @Observes: LedgerEventCapture (sync, same transaction)
  │    ├─ writes WorkItemLedgerEntry (gets entry.id)
  │    └─ event.setLedgerEntryId(entry.id)
  │
  ├─ @Observes: [other sync observers — do not read ledgerEntryId]
  │
  └─ @ObservesAsync: WorkItemLifecycleAdapter (async, runs AFTER all sync complete)
       ├─ reads event.ledgerEntryId()  ← guaranteed populated (or null if no ledger)
       ├─ CallerRef.parse(event.callerRef())
       └─ applier.apply(caseId, planItemId, status, ref, ledgerEntryId)
```

**CDI ordering safety:** `@Observes` (synchronous) observers complete before any `@ObservesAsync` observer fires. `LedgerEventCapture` is the sole writer of `ledgerEntryId`. No other sync observer reads or writes this field. The async observers see the final value. This is the CDI-specified event lifecycle — not an implementation-dependent ordering.

**Package-private setter:** The setter is `void setLedgerEntryId(UUID)` with package-private or module-internal visibility. Only `LedgerEventCapture` (in the ledger module) calls it. To enforce this, `LedgerEventCapture` accesses the setter via an SPI interface:

```java
// work-api
public interface LedgerEntryIdSetter {
    void setLedgerEntryId(WorkItemLifecycleEvent event, UUID ledgerEntryId);
}
```

`WorkItemLifecycleEvent` provides a static `LedgerEntryIdSetter` instance that accesses the private field. This keeps the event's public API immutable while allowing the ledger module to set the ID.

#### Gate path threading

Both the PlanItem path and the gate path thread `ledgerEntryId`:

```
Terminal WorkItemEvent
  ├─ PlanItemRef → applier.apply(caseId, planItemId, status, ref, ledgerEntryId)
  └─ GateRef    → gateApplier.apply(gateRef, status, ref, tenancyId, ledgerEntryId)
```

`ActionGateCompletionApplier.apply()` gains the same `UUID workLedgerEntryId` parameter.

#### Wire-reconstructed events

`WorkItemLifecycleEvent.fromWire()` gains a `@Nullable UUID ledgerEntryId` parameter. Distributed broadcaster events carry the ID when the sending node's ledger module populated it. If the sending node has no ledger module, the field is null — this is expected and safe.

#### PlanItemCompletionApplier

`PlanItemCompletionApplier.apply()` gains a `UUID workLedgerEntryId` parameter. The work-side change stops here — the parameter is accepted and logged at DEBUG. How the engine consumes it (adding a field to `PlanItemStateChangedEvent`, writing `causedByEntryId`) is engine#205 scope. `PlanItemStateChangedEvent` is an engine-common record — modifying it requires a change in the engine repo.

### Part 2: CrossSystemRef type hierarchy (#366)

#### CrossSystemRef — non-sealed interface in work-api

```java
package io.casehub.work.api;

public interface CrossSystemRef {
    String system();
    String encode();
}
```

Minimal surface — work-api stays opaque about callerRef content. No domain-specific fields, no decode logic, no ledgerEntryId. Each integration module provides its own implementations with domain-specific accessors.

#### engine-adapter — CallerRef extends CrossSystemRef

```java
package io.casehub.work.engine;

public sealed interface CallerRef extends CrossSystemRef
    permits PlanItemRef, GateRef {
    UUID caseId();
    @Override default String system() { return "engine"; }
    static CallerRef parse(String raw) { ... }  // unchanged logic
}

public record PlanItemRef(UUID caseId, String planItemId) implements CallerRef {
    @Override public String encode() { return "case:" + caseId + "/pi:" + planItemId; }
}

public record GateRef(UUID caseId, long gateId) implements CallerRef {
    @Override public String encode() { return "case:" + caseId + "/gate:" + gateId; }
}
```

This is a rename of the existing types (`PlanItemRef` → `PlanItemRef`, `GateRef` → `GateRef`) plus implementing `CrossSystemRef`. The sealed hierarchy and parse logic stay in engine-adapter where they belong.

#### qhorus — QhorusRef extends CrossSystemRef

```java
package io.casehub.work.qhorus;

public record QhorusRef(UUID channelId, long messageId, String correlationId)
    implements CrossSystemRef {
    @Override public String system() { return "qhorus"; }
    @Override public String encode() { return "qhorus:" + channelId + "/" + messageId + "/" + correlationId; }
    public static QhorusRef parse(String callerRef) { ... }  // unchanged logic
    public static boolean isQhorus(String callerRef) { ... } // unchanged
}
```

Rename from `QhorusRef` → `QhorusRef`, implementing `CrossSystemRef`. Parse logic stays in qhorus module.

#### decode() — no centralized decode

Each module retains its own parse/decode logic. There is no centralized `CrossSystemRef.decode()` — that would require work-api to know about all formats, violating opacity. The `CrossSystemRef` interface provides `system()` for runtime type identification when the concrete type is already resolved.

Future platform lineage work (parent#363) can introduce a `CrossSystemRefRegistry` that collects parsers from integration modules via CDI discovery — but that's out of scope here.

#### Error handling contract

`CallerRef.parse()` returns null on unrecognized input (existing behavior). `QhorusRef.parse()` throws on malformed input (existing behavior — callers guard with `isQhorus()` first). These contracts are preserved — unifying them is a separate concern.

### Changes by module

#### work-api

- **New:** `CrossSystemRef` interface (2 methods: `system()`, `encode()`)
- **New:** `LedgerEntryIdSetter` SPI interface
- **Modified:** `WorkItemLifecycleEvent` — add private `UUID ledgerEntryId` field, getter, and static `LedgerEntryIdSetter` accessor
- **Modified:** `WorkItemEvent` — add `default UUID ledgerEntryId() { return null; }` to interface
- **Modified:** `WorkItemLifecycleEvent.fromWire()` — add `@Nullable UUID ledgerEntryId` parameter

#### engine-adapter

- **Renamed:** `PlanItemRef` → `PlanItemRef` (implements `CallerRef extends CrossSystemRef`)
- **Renamed:** `GateRef` → `GateRef` (implements `CallerRef extends CrossSystemRef`)
- **Modified:** `CallerRef` — extends `CrossSystemRef`, adds `default system()` method
- **Modified:** `WorkItemLifecycleAdapter.onWorkItemLifecycle()` — pass `event.ledgerEntryId()` to applier and gateApplier
- **Modified:** `WorkItemLifecycleAdapter.handleSuspension()` — use renamed types
- **Modified:** `WorkItemLifecycleAdapter.handlePossibleResume()` — use renamed types
- **Modified:** `PlanItemCompletionApplier.apply()` — add `UUID workLedgerEntryId` parameter, log at DEBUG
- **Modified:** `ActionGateCompletionApplier.apply()` — add `UUID workLedgerEntryId` parameter
- **Modified:** `ActionGateCancelledHandler` — use renamed `GateRef`
- **Modified:** `HumanTaskScheduleHandler` — use `PlanItemRef.encode()` instead of `PlanItemCallerRef.encode()`
- **Modified:** `ActionGateWorkItemHandler` — use `GateRef.encode()` instead of `GateCallerRef.encode()`

#### qhorus

- **Renamed:** `QhorusRef` → `QhorusRef` (implements `CrossSystemRef`)
- **Modified:** `QhorusWorkItemLifecycleAdapter` — use renamed `QhorusRef`
- **Modified:** `WorkQhorusMcpTools` (or equivalent MCP tool class) — use `QhorusRef.encode()`

#### ledger

- **Modified:** `LedgerEventCapture.onWorkItemEvent()` — after `ledgerRepo.save(entry)`, call `LedgerEntryIdSetter` to set `event.ledgerEntryId`

#### rest

- **Modified:** `SpawnCallerRefTest` — use renamed `PlanItemRef`/`GateRef`

### What does NOT change

- **Database schema** — `callerRef` column stays TEXT, encoded strings are identical
- **REST API** — callerRef is returned as a string in API responses (unchanged)
- **Engine-side `CallerRefParser`** — stays as-is in `casehub-engine`. Follow-on issue to have it consume `CrossSystemRef` or delegate to `CallerRef.parse()`
- **Provenance supplement in LedgerEventCapture** — still writes `sourceEntityId = callerRef` string on CREATED entries (unchanged)
- **Parse/decode error handling** — each module's existing contract preserved

---

## Tests

### work-api

1. `CrossSystemRefTest` — `PlanItemRef`, `GateRef`, `QhorusRef` all implement `CrossSystemRef` with correct `system()` values
2. `CrossSystemRefTest` — `encode()` produces backward-compatible strings matching existing formats
3. `WorkItemLifecycleEventTest` — `ledgerEntryId` is null after construction, populated after `LedgerEntryIdSetter.setLedgerEntryId()`
4. `WorkItemLifecycleEventTest` — `fromWire()` with ledgerEntryId parameter round-trips correctly

### engine-adapter

5. `WorkItemLifecycleAdapterTest` — terminal event with `PlanItemRef` callerRef triggers `applier.apply()` with correct `ledgerEntryId`
6. `WorkItemLifecycleAdapterTest` — terminal event with `GateRef` callerRef routes to `gateApplier` with `ledgerEntryId`
7. `WorkItemLifecycleAdapterTest` — null callerRef returns early (no NPE)
8. `WorkItemLifecycleAdapterTest` — null `ledgerEntryId` (no ledger module) passed through without error
9. `PlanItemCompletionApplierTest` — `workLedgerEntryId` parameter accepted and logged at DEBUG
10. `CallerRefTest` — `PlanItemRef` and `GateRef` implement `CrossSystemRef` correctly

### qhorus

11. `QhorusRefTest` — `QhorusRef` implements `CrossSystemRef` with `system() = "qhorus"`
12. `QhorusWorkItemLifecycleAdapterTest` — terminal event with `QhorusRef` callerRef dispatches to channel
13. `QhorusWorkItemLifecycleAdapterTest` — non-qhorus callerRef is ignored

### ledger

14. `LedgerEventCaptureTest` — after ledger entry written, `event.ledgerEntryId()` is non-null and matches persisted entry ID

### integration-tests

15. `LedgerEntryIdThreadingIT` — end-to-end: create WorkItem, complete it, verify `ledgerEntryId` is available on the `@ObservesAsync` event in a real Quarkus container

---

## Out of scope

- Engine-side `CallerRefParser` migration — follow-on issue in casehubio/engine
- Engine-side wiring of `causedByEntryId` from the received `workLedgerEntryId` — engine#205 scope
- Reasoning traces as lineage DAG participants — engine#1007
- Platform-wide PROV-DM export — parent#431
- `WorkItemGroupLifecycleEvent` ledger entry threading — follows the same pattern; separate issue
- Centralized `CrossSystemRef.decode()` / `CrossSystemRefRegistry` — future work when parent#363 needs cross-module resolution
- Unifying error handling contracts between `CallerRef.parse()` (returns null) and `QhorusRef.parse()` (throws) — separate concern

---

## Review findings incorporated

| # | Finding | Resolution |
|---|---------|------------|
| 1 | Mutable setter contradicts immutability | `LedgerEntryIdSetter` SPI — public API stays immutable, setter is module-internal |
| 2 | ledgerEntryId phantom field on CrossSystemRef | Removed — ledgerEntryId threaded via WorkItemLifecycleEvent, not CrossSystemRef |
| 3 | CrossSystemRef in work-api violates opacity | Moved to non-sealed interface with minimal surface; engine-specific types stay in engine-adapter |
| 4 | ActionGateCompletionApplier missing | Added to change list |
| 5 | Deleted types still referenced in untouched files | Added SpawnCallerRefTest, ActionGateCancelledHandler to change list |
| 6 | fromWire() drops ledgerEntryId | Added ledgerEntryId parameter to fromWire() |
| 7 | Error handling mismatch (throw vs null) | Preserved each module's existing contract; unification deferred |
| 8 | entityType() unspecified | Removed entityType() from interface — unnecessary on minimal CrossSystemRef |
| 9 | Gate path omitted from threading | Added gate path to threading diagram |
| 10 | GenericRef edge cases | Removed GenericRef — no centralized decode means no fallback type needed |
| 11 | No integration test | Added LedgerEntryIdThreadingIT |

---

## References

- `engine-adapter/src/main/java/io/casehub/work/engine/CallerRef.java` — sealed interface (modified to extend CrossSystemRef)
- `engine-adapter/src/main/java/io/casehub/work/engine/PlanItemCallerRef.java` → renamed to PlanItemRef
- `engine-adapter/src/main/java/io/casehub/work/engine/GateCallerRef.java` → renamed to GateRef
- `qhorus/src/main/java/io/casehub/work/qhorus/QhorusCallerRef.java` → renamed to QhorusRef
- `engine/common/src/main/java/io/casehub/engine/common/spi/CallerRefParser.java` — engine-side duplicate (not changed)
- `api/src/main/java/io/casehub/work/api/WorkItemLifecycleEvent.java` — event class (modified)
- `engine-adapter/src/main/java/io/casehub/work/engine/WorkItemLifecycleAdapter.java` — inbound adapter (modified)
- `engine-adapter/src/main/java/io/casehub/work/engine/PlanItemCompletionApplier.java` — completion path (modified)
- `engine-adapter/src/main/java/io/casehub/work/engine/ActionGateCompletionApplier.java` — gate path (modified)
- `engine-adapter/src/main/java/io/casehub/work/engine/ActionGateCancelledHandler.java` — gate cancel (modified)
- `ledger/src/main/java/io/casehub/work/ledger/service/LedgerEventCapture.java` — ledger write (modified)
- `rest/src/test/java/io/casehub/work/rest/SpawnCallerRefTest.java` — test using renamed types
- `docs/specs/2026-05-15-callerref-multi-instance-design.md` — prior callerRef design for multi-instance groups
- Slot 140 handoff — reasoning traces landing (validates "add to event, thread through pipeline" pattern)
- casehubio/engine#205 — complete causal lineage (consumes the ledgerEntryId we thread)
- casehubio/engine#1007 — reasoning traces as lineage participants (separate concern)
- casehubio/parent#363 — explainable decisions end-to-end (consumes cross-system lineage)
- casehubio/parent#431 — platform-wide PROV-DM export (consumes cross-system lineage)
