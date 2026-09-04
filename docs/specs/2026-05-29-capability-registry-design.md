# Capability Registry Design

**Issue:** casehubio/work#220
**Branch:** issue-220-capability-registry
**Date:** 2026-05-29 (revised after two spec review rounds)

## Problem

Engine `CaseDefinition` capability names and worker capability tags in `WorkerCandidate.capabilities` are both `String`, matched by `WorkBroker` using exact case-sensitive equality. There is no shared vocabulary, no format enforcement, and no validation. A mismatch (`"legal-review"` vs `"legal_review"`) causes silent routing failure — `WorkOrchestrator` throws `IllegalStateException("No worker available")` with no diagnostic pointing at the vocabulary gap.

## Scope Decision: Capability Value Type

Rather than adding validation infrastructure on top of the raw-String foundation, this issue introduces a `Capability` value type. Format errors (underscore instead of hyphen, uppercase) are caught at construction time rather than at routing time — the entire format-mismatch bug class is eliminated at the type level. This is a **breaking change** to `WorkerCandidate` and `SelectionContext`, acceptable at 0.2-SNAPSHOT.

**Wire format and DB column stay as comma-separated String** — no Flyway migration, no REST API break.

## New Types in `casehub-work-api`

### `Capability` record

```java
public record Capability(String id) {
    public Capability {
        Objects.requireNonNull(id);
        if (!id.matches("[a-z][a-z0-9]*(-[a-z0-9]+)*"))
            throw new MalformedCapabilityException(id);
    }
    public static Capability of(String id) { return new Capability(id); }
}
```

Format contract: **lowercase kebab-case only** (`[a-z][a-z0-9]*(-[a-z0-9]+)*`). `"legal-review"` is valid; `"Legal-Review"`, `"legal_review"`, `"legal-"`, and `"a--b"` are not. No trailing hyphen, no consecutive hyphens.

### `MalformedCapabilityException` (unchecked)

Thrown by the `Capability` constructor. Maps to 400:

```json
{"error": "MALFORMED_CAPABILITY", "values": ["Legal-Review"],
 "message": "Capability id must be lowercase kebab-case"}
```

### `WorkCapabilities`

```java
public final class WorkCapabilities {
    public static final Capability LEGAL_REVIEW      = Capability.of("legal-review");
    public static final Capability CONTRACT_ANALYSIS = Capability.of("contract-analysis");
    // ... curated by platform team; examples import FROM here, never the reverse
    private WorkCapabilities() {}
}
```

Contents are curated platform vocabulary. Examples and test code import from this class.

### `CapabilityRegistry` SPI

```java
public interface CapabilityRegistry {
    /** Known capability vocabulary. Empty set means unmanaged. */
    Set<Capability> capabilities();

    /**
     * Returns true if {@code tag} is a known capability.
     *
     * <p>Matching is exact and case-sensitive — {@code "legal-review"} and
     * {@code "Legal-Review"} are distinct (the Capability constructor enforces
     * lowercase kebab-case, so the latter is rejected before reaching this method).
     *
     * <p>Override when direct lookup is more efficient than loading the full
     * capabilities set (e.g. database-backed registry with SELECT EXISTS).
     * Never back this method with a static field — that silently bypasses
     * subclass capability sets (GE-20260511-a5f47d).
     */
    default boolean isKnown(Capability tag) { return capabilities().contains(tag); }
}
```

`validationMode` is **not** on the registry — policy is a deployment concern, not a vocabulary concern (see `ValidationMode` below).

### `ValidationMode`

```java
public enum ValidationMode { STRICT, WARN, PERMISSIVE }
```

Controlled by config property:

```properties
casehub.work.capability-validation=PERMISSIVE   # default — no enforcement
```

### `UnknownCapabilityException` (unchecked)

Thrown by `CapabilityValidator` in STRICT mode. Maps to 400:

```json
{"error": "UNKNOWN_CAPABILITY", "values": ["audit-sign", "risk-assess"],
 "message": "Unknown capabilities: [audit-sign, risk-assess]"}
```

All unknown tags appear in the response (not just the first).

## Default Implementation (`casehub-work-core`)

### `PermissiveCapabilityRegistry`

```java
@DefaultBean
@ApplicationScoped
public class PermissiveCapabilityRegistry implements CapabilityRegistry {
    @Override public Set<Capability> capabilities() { return Set.of(); }
}
```

`@DefaultBean` — any application-scoped `CapabilityRegistry` in the deploying app displaces it. Existing deployments see no behaviour change.

## Optional Registry Implementations (`casehub-work-core`)

### `WorkCapabilitiesRegistry`

```java
@ApplicationScoped
public class WorkCapabilitiesRegistry implements CapabilityRegistry {
    private static final Set<Capability> KNOWN = Set.of(
        WorkCapabilities.LEGAL_REVIEW,
        WorkCapabilities.CONTRACT_ANALYSIS
        // ...
    );
    @Override public Set<Capability> capabilities() { return KNOWN; }
}
```

