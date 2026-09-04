# Replace VocabularyScope Enum with Path-Based Scope Hierarchy

**Issue:** casehubio/work#236
**Date:** 2026-06-12
**Status:** Approved

## Summary

Replace the fixed 4-level `VocabularyScope` enum (GLOBAL/ORG/TEAM/PERSONAL) with the platform's `Path` type from `casehub-platform-api`. Scope accessibility changes from ordinal comparison to `Path.isAncestorOf()`. The `ownerId` field is dropped — the scope path subsumes it. The `VocabularyScope` enum is deleted.

## Motivation

`VocabularyScope` is a parallel scope type that violates the platform boundary rule: "Do not define parallel path, scope, preference, or principal types." `Path` already provides `isAncestorOf()`, `root()`, `depth()`, and `segments()` — everything the vocabulary hierarchy needs, without a hardcoded 4-level ceiling.

The `ownerId` field is entangled with `scope` — they form a compound key where the enum constrains valid owner types. With `Path`, the compound key collapses: the path segments encode both the hierarchy tier (depth) and the identity (segment values). Keeping `ownerId` alongside a scope path creates two sources of truth with no consumer.

## Design

### Domain Model — `LabelVocabulary`

Replace the `scope` field type and drop `ownerId`:

```java
// Before
@Enumerated(EnumType.STRING)
@Column(nullable = false, length = 20)
public VocabularyScope scope;

@Column(name = "owner_id", length = 255)
public String ownerId;

// After
@Convert(converter = PathAttributeConverter.class)
@Column(nullable = false, length = 500)
public Path scope;
```

### PathAttributeConverter — Root Path Fix

`Path.parse("")` throws `IllegalArgumentException`. Root scope stores as `""` in the DB. The converter needs a root-path guard:

```java
@Override
public Path convertToEntityAttribute(final String value) {
    if (value == null) return null;
    if (value.isEmpty()) return Path.root();
    return Path.parse(value);
}
```

`convertToDatabaseColumn` is unchanged — `Path.root().value()` returns `""`.

### Service Layer — `LabelVocabularyService`

**`isScopeAccessible` → `isAccessibleFrom`:**

```java
public static boolean isAccessibleFrom(Path vocabScope, Path callerScope) {
    return vocabScope.equals(callerScope) || vocabScope.isAncestorOf(callerScope);
}
```

Root semantics: `Path.root().isAncestorOf(anyNonRoot)` returns true (empty segments → `!other.segments.isEmpty()`). Root-scoped vocabularies are accessible to everyone. A root caller sees only root-scoped vocabularies. Matches current GLOBAL behaviour.

**`listAccessible(VocabularyScope)` → `listAccessible(Path)`:** Same logic, new types.

**`findGlobalVocabulary()` — deleted.** Only caller was `VocabularyResource.addDefinition()` for the root-scope branch. In the new model, root is just another path — `findOrCreateVocabulary(Path.root(), "Global")` handles it. The method also hid a multi-tenant bug: `scanAll()` filters by `currentPrincipal.tenancyId()`, but the V3 seed row gets the V35 default tenant UUID (`278776f9-...`). Any other tenant's `findGlobalVocabulary()` returned null → 500 error. Unifying to `findOrCreateVocabulary` fixes this — auto-creates a root vocabulary per tenant on first use.

**`findOrCreateVocabulary` — simplified, delegates to store:**

```java
// Before: (VocabularyScope scope, String ownerId, String name) — scan-all + filter + create
// After:  (Path scope, String name) — delegates to store.findOrCreate()
public LabelVocabulary findOrCreateVocabulary(Path scope, String name) {
    return labelVocabularyStore.findOrCreate(scope, name);
}
```

The service decides the name (`"Global"` for root, `scopePath.value()` for non-root). The store handles atomicity. See Store/Repository Layer below.

**`listAllDefinitions()` — new method:**

