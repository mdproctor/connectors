# Spec: LabelDefinition.path String → casehub-platform-api Path (#189)

**Date:** 2026-05-29
**Branch:** issue-235-sxs-sweep
**Scale:** XS · Low

## Problem

`LabelDefinition.path` is a bare `String`. This forecloses type-safe hierarchy operations
(`isAncestorOf`, `parent`, `depth`) and allows malformed paths to persist. `casehub-platform-api`
owns the `Path` type and `casehub-work-api` already declares it as a compile dependency — the
runtime has it transitively.

## Scope

Changes `LabelDefinition.path`, `findByPath`, `isDeclared`, and `addDefinition` only.

`matchesPattern(String pattern, String path)` is **not changed** — it receives `WorkItemLabel.path`
(a different entity), and changing its signature would cascade to `QueueMembershipContext`,
`InMemoryWorkItemStore`, and `WorkItemServiceTest`. Migrating `WorkItemLabel.path` to `Path` is
M-scale and tracked separately as a follow-up issue.

## Design

### No schema migration required

The DB column stays `VARCHAR(500)`. A `PathAttributeConverter` handles Java↔SQL transparently.
The stored value is `Path.value()` — identical to what is currently stored.

### PathAttributeConverter

New class in `runtime/src/main/java/io/casehub/work/runtime/model/PathAttributeConverter.java`:

```java
// autoApply = false: Path may appear in non-entity contexts (DTOs, value objects);
// force explicit opt-in per field rather than converting every Path field globally.
@Converter(autoApply = false)
public class PathAttributeConverter implements AttributeConverter<Path, String> {
    public String convertToDatabaseColumn(Path p) { return p == null ? null : p.value(); }
    public Path convertToEntityAttribute(String s) { return s == null ? null : Path.parse(s); }
}
```

### LabelDefinition

- `path` field: `String` → `io.casehub.platform.api.path.Path`
- Annotated `@Convert(converter = PathAttributeConverter.class)`
- `findByPath(String)` → `findByPath(Path)`, passes the `Path` object to Panache so Hibernate
  applies the converter correctly:
  ```java
  return find("path", path).list();
  ```

### LabelVocabularyService

- `isDeclared(String path)` → `isDeclared(Path path)` — passes typed `Path` to `findByPath`
- `addDefinition(UUID, String path, ...)` → `addDefinition(UUID, Path path, ...)`
- `matchesPattern(String, String)` — **unchanged** (see Scope above)
- `listAccessible` — `d.path` is now a `Path`; callers inside the service use it as-is
  (no string coercion needed at the service layer)

### VocabularyResource (REST boundary)

`AddDefinitionRequest.path` stays `String` — it is user-supplied input.

**Import note:** `VocabularyResource` already imports `jakarta.ws.rs.Path`. The platform `Path`
class must be used fully qualified (`io.casehub.platform.api.path.Path`) everywhere in this
file — no import, no alias.

**Wildcard validation:** `Path.parse` only rejects blank segments; `*` is accepted as a valid
segment character. A POST with `"path": "legal/*"` would store a pattern, not a definition path.
Reject wildcard characters at the REST boundary alongside path parsing.

Parse and validate in `addDefinition()`:

```java
final io.casehub.platform.api.path.Path labelPath;
try {
    labelPath = io.casehub.platform.api.path.Path.parse(request.path());
} catch (IllegalArgumentException e) {
    return Response.status(400).entity(Map.of("error", "invalid path: " + e.getMessage())).build();
}
if (request.path().contains("*")) {
    return Response.status(400).entity(Map.of("error", "path must not contain wildcard characters")).build();
}
```

**`listAll()` response:** `d.path` is now a `Path` record. Use `d.path.value()` to return the
bare string — `d.path.toString()` produces `Path[value=..., segments=[...]]` and will break
callers.

**`addDefinition()` response:** Same issue — use `def.path.value()`:
```java
Map.of("id", def.id, "path", def.path.value(), "scope", scope)
```

### Cascade callers

All callers of `addDefinition(UUID, String, ...)` must be updated to pass `Path`:

| File | Location |
|------|----------|
| `VocabularyResource.java` | `addDefinition()` — parse from `request.path()` |
| `VocabularyScenario.java` (examples module) | Three `addDefinition(vocab.id, "leave/...", ...)` calls |

## Testing

- **`PathAttributeConverter`** — unit test: null handling, round-trip `parse(value())` identity
- **`LabelVocabularyService.isDeclared(Path)` / `addDefinition(UUID, Path, ...)`** — update
  existing unit tests for new signatures
- **`findByPath(Path)` with Hibernate** — integration test in `LabelEndpointTest`: persist a
  definition, look it up by `Path`, assert found. This exercises the converter in a live JPA stack.
- **VocabularyResource wildcard validation** — integration test: POST with `"path": "legal/*"`
  returns 400
- **VocabularyResource invalid path** — integration test: POST with blank/empty path returns 400

## #190 Evaluation Finding

`SettingsScope` is `record(Path scope, Instant effectiveAt)`. The `Instant` carries time-based
preference override semantics irrelevant to vocabulary visibility. Direct unification of
`VocabularyScope` with `SettingsScope` is wrong.

The right direction is replacing the ordinal-based `VocabularyScope` enum with `Path`-based scope
(using `isAncestorOf` instead of ordinal comparison), but that requires a schema migration
(`label_vocabulary.scope` enum → varchar path column) and REST API change. M-scale — tracked
as a follow-up issue. Close #190 with an evaluation comment.
