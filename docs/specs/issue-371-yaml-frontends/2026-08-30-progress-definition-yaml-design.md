# Progress Definition YAML — Design Spec

**Date:** 2026-08-30
**Status:** Draft
**Issue:** casehubio/work#373
**Decisions:** [decisions.md](decisions.md) D7-D8

## Motivation

`ProgressDefinition` (structured progress stages and transitions) is currently programmatic — callers build a `StepDefinition` list as `JsonNode` and pass it inline via `ProgressCreateRequest.definition()`. Tutorials need declarative progress definitions without Java.

## YAML Format (D7)

Classpath resource: `META-INF/work-progress-definitions.yaml`

```yaml
progressDefinitions:
  - name: document-review
    shapeType: step
    stages:
      - name: received
      - name: in-review
      - name: approved
      - name: rejected
        optional: true
    transitions:
      received: [in-review]
      in-review: [approved, rejected, received]
    rollbackPolicy: revert-to-previous
    visualisationMode: linear

  - name: simple-percentage
    shapeType: percentage
```

### Fields

| Field | Type | Required | Default | Notes |
|-------|------|----------|---------|-------|
| `name` | String | yes | — | Unique identifier for the definition |
| `shapeType` | String | no | `step` if `stages` present, else required | Shape type discriminator |
| `stages` | List | no | — | Maps to `StepDefinition` records. Each entry: `name` (required), `optional` (default false), `dependsOn` (list), `condition` (string) |
| `transitions` | Map<String, List<String>> | no | — | Allowed state transitions. Stored in definition JSON alongside step definitions |
| `rollbackPolicy` | String | no | — | Default rollback policy for instances created from this definition |
| `visualisationMode` | String | no | — | Default visualisation mode |

### Stage-to-StepDefinition Mapping

Each stage entry maps to a `StepDefinition` record:

```yaml
stages:
  - name: in-review
    optional: false
    dependsOn: [received]
    condition: ".assignee != null"
```

Maps to:
```java
new StepDefinition("in-review", false, List.of("received"), ".assignee != null")
```

When only `name` is provided (short form), the stage maps to `new StepDefinition(name, false, List.of(), null)`.

### Transitions Storage

The `transitions` map is stored in the definition JSON as a sibling to the step definitions array:

```json
{
  "steps": [
    {"name": "received", "optional": false, "dependsOn": [], "condition": null},
    {"name": "in-review", "optional": false, "dependsOn": [], "condition": null}
  ],
  "transitions": {
    "received": ["in-review"],
    "in-review": ["approved", "rejected", "received"]
  }
}
```

The transitions map is informational in this iteration — it's stored in the definition JSON but not enforced by `ProgressService` state transitions. Enforcement is deferred (see Deferred section). The map is available for consumers (UI, validation hooks) to use for rendering and client-side validation.

### Validation at Load Time

- `name` must be non-null and non-blank
- `shapeType` must be non-null when no `stages` are present
- Stage names must be unique within a definition
- Transition keys must reference defined stage names
- Transition targets must reference defined stage names
- `StepDefinitionValidator` validates the generated step definitions (cycle detection, at least one required step)
- Duplicate definition names across YAML resources: WARN log, last-writer-wins

### Variable Interpolation

Following the `WorkItemTemplateYamlLoader` pattern, string values support `${env.VAR}` and `${sys.PROP}` interpolation.

## Changes

### ProgressDefinition — Record (progress-api, D8)

New record in `io.casehub.work.progress`:

```java
public record ProgressDefinition(
    String name,
    String shapeType,
    JsonNode definition,
    String rollbackPolicy,
    String visualisationMode
) {
    public ProgressDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("ProgressDefinition name is required");
        }
        if (shapeType == null || shapeType.isBlank()) {
            throw new IllegalArgumentException("ProgressDefinition shapeType is required");
        }
    }
}
```

### ProgressDefinitionRegistry — In-Memory Registry (progress-api, D8)

New class in `io.casehub.work.progress`:

```java
@ApplicationScoped
public class ProgressDefinitionRegistry {

    private final Map<String, ProgressDefinition> definitions = new ConcurrentHashMap<>();

    public void register(ProgressDefinition definition) {
        ProgressDefinition prev = definitions.put(definition.name(), definition);
        if (prev != null) {
            LOG.warnf("ProgressDefinition '%s' overwritten", definition.name());
        }
    }

    public Optional<ProgressDefinition> get(String name) {
        return Optional.ofNullable(definitions.get(name));
    }

    public Collection<ProgressDefinition> getAll() {
        return Collections.unmodifiableCollection(definitions.values());
    }
}
```

