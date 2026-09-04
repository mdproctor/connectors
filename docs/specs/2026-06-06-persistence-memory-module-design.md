# Spec: persistence-memory module extraction

**Issue:** casehubio/work#191
**Branch:** issue-191-persistence-memory
**Date:** 2026-06-06

---

## Problem

In-memory store implementations (`InMemoryWorkItemStore`, `InMemoryAuditEntryStore`,
`InMemoryWorkItemNoteStore`, `InMemoryIssueLinkStore`, `InMemoryRoutingCursorStore`)
live in `testing/`. This conflates two distinct concerns and introduces two real bugs:

1. **Wrong module semantics.** A module named `testing/` signals test-only and may bundle
   test-framework artifacts. These stores are valid production deployment targets (ephemeral,
   data lost on restart — acceptable for demos and local evaluation).

2. **Priority collision with MongoDB.** All five in-memory stores are `@Alternative @Priority(1)`,
   which is Tier 2 in the platform CDI priority ladder — the same slot as `MongoWorkItemStore`
   and `MongoAuditEntryStore`. Any deployment with both modules on the classpath raises
   `AmbiguousResolutionException` for `WorkItemStore` and `AuditEntryStore`.

3. **Not thread-safe.** Four of five stores use `LinkedHashMap` and are documented as
   "Not thread-safe — designed for single-threaded test use only." Production deployment
   with concurrent requests requires thread-safe data structures.

---

## Design

### New module: `persistence-memory/`

| Field | Value |
|---|---|
| artifactId | `casehub-work-persistence-memory` |
| package | `io.casehub.work.memory` |
| parent | `casehub-work-parent` |

**Contents:** the five in-memory store implementations, re-annotated to the new Tier 3,
with thread-safe data structures.

**Dependencies:**

| Artifact | Scope | Reason |
|---|---|---|
| `casehub-work` | compile | WorkItemStore, AuditEntryStore, WorkItemNoteStore. RoutingCursorStore is in `casehub-work-core`, available transitively through `casehub-work`. |
| `casehub-work-issue-tracker` | compile | IssueLinkStore |
| `jakarta.enterprise.cdi-api` | provided | CDI annotations |
| `casehub-platform` | test | CDI scan setup for store unit tests |
| `quarkus-junit5`, `assertj-core` | test | store unit tests |

Jandex plugin: copy the `io.smallrye:jandex-maven-plugin` configuration from
`persistence-mongodb/pom.xml`.

### CDI tier — adding a fourth tier to the platform ladder

The platform protocol `persistence-backend-cdi-priority.md` defines three tiers:

| Tier | Annotation | Purpose |
|---|---|---|
| 0 — Zero-dep default | `@DefaultBean` | Mock/fallback when no real backend present |
| 1 — Production default | `@ApplicationScoped` | JPA/SQL |
| 2 — Override | `@Alternative @Priority(1)` | NoSQL (MongoDB), beats JPA when co-deployed |

This spec adds a fourth:

| Tier | Annotation | Purpose |
|---|---|---|
| 3 — Ephemeral | `@Alternative @Priority(100)` | In-memory; wins over all backends when on classpath |

**Tier 0 in this codebase:** `NoOpRoutingCursorStore` in `core/` is `@DefaultBean
@ApplicationScoped` — the fallback when `casehub-work` runtime is not on the classpath.
`PermissiveCapabilityRegistry`, `NoOpSlaBreachPolicy`, `NoOpGroupMembershipProvider`,
`CommaSeparatedExclusionPolicy`, and `LocalWorkItemEventBroadcaster` also follow Tier 0.
`WorkItemStore`, `AuditEntryStore`, `WorkItemNoteStore`, and `IssueLinkStore` have no
Tier 0 — the extension always provides JPA.

**Priority inversion:** unlike Tiers 0–2, where higher priority implies a more capable
backend, Tier 3 is an override mechanism. The ephemeral store is the least capable backend
(data lost on restart) with the highest CDI priority (beats everything). This is correct
for the classpath-activation model — you add `persistence-memory` when you deliberately
want ephemeral storage, and it must win to be useful. The protocol update must document
this semantic inversion.

Priority(100) instead of Priority(10) widens the gap for future tiers between Tier 2
and Tier 3 (e.g. Redis at Priority(50)).

**Both-modules-on-classpath:** when `persistence-mongodb` (Priority 1) and
`persistence-memory` (Priority 100) are both present, in-memory wins silently.
This is by design — typical use is `persistence-memory` at test scope alongside
a production backend. Document in module README.

The full ladder for casehub-work after this change:

| Tier | Annotation | Backend | Module |
|---|---|---|---|
| 0 | `@DefaultBean` | No-op/fallback | `core/` (RoutingCursorStore only) |
| 1 | `@ApplicationScoped` | JPA (default) | `runtime/`, `issue-tracker/` |
| 2 | `@Alternative @Priority(1)` | MongoDB | `persistence-mongodb/` |
| 3 | `@Alternative @Priority(100)` | In-memory/ephemeral | `persistence-memory/` ← new |

### SPI Javadoc updates — correcting a pre-existing error

The current WorkItemStore Javadoc says:

> `@DefaultBean` (mock/in-memory) < `@ApplicationScoped` (JPA/SQL primary) <
> `@Alternative @Priority(1)` (NoSQL secondary, beats JPA when co-deployed).

This was always wrong. The in-memory implementations have never been `@DefaultBean` —
since their creation, they have been `@Alternative @Priority(1)`, the same as MongoDB.
The Javadoc described the intended architecture, but the implementation never matched.

The update corrects the Javadoc to match the actual four-tier ladder. Each store interface
documents its actual tier coverage. `WorkItemStore` and `AuditEntryStore` have Tiers 0–3
(where applicable). `WorkItemNoteStore`, `IssueLinkStore`, and `RoutingCursorStore` have
only Tiers 0/1 and 3 — no Tier 2 MongoDB implementation exists yet (tracked as #253).

### Thread safety

Replace data structures with lock-free concurrent alternatives. No explicit locks.

| Store | Current | Replace with | Rationale |
|---|---|---|---|
| InMemoryWorkItemStore | `LinkedHashMap<UUID, WorkItem>` | `ConcurrentHashMap<UUID, WorkItem>` | Lock-free CRUD; weakly consistent scan matches READ COMMITTED |
| InMemoryAuditEntryStore | `ArrayList<AuditEntry>` | `ConcurrentHashMap<UUID, CopyOnWriteArrayList<AuditEntry>>` keyed by workItemId | See AuditEntryStore restructure below |
| InMemoryIssueLinkStore | `LinkedHashMap<UUID, WorkItemIssueLink>` | `ConcurrentHashMap<UUID, WorkItemIssueLink>` | Same as WorkItemStore |
| InMemoryWorkItemNoteStore | `LinkedHashMap<UUID, WorkItemNote>` | `ConcurrentHashMap<UUID, WorkItemNote>` | Same as WorkItemStore |
| InMemoryRoutingCursorStore | `ConcurrentHashMap<String, AtomicInteger>` | No change | Already thread-safe |

**Why ConcurrentHashMap over synchronized or ReadWriteLock:** ConcurrentHashMap's weakly
consistent iteration provides READ COMMITTED semantics — the same isolation level as the
JPA/Panache stores running on PostgreSQL. `synchronized` would provide SERIALIZABLE
(stricter than the database stores), creating a semantic mismatch. Lock-free reads mean
`get()` and `scan()` never block during concurrent writes.

**Javadoc update:** Remove "Not thread-safe — designed for single-threaded test use only."
Replace with: "Thread-safe. Data is ephemeral (lost on restart). Objects returned from the
store are shared references — concurrent field-level mutations to the same object without
calling `put()` are not guaranteed to be visible across threads."

### AuditEntryStore internal restructure

The current flat `ArrayList` forces O(n) scan on every `findByWorkItemId()` call. With
`CopyOnWriteArrayList` as a flat list, every `append()` copies the entire store.

Restructure to `ConcurrentHashMap<UUID, CopyOnWriteArrayList<AuditEntry>>` keyed by
workItemId:

- `append(entry)`: `map.computeIfAbsent(entry.workItemId, k -> new CopyOnWriteArrayList<>()).add(entry)` — atomic; copy-on-write cost is per-workItem (3–10 entries), not whole-store
- `findByWorkItemId(id)`: `map.getOrDefault(id, List.of())` — O(1) lookup, then sort the small per-item list
- `query(auditQuery)`: iterate the outer map (weakly consistent), flatMap inner lists, filter/sort/paginate. `AuditQuery` has no `workItemId` field, so this remains a full-iteration operation — the restructure does not change `query()` performance
- `count(auditQuery)`: same traversal, count instead of collect
- `clear()`: `map.clear()`

### clear() and findAll()

Keep on concrete classes unchanged. The SPI interfaces do not declare these methods.
Production code injects the interface, not the concrete class — `clear()` is invisible
through the injection point. Only test code that knows the concrete type can call them.

Update Javadoc from "Call in @BeforeEach to isolate tests" to "Available for test isolation
(@BeforeEach) and administrative reset."

### Module rename via git mv

Use `git mv testing/ persistence-memory/` to rename the module directory, then refactor
package names. Preserves per-file `git log --follow` history.

### `testing/` module deletion

After `git mv`, the module is fully renamed:
- All five `InMemory*Store.java` files end up in `persistence-memory/src/main/java/io/casehub/work/memory/`
- Both test classes (`InMemoryRepositoryTest`, `InMemoryIssueLinkStoreTest`) end up in
  `persistence-memory/src/test/java/io/casehub/work/memory/`
- `<module>testing</module>` removed from root `pom.xml`
- `<module>persistence-memory</module>` added after `<module>persistence-mongodb</module>`

### Consumer migration

Four modules depend on `casehub-work-testing` at `test` scope. `integration-tests/`,
`examples/`, `flow-examples/`, and `queues-examples/` do not depend on it — confirmed.

Each consumer needs:
1. **pom.xml:** `casehub-work-testing` → `casehub-work-persistence-memory` at `test` scope
2. **Java imports:** `io.casehub.work.testing.*` → `io.casehub.work.memory.*` in test sources

Import changes by module:
- `ai/` — 3 test files, 4 imports (InMemoryWorkItemStore, InMemoryAuditEntryStore)
- `notifications/` — no direct imports (verify during implementation)
- `postgres-broadcaster/` — no direct imports (verify during implementation)
- `queues-postgres-broadcaster/` — no direct imports (verify during implementation)

### Ephemeral production deploy

The casehub-work runtime module depends on `quarkus-hibernate-orm-panache`, which validates
datasource configuration at Quarkus build time. CDI priority alone does not bypass this.

**Required application.properties for ephemeral deploy:**

```properties
quarkus.datasource.active=false
quarkus.hibernate-orm.active=false
```

These properties (Quarkus 3.7+, available in 3.32.2) deactivate the extensions at build
time — present but inert. JPA beans exist but are not initialised. In-memory stores win
via CDI priority.

**Fallback** if deactivation does not work cleanly (verify in integration test):

```properties
quarkus.datasource.db-kind=h2
quarkus.datasource.jdbc.url=jdbc:h2:mem:ephemeral
quarkus.hibernate-orm.database.generation=none
```

Three lines, no external database, `quarkus-jdbc-h2` is already a runtime dependency.

**Required:** an integration test that boots with `persistence-memory` on the classpath
and the deactivation properties, verifies CRUD works through the in-memory stores. This
test determines which configuration to recommend in the module README.

### Known limitations

- **Shared object references.** Objects returned from the store are shared references.
  Concurrent field-level mutations to the same object without calling `put()` are not
  guaranteed to be visible across threads.

- **AuditEntryStore category filter.** The Javadoc says "Category filter is not supported
  (no WorkItem access); it is silently ignored." Fixing this requires injecting
  `WorkItemStore` into `AuditEntryStore` (inter-store dependency) — out of scope.

---

## Documentation updates

| Document | Change |
|---|---|
| `docs/MODULES.md` | Remove `testing/`, add `persistence-memory/`. Also add `persistence-mongodb/` — pre-existing gap (was never added when that module shipped). |
| `ARC42STORIES.MD` §5 | Update Building Block View with new module |
| `ARC42STORIES.MD` §9 | Journal entry for this chapter |
| Module `persistence-memory/README.md` | What it is, when to use it, priority tier, thread-safety guarantee, known limitations, ephemeral deployment config, both-modules-on-classpath behavior |
| SPI interface Javadoc | Correct pre-existing error and update tier ladder per-SPI (see SPI Javadoc section above) |

---

## Out of scope

**#253** — MongoDB completeness: `MongoWorkItemNoteStore`, `MongoIssueLinkStore`,
`MongoRoutingCursorStore` are missing. The `persistence-mongodb/` description promises a
"drop-in replacement" but is currently partial. Tracked separately.

---

## Best-practice compliance

- `inmemory-store-aggregate-no-scan-delegation` (casehub-qhorus protocol, adopted as best
  practice) — aggregate methods must stream the backing collection directly, not delegate
  to `scan()`. `scan()` applies pagination, silently truncating aggregates. Existing
  implementations comply; preserve this in the move.

---

## Protocol update

Extend `persistence-backend-cdi-priority.md` with a fourth tier:

> **Tier 3 — Ephemeral backend,** `@Alternative @Priority(100)`. Highest priority; beats
> all backends when on the classpath. Intended for in-memory/ephemeral stores that override
> everything by classpath presence. Unlike Tiers 0–2, where higher priority implies a more
> capable backend, Tier 3 is an override mechanism — it wins by design to replace all
> persistence with ephemeral storage for testing and demo scenarios.

Also propose broadening `inmemory-store-aggregate-no-scan-delegation` from casehub-qhorus
scope to universal — the footgun (scan() applying pagination to aggregates) exists in any
repo with InMemory stores and query pagination.