```java
@Transactional
public List<LabelDefinition> listAllDefinitions() {
    return labelVocabularyStore.scanAll().stream()
            .flatMap(v -> labelDefinitionStore.findByVocabularyId(v.id).stream())
            .toList();
}
```

Provides the unfiltered listing that `GET /vocabulary` needs. `VocabularyResource` injects only `LabelVocabularyService` — it has no path to `LabelDefinitionStore` without this method. Both `listAllDefinitions()` and `listAccessible(Path)` remain — the former for the current unfiltered endpoint, the latter for when scope enforcement activates.

### REST API — `VocabularyResource`

**Scope moves from URL path param to request body.** Path strings like `acme-corp/hr-team` contain `/` which collides with URL path structure. The scope is a property of the operation — it belongs in the body.

```java
// Before
record AddDefinitionRequest(String path, String description, String addedBy, String ownerId) {}
@POST @Path("/{scope}")
Response addDefinition(@PathParam("scope") String scopeStr, AddDefinitionRequest request)

// After
record AddDefinitionRequest(String path, String description, String addedBy, String scope) {}
@POST
Response addDefinition(AddDefinitionRequest request)
```

**Full collapsed `addDefinition` implementation:**

```java
@POST
@Transactional
public Response addDefinition(final AddDefinitionRequest request) {
    if (request == null || request.path() == null || request.path().isBlank()) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", "path is required")).build();
    }
    if (request.path().contains("*") || request.path().contains("?")) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", "path must not contain wildcard characters")).build();
    }

    final io.casehub.platform.api.path.Path labelPath;
    try {
        labelPath = io.casehub.platform.api.path.Path.parse(request.path());
    } catch (IllegalArgumentException e) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", "invalid path: " + e.getMessage())).build();
    }

    final io.casehub.platform.api.path.Path scopePath;
    try {
        scopePath = (request.scope() == null || request.scope().isBlank())
                ? io.casehub.platform.api.path.Path.root()
                : io.casehub.platform.api.path.Path.parse(request.scope());
    } catch (IllegalArgumentException e) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", "invalid scope: " + e.getMessage())).build();
    }

    final String vocabName = scopePath.value().isEmpty() ? "Global" : scopePath.value();
    final LabelVocabulary vocab = vocabularyService.findOrCreateVocabulary(scopePath, vocabName);

    final LabelDefinition def = vocabularyService.addDefinition(
            vocab.id, labelPath, request.description(),
            request.addedBy() != null ? request.addedBy() : "unknown");

    return Response.status(Response.Status.CREATED)
            .entity(Map.of("id", def.id, "path", def.path.value(), "scope", scopePath.value()))
            .build();
}
```

Key changes visible in the implementation:
- **Scope parsing:** null/blank → `Path.root()`, otherwise `Path.parse(scope)` with error handling.
- **Unified vocabulary resolution:** all scopes (including root) go through `findOrCreateVocabulary`. No root-vs-non-root branching. No null check. This also fixes the pre-existing multi-tenant bug where `findGlobalVocabulary()` returned null for non-default tenants (the V3 seed row was locked to the V35 default tenant UUID).
- **Vocabulary name derivation:** root scope gets `"Global"`, non-root gets `scopePath.value()`. The `name` parameter stays on `findOrCreateVocabulary` because it serves a display purpose ("HR Team Leave Vocabulary" vs "casehubio/hr-team"), but the REST endpoint derives it from the path for auto-creation.
- **Response serialization:** `scopePath.value()` returns a flat string, not a JSON object. Without `.value()`, Jackson would serialize `Path` as `{"value":"…","segments":[…]}`.
- **All GLOBAL/ORG/TEAM/PERSONAL branching eliminated** — the enum parsing, ownerId defaulting for PERSONAL, ownerId validation for ORG/TEAM, scope-specific error messages, and the root special case all collapse to a single Path parse + findOrCreateVocabulary.

