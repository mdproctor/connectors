# WorkItemTemplate YAML Declaration Format — Design Spec

**Date:** 2026-08-29
**Status:** Draft
**Issue:** casehubio/work#370
**Decisions:** [decisions.md](decisions.md) D1-D7

## Motivation

WorkItemTemplates are the primary reuse abstraction for WorkItem configuration — title, candidates, deadlines, escalation, outcomes, schemas. Currently they're REST-only: created via API, not declarable. For YAML-only tutorials and deployments, templates need a YAML declaration format so a complete working app can be built from YAML alone.

## YAML Surface

Templates are declared in standalone classpath files at `META-INF/work-templates.yaml` (D1):

```yaml
workItemTemplates:
  - name: irb-72h-review
    description: "72-hour IRB ethics review with escalation"
    priority: URGENT
    candidateGroups: "ethics-committee"
    candidateUsers: "dr-smith,dr-jones"
    requiredCapabilities: "clinical-review,ethics"
    defaultExpiryHours: 72
    defaultClaimHours: 24
    defaultPayload: '{"formType": "irb-ethics"}'
    labelPaths: "clinical/irb,compliance/required"
    typePaths: "irb,ethics"
    outcomes: '[{"name":"APPROVED"},{"name":"REJECTED"},{"name":"DEFERRED"}]'
    excludedUsers: "trial-pi"
    scope: "casehubio/clinical/adverse-event"
    inputDataSchema: '{"type":"object","properties":{"protocolId":{"type":"string"}}}'
    outputDataSchema: '{"type":"object","properties":{"decision":{"type":"string"}}}'
    instanceCount: 3
    requiredCount: 2
    onThresholdReached: KEEP
    assignmentStrategy: "round-robin"

  - name: simple-approval
    description: "Basic approval task"
    candidateGroups: "approvers"
    defaultExpiryHours: 48
    outcomes: '[{"name":"APPROVED"},{"name":"REJECTED"}]'
```

CaseDefinition references templates by name via existing `humanTask.templateRef:` (D4):

```yaml
bindings:
  - name: ethics-review
    humanTask:
      templateRef: irb-72h-review
    on:
      contextChange:
        filter: ".readyForReview == true"
```

All template fields are optional. Field names match `WorkItemTemplate` entity field names exactly — no mapping layer.

## Architecture

### Loading mechanism (D2)

A `@Startup @ApplicationScoped` bean (`WorkItemTemplateYamlLoader`) reads `META-INF/work-templates.yaml` from the classpath at `@PostConstruct` and upserts each template into `WorkItemTemplateStore`. This follows the `EndpointConfigLoader` pattern proven in the platform.

```java
@ApplicationScoped
@Startup
public class WorkItemTemplateYamlLoader {

    @Inject WorkItemTemplateStore templateStore;

    @PostConstruct
    void loadTemplates() {
        List<URL> resources = discoverResources("META-INF/work-templates.yaml");
        for (URL resource : resources) {
            List<WorkItemTemplate> templates = parseYaml(resource);
            for (WorkItemTemplate template : templates) {
                template.tenancyId = TenancyConstants.DEFAULT_TENANT_ID;
                upsertByName(template);
            }
        }
    }
}
```

The deployment module registers a `@BuildStep` that adds `NativeImageResourcePatternsBuildItem` for `META-INF/work-templates.yaml` — ensuring the YAML file is included in native images.

### Tenancy (D6)

The loader runs at `@PostConstruct` — no request context exists. Templates are pre-stamped with `TenancyConstants.DEFAULT_TENANT_ID` before calling `WorkItemTemplateStore.put()`. This avoids `ContextNotActiveException` from `@RequestScoped CurrentPrincipal` in OIDC deployments. The upsert collision check uses a direct Panache query with explicit tenancyId rather than `getByName()` (which delegates to `CurrentPrincipal`).

### Collision handling (D5)

On name collision with an existing DB template:
1. Log WARN with YAML file path and existing template ID
2. Overwrite (upsert)

YAML is the deployment-time source of truth. REST customizations between boots are overwritten on redeploy — same mental model as Quarkus config properties.

### Mutability (D7)

After loading, YAML templates are regular mutable DB entities. No mutability boundary between YAML-declared and REST-created templates. REST updates, deletes, and patches apply normally. Redeployment re-upserts from YAML source.