**Not `@DefaultBean`** — opt-in. Teams deploy it as `@Alternative @Priority(1)` when they want platform-vocabulary enforcement. Bridges the constants class and the registry SPI: teams enabling STRICT mode get a working starting point without implementing a custom registry.

## Validator Service (`casehub-work-core`)

```java
@ApplicationScoped
public class CapabilityValidator {

    @ConfigProperty(name = "casehub.work.capability-validation",
                    defaultValue = "PERMISSIVE")
    ValidationMode validationMode;

    @Inject CapabilityRegistry registry;

    /**
     * No-op if mode is PERMISSIVE or the set is empty.
     * Precondition: {@code capabilities} is non-null — use {@code parseCapabilities()} or
     * {@code parseCapabilitiesLenient()} to produce the argument.
     */
    public void validate(Set<Capability> capabilities) {
        if (validationMode == ValidationMode.PERMISSIVE || capabilities.isEmpty()) return;
        List<Capability> unknown = capabilities.stream()
                .filter(c -> !registry.isKnown(c))
                .toList();
        if (unknown.isEmpty()) return;
        if (validationMode == ValidationMode.STRICT) {
            throw new UnknownCapabilityException(unknown);
        } else {
            Log.warnf("WorkItem references unregistered capabilities: %s", unknown);
        }
    }
}
```

Accepts `Set<Capability>` — caller owns the parse from the comma-separated String. PERMISSIVE short-circuits immediately.

## Breaking Changes to Existing SPIs

### `WorkerCandidate` (`api/`)

```java
// Before
public record WorkerCandidate(String id, Set<String> capabilities, int activeWorkItemCount)

// After
public record WorkerCandidate(String id, Set<Capability> capabilities, int activeWorkItemCount)
```

`WorkerRegistry` implementations return `Set<Capability>` for the capabilities field.

### `SelectionContext` (`api/`)

```java
// Before
public record SelectionContext(..., String requiredCapabilities, ...)

// After
public record SelectionContext(..., Set<Capability> requiredCapabilities, ...)
```

Callers constructing `SelectionContext` parse the comma-separated String to `Set<Capability>` at the boundary.

### `WorkBroker.filterByCapabilities()` (`core/`)

No longer parses a comma-separated string — operates directly on `Set<Capability>`:

```java
private List<WorkerCandidate> filterByCapabilities(SelectionContext ctx, List<WorkerCandidate> candidates) {
    if (ctx.requiredCapabilities().isEmpty()) return candidates;
    return candidates.stream()
            .filter(c -> c.capabilities().containsAll(ctx.requiredCapabilities()))
            .toList();
}
```

## Creation-Path Wiring (`runtime`)

### Dual parse modes (strict-on-write, lenient-on-read)

The `Capability` value type is new — existing `WorkItemEntity` DB rows may contain capability strings that violate the format contract (underscores, uppercase). Strict parsing everywhere would throw `MalformedCapabilityException` inside `@Transactional` routing operations for those rows — a production incident at deployment time.

Two parse modes address this:

**`CapabilityParser.parse(String raw)`** — strict. Throws `MalformedCapabilityException` on any malformed token. Used in `WorkItemService.create()` for new input — format errors surfaced as 400 at creation time.

**`CapabilityParser.parseLenient(String raw)`** — lenient. Skips malformed tokens with a `WARN` log entry. Used in `WorkItemAssignmentService` when building `SelectionContext` from DB rows — routing of existing WorkItems is protected; bad tokens are excluded from the required set rather than blowing up the transaction.

This is intentional: the format contract is enforced on new writes. Existing data is served leniently until operators clean it up (e.g., `UPDATE work_item SET required_capabilities = REPLACE(required_capabilities, '_', '-') WHERE required_capabilities LIKE '%\_%'`). No Flyway migration required, but operators should run cleanup before enabling STRICT mode.

### `CapabilityParser` (`runtime/service/`)

Standalone package-private utility class. Keeps both callers (`WorkItemService`, `WorkItemAssignmentService`) decoupled from each other — `WorkItemService` constructor-injects `WorkItemAssignmentService`, so placing parse methods on either service would create a circular class dependency.

```java
final class CapabilityParser {
    private CapabilityParser() {}

    /** Strict parse — throws MalformedCapabilityException on any malformed token. */
    static Set<Capability> parse(String raw) {
        if (raw == null || raw.isBlank()) return Set.of();
        return Arrays.stream(raw.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .map(Capability::of)
                .collect(Collectors.toSet());
    }

    /** Lenient parse — skips malformed tokens with a WARN log. */
    static Set<Capability> parseLenient(String raw) {
        if (raw == null || raw.isBlank()) return Set.of();
        return Arrays.stream(raw.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .flatMap(s -> {
                    try { return Stream.of(Capability.of(s)); }
                    catch (MalformedCapabilityException e) {
                        Log.warnf("Skipping malformed capability in DB row: %s", s);
                        return Stream.empty();
                    }
                })
                .collect(Collectors.toSet());
    }
}
```