### ProgressDefinitionYamlLoader — Classpath Loader (progress-runtime, D8)

New `@ApplicationScoped @Startup` bean in `io.casehub.work.progress.runtime.service`:

```java
@ApplicationScoped
@Startup
public class ProgressDefinitionYamlLoader {

    private static final String RESOURCE_PATH = "META-INF/work-progress-definitions.yaml";

    @Inject ProgressDefinitionRegistry registry;

    @PostConstruct
    void load() {
        // Classpath scan, parse YAML, validate, register into registry
        // Follows WorkItemTemplateYamlLoader pattern exactly
    }
}
```

**Classpath scanning:** Uses `Thread.currentThread().getContextClassLoader().getResources(RESOURCE_PATH)`. Multiple JARs can contribute definitions. Duplicate names: WARN log, last-writer-wins.

**YAML-to-definition mapping:**
1. Parse YAML stages into `StepDefinition` records
2. Validate via `StepDefinitionValidator`
3. Build definition JSON (steps array + transitions map)
4. Construct `ProgressDefinition` with resolved shapeType, definition JSON, and optional defaults
5. Register into `ProgressDefinitionRegistry`

**Error handling:** Malformed YAML or invalid definitions throw at startup — fail fast.

### ProgressCreateRequest — Optional definitionName

Add optional `definitionName` field to `ProgressCreateRequest`:

```java
public record ProgressCreateRequest(
    String tenancyId,
    String scopeType,
    String scopeId,
    String shapeType,           // nullable when definitionName is set
    JsonNode state,
    UUID parentProgressId,
    String rollupStrategyId,
    JsonNode definition,        // nullable when definitionName is set
    String rollbackPolicy,
    String visualisationMode,
    String definitionName       // NEW — optional, references ProgressDefinition by name
) {
    public ProgressCreateRequest {
        // existing validations...
        // shapeType validation relaxed when definitionName is set
    }
}
```

### ProgressService.create() — Definition Resolution

When `request.definitionName()` is set:

```java
if (request.definitionName() != null) {
    ProgressDefinition def = registry.get(request.definitionName())
        .orElseThrow(() -> new IllegalArgumentException(
            "Unknown progress definition: " + request.definitionName()));

    // Template fields fill gaps in the request — request values take precedence
    shapeType = request.shapeType() != null ? request.shapeType() : def.shapeType();
    definition = request.definition() != null ? request.definition() : def.definition();
    rollbackPolicy = request.rollbackPolicy() != null ? request.rollbackPolicy() : def.rollbackPolicy();
    visualisationMode = request.visualisationMode() != null ? request.visualisationMode() : def.visualisationMode();
}
```

Request fields override template defaults. This allows per-instance customization while using the template as a base.

## Test Fixtures

### Unit Tests

- **`ProgressDefinitionTest`** — record construction, validation (null name, null shapeType throw)
- **`ProgressDefinitionRegistryTest`** — register and get, duplicate-name overwrite with warning, getAll, get unknown returns empty
- **`ProgressDefinitionYamlLoaderTest`** — load from classpath resource; parse stages to StepDefinitions; parse transitions map; shapeType defaults to `step` when stages present; validation catches unknown transition targets; variable interpolation
- **`ProgressServiceTest`** (existing, extend) — definitionName resolution fills shapeType/definition from template; request fields override template defaults; unknown definitionName throws

## Deferred

### Transition enforcement (#374)

The `transitions` map is stored in the definition JSON but not enforced by `ProgressService.updateState()` or step operations. Adding transition validation (reject state changes not in the transitions map) is a separate concern that requires deciding on enforcement semantics (strict vs permissive, error vs warning).

### REST API for definition management (#375)

Named definitions are currently loaded from classpath YAML only. A REST API for runtime CRUD of definitions is deferred.

## References

- `progress-api/src/main/java/io/casehub/work/progress/ProgressInstance.java` — existing record with shapeType + definition
- `progress-api/src/main/java/io/casehub/work/progress/ProgressCreateRequest.java` — creation request record
- `progress-api/src/main/java/io/casehub/work/progress/StepDefinition.java` — step definition record
- `progress-core/src/main/java/io/casehub/work/progress/validation/StepShapeValidator.java` — step shape validation
- `progress-core/src/main/java/io/casehub/work/progress/validation/StepDefinitionValidator.java` — step definition validation
- `progress-runtime/src/main/java/io/casehub/work/progress/runtime/service/ProgressService.java:66` — create method
- `runtime/src/main/java/io/casehub/work/runtime/service/WorkItemTemplateYamlLoader.java` — YAML loading pattern
- `specs/2026-07-17-progress-model-design.md` — progress model design