### Resolution path

No changes to the existing resolution path. `WorkItemTemplateService.findByRef(name)` queries the store — which now contains both REST-created and YAML-loaded templates. `HumanTaskScheduleHandler` calls `findByRef(templateRef)` as before.

### Variable resolution (D3)

Load-time interpolation of environment variables and system properties via `${env.VAR_NAME}` and `${sys.property.name}` patterns (same approach as `EndpointConfigLoader.interpolate()`). Per-instantiation context parameterization is deferred.

```yaml
workItemTemplates:
  - name: team-review
    candidateGroups: "${env.REVIEW_TEAM}"
    defaultExpiryHours: ${sys.review.expiry.hours}
```

## Changes

### New: WorkItemTemplateYamlLoader (runtime module)

`runtime/src/main/java/io/casehub/work/runtime/service/WorkItemTemplateYamlLoader.java`

- `@ApplicationScoped @Startup`
- `@PostConstruct void loadTemplates()` — discovers and parses YAML, upserts templates
- `discoverResources(String path)` — scans classpath for all matching resources (multiple JARs may contribute)
- `parseYaml(URL resource)` — Jackson YAML parser, maps to `WorkItemTemplate` entities
- `interpolate(String value)` — `${env.X}` and `${sys.X}` resolution
- `upsertByName(WorkItemTemplate template)` — direct Panache query by name + tenancyId, update or persist

### New: NativeImageResourcePatternsBuildItem (deployment module)

`deployment/src/main/java/io/casehub/work/deployment/WorkItemsProcessor.java` — add `@BuildStep` that registers `META-INF/work-templates.yaml` for native image inclusion.

### Existing: WorkItemTemplateStore — no changes

`put()` already handles create-or-update. `findByRef()` already resolves by name. No changes needed.

### Existing: WorkItemTemplateService — no changes

`mergeRequestWithTemplate()` already handles template + request merging. `findByRef()` already resolves by UUID or name. No changes needed.

### Existing: HumanTaskScheduleHandler — no changes

Already calls `findByRef(templateRef)`. YAML-loaded templates are indistinguishable from REST-created ones.

### Test fixtures

- `WorkItemTemplateYamlLoaderTest` — unit test: parse valid YAML, verify all fields map correctly to `WorkItemTemplate` entity
- `WorkItemTemplateYamlLoaderTest` — upsert collision: verify WARN log on name collision, verify overwrite
- `WorkItemTemplateYamlLoaderTest` — variable interpolation: `${env.X}` and `${sys.X}` resolve correctly
- `WorkItemTemplateYamlLoaderTest` — malformed YAML: verify startup fails with clear error message
- Example YAML fixture: `src/test/resources/META-INF/work-templates.yaml` with representative templates
- Integration test: CaseDefinition `templateRef:` resolves to YAML-loaded template

## Deferred

- **Per-instantiation variable resolution** (D3) — `${case.context.field}` expressions resolved at WorkItem creation time. Requires platform expression infrastructure, separate issue.
- **Inline templates in CaseDefinition** (D1) — crosses engine/work boundary. Would require engine schema changes or a shared template schema module.
- **Multi-tenant template loading** (D6) — per-tenant YAML files or per-template tenancyId field.
- **forEach expansion** — yaml-core `ForEachExpander` for template families (e.g., generate N templates from a CSV data source).

## References

- `runtime/src/main/java/io/casehub/work/runtime/model/WorkItemTemplate.java` — canonical model (25 fields)
- `runtime/src/main/java/io/casehub/work/runtime/service/WorkItemTemplateService.java` — findByRef, mergeRequestWithTemplate
- `runtime/src/main/java/io/casehub/work/runtime/repository/WorkItemTemplateStore.java` — store interface
- `engine-adapter/src/main/java/io/casehub/work/engine/HumanTaskScheduleHandler.java` — templateRef resolution
- `platform/yaml-core/src/main/java/io/casehub/yaml/core/resolver/VariableResolver.java` — yaml-core reference (deferred dependency)
- `EndpointConfigLoader` (platform endpoints-config) — startup-loader pattern precedent
- casehubio/parent#247 — shared YAML core
- casehubio/work#362 — escalation/skillMatch YAML (prior art)