### `WorkItemService.create()`

`CapabilityValidator` is a primary dependency — constructor-injected:

```java
public WorkItemService(..., CapabilityValidator capabilityValidator) { ... }

@Transactional
public WorkItem create(final WorkItemCreateRequest request) {
    capabilityValidator.validate(CapabilityParser.parse(request.requiredCapabilities));
    // ... existing entity construction unchanged
}
```

`WorkItemAssignmentService` calls `CapabilityParser.parseLenient()` when building `SelectionContext` from a `WorkItemEntity` entity.

### Immutability

`requiredCapabilities` is **write-once**. `WorkItemService` sets it only in `create()` and `clone()`. There is no update path — no second validation site needed.

### `clone()` + STRICT mode

`clone()` calls `create()` so the same validation applies. A WorkItem cloned in PERMISSIVE mode (before a registry was deployed) may have capabilities not in a later STRICT registry — cloning it will fail. This is intentional: the registry defines what the system currently accepts. Operators enabling STRICT mode must ensure all existing capability strings are registered before switching. This is a known migration responsibility, not a bug.

### Exception Mappers (`runtime`)

```java
@Provider
public class MalformedCapabilityExceptionMapper implements ExceptionMapper<MalformedCapabilityException> {
    public Response toResponse(MalformedCapabilityException e) {
        return Response.status(400)
            .entity(Map.of("error", "MALFORMED_CAPABILITY",
                           "values", e.badValues(),
                           "message", "Capability id must be lowercase kebab-case"))
            .build();
    }
}

@Provider
public class UnknownCapabilityExceptionMapper implements ExceptionMapper<UnknownCapabilityException> {
    public Response toResponse(UnknownCapabilityException e) {
        return Response.status(400)
            .entity(Map.of("error", "UNKNOWN_CAPABILITY",
                           "values", e.unknownIds(),
                           "message", "Unknown capabilities: " + e.unknownIds()))
            .build();
    }
}
```

`e.badValues()` and `e.unknownIds()` return `List<String>` (the String ids) for JSON serialisation.

## Out of Scope

- **Scoping / per-domain policies** — the `CapabilityRegistry` SPI is a CDI `@Alternative`; teams can compose sub-registries or scope by prefix without changes to the SPI contract.
- **Startup validation of worker-declared capabilities** — `WorkerCandidate.capabilities` is resolved lazily by `WorkerRegistry`; no static worker capability declaration mechanism exists. Separate issue when needed.

## Testing

| Test | Module | Notes |
|------|--------|-------|
| `CapabilityTest` | `api` | Valid kebab-case accepted; uppercase, underscore, trailing hyphen, double hyphen, leading digit all rejected; `MalformedCapabilityException` message includes the bad value |
| `CapabilityRegistryTest` | `api` | `isKnown()` default delegates to `capabilities()`; subclass overriding only `capabilities()` gets correct `isKnown()` (GE guard) |
| `PermissiveCapabilityRegistryTest` | `core` | `capabilities()` → empty; `validate(anySet)` → no-op |
| `WorkCapabilitiesRegistryTest` | `core` | Returns `WorkCapabilities` constants; `isKnown(WorkCapabilities.LEGAL_REVIEW)` → true |
| `CapabilityValidatorTest` | `core` | PERMISSIVE → always passes; WARN + unknown → logs, no throw; STRICT + known → passes; STRICT + unknown → throws with all unknown values named; null/empty set → always passes; comma edge cases (leading/trailing spaces, double commas) handled by caller's `parseCapabilities()` |
| `WorkItemServiceCapabilityIT` | `runtime` | `@QuarkusTest` with `@Alternative @Priority(1)` STRICT registry containing `LEGAL_REVIEW`; POST with `requiredCapabilities=legal-review` → 201; POST with `requiredCapabilities=legal_review` → 400 MALFORMED_CAPABILITY; POST with `requiredCapabilities=unknown-cap` → 400 UNKNOWN_CAPABILITY; all unknown values present in 400 body; clone of STRICT-rejected capability → 400 |
| `WorkBrokerTest` | `core` | `filterByCapabilities` uses `Set<Capability>` directly; candidate missing one required capability excluded; empty required set → all candidates pass |
| `CapabilityParserTest` | `runtime` | `parse()`: leading/trailing spaces trimmed; double commas skipped; valid multi-token string → correct Set; single bad token throws `MalformedCapabilityException`. `parseLenient()`: bad token skipped + warn logged; valid tokens retained in result |