**`GET /vocabulary` (listAll):** Currently hardcodes `VocabularyScope.PERSONAL` (= "show everything"). No `Path` equivalent of "deepest possible" exists. Change to call `vocabularyService.listAllDefinitions()` — scans all tenant-visible vocabularies and flatmaps their definitions. This is what the endpoint was effectively doing (scope enforcement is deferred). The `listAccessible(Path)` method remains available for when scope enforcement is activated.

### Store/Repository Layer

The store SPI is missing a natural key lookup — `LabelDefinitionStore` has `findByPath(Path)` but `LabelVocabularyStore` has no equivalent. This forces the service into `scanAll().stream().filter()`, which is both inefficient and non-atomic.

**Two new methods on `LabelVocabularyStore` SPI:**

```java
Optional<LabelVocabulary> findByScope(Path scope);

LabelVocabulary findOrCreate(Path scope, String name);
```

`findByScope` provides the targeted natural key lookup. `findOrCreate` provides atomic get-or-create — each backend handles concurrency with its own tools.

**Default implementation** (for backends that don't need special concurrency handling):

```java
default LabelVocabulary findOrCreate(Path scope, String name) {
    return findByScope(scope).orElseGet(() -> {
        LabelVocabulary vocab = new LabelVocabulary();
        vocab.scope = scope;
        vocab.name = name;
        return put(vocab);
    });
}
```

**JPA implementation** — `@Transactional(REQUIRES_NEW)` isolates vocabulary creation so a UNIQUE constraint violation doesn't corrupt the caller's transaction. After a constraint violation, the Hibernate Session is dirty and unusable; `clear()` resets it, and the re-query finds the winning thread's committed row.

```java
@Override
public Optional<LabelVocabulary> findByScope(Path scope) {
    return withTenantQuery(() ->
        LabelVocabulary.find("scope = ?1 AND tenancyId = ?2", scope, currentPrincipal.tenancyId())
            .firstResultOptional());
}

@Override
@Transactional(TxType.REQUIRES_NEW)
public LabelVocabulary findOrCreate(Path scope, String name) {
    return withTenantQuery(() -> {
        Optional<LabelVocabulary> existing = LabelVocabulary
            .find("scope = ?1 AND tenancyId = ?2", scope, currentPrincipal.tenancyId())
            .firstResultOptional();
        if (existing.isPresent()) {
            return existing.get();
        }
        LabelVocabulary vocab = new LabelVocabulary();
        vocab.scope = scope;
        vocab.name = name;
        vocab.tenancyId = currentPrincipal.tenancyId();
        try {
            vocab.persistAndFlush();
            return vocab;
        } catch (PersistenceException e) {
            LabelVocabulary.getEntityManager().clear();
            return LabelVocabulary
                .find("scope = ?1 AND tenancyId = ?2", scope, currentPrincipal.tenancyId())
                .<LabelVocabulary>firstResultOptional()
                .orElseThrow(() -> new IllegalStateException(
                    "Concurrent vocabulary creation failed", e));
        }
    });
}
```

`REQUIRES_NEW` side-effect: if the outer transaction later fails, the vocabulary persists independently. Harmless — an empty vocabulary at a scope path is inert.

**InMemory implementation** — `synchronized` for atomicity:

```java
@Override
public Optional<LabelVocabulary> findByScope(Path scope) {
    String tenancyId = currentPrincipal.tenancyId();
    return store.values().stream()
        .filter(v -> tenancyId.equals(v.tenancyId))
        .filter(v -> scope.equals(v.scope))
        .findFirst();
}

@Override
public LabelVocabulary findOrCreate(Path scope, String name) {
    String tenancyId = currentPrincipal.tenancyId();
    synchronized (store) {
        Optional<LabelVocabulary> existing = store.values().stream()
            .filter(v -> tenancyId.equals(v.tenancyId))
            .filter(v -> scope.equals(v.scope))
            .findFirst();
        if (existing.isPresent()) {
            return existing.get();
        }
        LabelVocabulary vocab = new LabelVocabulary();
        vocab.id = UUID.randomUUID();
        vocab.scope = scope;
        vocab.name = name;
        vocab.tenancyId = tenancyId;
        store.put(vocab.id, vocab);
        return vocab;
    }
}
```

**Three layers of fix:**

| Layer | What it fixes | Mechanism |
|-------|--------------|-----------|
| `findByScope(Path)` on SPI | Scan-all inefficiency | Targeted JPQL query |
| `UNIQUE(scope, tenancy_id)` | Data corruption | Database constraint (in V36 migration) |
| `findOrCreate` on SPI | Race condition → 500 | `REQUIRES_NEW` + catch + re-query (JPA); `synchronized` (InMemory) |

### Flyway Migration — V36

File: `runtime/src/main/resources/db/work/migration/V36__vocabulary_scope_to_path.sql`

V36, not V5003. Per `docs/FLYWAY.md`, V1–V999 belongs to the runtime module (currently at V35). V5000–V5999 belongs to `casehub-work-issue-tracker`.

```sql
-- Widen scope column
ALTER TABLE label_vocabulary ALTER COLUMN scope TYPE VARCHAR(500);

-- Convert enum values to path strings
UPDATE label_vocabulary SET scope = '' WHERE scope = 'GLOBAL';
UPDATE label_vocabulary SET scope = owner_id WHERE scope IN ('ORG', 'TEAM', 'PERSONAL');

-- Drop owner_id
ALTER TABLE label_vocabulary DROP COLUMN owner_id;

-- Enforce: one vocabulary per scope path per tenant
-- Follows V35 precedent: uq_work_item_template_name_tenant UNIQUE(name, tenancy_id)
CREATE UNIQUE INDEX uq_label_vocabulary_scope_tenant ON label_vocabulary(scope, tenancy_id);
```

**Data loss acknowledgment:** ORG/TEAM/PERSONAL vocabularies become single-segment paths. The tier distinction is lost because the old schema never captured hierarchy — `ownerId="hr-team"` at TEAM scope didn't record which org. New vocabularies use proper hierarchical paths. The seeded GLOBAL row converts to `scope=''` (root).

**Collision caveat:** The `SET scope = owner_id` conversion collapses different tiers sharing an ownerId into the same path. If a dev database has both `(ORG, 'engineering')` and `(TEAM, 'engineering')` for the same tenant, the unique index creation fails. This is correct — the migration surfaces the collision rather than silently orphaning a row. In practice this requires deliberate REST API calls and no production data exists.

**H2 compatibility:** `ALTER COLUMN ... TYPE` and `DROP COLUMN` work in both PostgreSQL and H2 `MODE=PostgreSQL`.

### Tests

**`LabelVocabularyServiceTest`:** Ordinal hierarchy tests → ancestor-based accessibility tests. Enum count test deleted. `matchesPattern` tests unchanged.

**`JpaLabelVocabularyStoreTenancyTest` / `JpaLabelDefinitionStoreTenancyTest`:** `VocabularyScope.GLOBAL` → `Path.root()` in test vocabulary creation.

**`VocabularyScenario`:** `VocabularyScope.TEAM` → `Path.of("casehubio", TEAM_ID)`. Demonstrates proper two-segment path following platform convention.

**`PathAttributeConverterTest` — root roundtrip test:** The root-path guard is the entire point of the converter change. Add test cases:

```java
@Test
void rootPath_roundtrips() {
    PathAttributeConverter converter = new PathAttributeConverter();
    // Path.root() → "" in database
    assertThat(converter.convertToDatabaseColumn(Path.root())).isEqualTo("");
    // "" in database → Path.root()
    assertThat(converter.convertToEntityAttribute("")).isEqualTo(Path.root());
}
```

### Cleanup

Delete `VocabularyScope.java`. All references eliminated by the changes above.

### ARC42STORIES.MD Updates

Three updates in the Layer 16 Key Files and Key Wiring sections:

1. **Remove VocabularyScope.java row** (~line 1452): file deleted.
2. **Update LabelVocabulary.java description** (~line 1450): change "scope enum GLOBAL/ORG/TEAM/PERSONAL" to "scope uses `Path` from `casehub-platform-api`; `PathAttributeConverter` stores as VARCHAR".
3. **Update Key Wiring section** (~line 1468): note that `LabelVocabulary.scope` now also uses `PathAttributeConverter` alongside `LabelDefinition.path`.

## Files Changed

| File | Change |
|------|--------|
| `runtime/.../model/VocabularyScope.java` | **Deleted** |
| `runtime/.../model/LabelVocabulary.java` | scope: enum → Path, drop ownerId |
| `runtime/.../model/PathAttributeConverter.java` | Root path guard in `convertToEntityAttribute` |
| `runtime/.../repository/LabelVocabularyStore.java` | Add `findByScope(Path)`, `findOrCreate(Path, String)` with default impl |
| `runtime/.../repository/jpa/JpaLabelVocabularyStore.java` | Override `findByScope` (JPQL), `findOrCreate` (REQUIRES_NEW + catch + re-query) |
| `runtime/.../service/LabelVocabularyService.java` | All scope methods: enum → Path; `findOrCreateVocabulary` delegates to store; add `listAllDefinitions()`; delete `findGlobalVocabulary()` |
| `runtime/.../api/VocabularyResource.java` | Scope in body, drop ownerId, listAll calls `listAllDefinitions()`, full impl shown above |
| `runtime/.../resources/db/work/migration/V36__vocabulary_scope_to_path.sql` | Migration + UNIQUE(scope, tenancy_id) |
| `persistence-memory/.../InMemoryLabelVocabularyStore.java` | Override `findByScope` (filter), `findOrCreate` (synchronized) |
| `runtime/test/.../LabelVocabularyServiceTest.java` | Rewrite scope tests |
| `runtime/test/.../PathAttributeConverterTest.java` | Add root roundtrip test |
| `runtime/test/.../JpaLabelVocabularyStoreTenancyTest.java` | GLOBAL → Path.root() |
| `runtime/test/.../JpaLabelDefinitionStoreTenancyTest.java` | GLOBAL → Path.root() |
| `runtime/test/.../api/LabelEndpointTest.java` | Rewrite vocabulary tests: scope in body, drop ownerId tests, delete `personal_defaultsOwnerToAddedBy`, `org_missingOwnerId_returns400`, `invalidScope_returns400` (conceptually invalidated); update response assertions from enum strings to path strings |
| `examples/.../VocabularyScenario.java` | TEAM → Path.of("casehubio", TEAM_ID) |
| `docs/api-reference.md` | Rewrite Vocabulary API section (~line 619+): `POST /vocabulary` with scope in body, Path-based hierarchy, drop ownerId, update curl examples, remove `501` for non-GLOBAL scopes |
| `ARC42STORIES.MD` | Remove VocabularyScope.java row, update LabelVocabulary description, update Key Wiring |

## Garden Entries Referenced

- `GE-20260522-a87fd7`: Path.parent() returns null for single-segment paths — root scope excluded from ancestor walk. Relevant to root handling but `isAncestorOf()` handles root correctly.

## Platform Coherence

- Removes parallel scope type per boundary rule
- Uses `Path` from `casehub-platform-api` as designed
- `VocabularyScope` is in `runtime/model/` (not `casehub-work-api`) — no external consumers, no cross-repo propagation
- Fixes pre-existing multi-tenant bug in `findGlobalVocabulary()` — seeded GLOBAL vocabulary was locked to default tenant

**Deferred:** `FilterScope` enum in `queues/` module (`PERSONAL`, `TEAM`, `ORG`) — same boundary rule violation, 58 references. Tracked as #263.
