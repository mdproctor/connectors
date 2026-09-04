# WorkItemTemplateService — callerRef overload
*2026-05-12*

## Context

`WorkItemTemplateService.instantiate()` hardcodes `callerRef = null` in `toCreateRequest()`.
The `casehub-engine-work-adapter` (`HumanTaskScheduleHandler`, engine#245) needs to set
`callerRef = CallerRef.encode(caseId, planItemId)` when instantiating a template on behalf of a
case binding, so the resulting `WorkItemLifecycleEvent` routes back to the correct `PlanItem`.

Refs casehubio/work#165. Out-of-scope gap captured in casehubio/work#166.

---

## Design

Pure addition — two new overloads, zero existing callers change.

### `toCreateRequest()` — static, unit-testable

Existing 4-arg overload delegates to new 5-arg:

```java
public static WorkItemCreateRequest toCreateRequest(
        WorkItemTemplate template, String titleOverride,
        String assigneeIdOverride, String createdBy) {
    return toCreateRequest(template, titleOverride, assigneeIdOverride, createdBy, null);
}

public static WorkItemCreateRequest toCreateRequest(
        WorkItemTemplate template, String titleOverride,
        String assigneeIdOverride, String createdBy,
        String callerRef) {
    // same body as current; callerRef replaces the hardcoded null
}
```

All 8 existing `WorkItemTemplateServiceTest` unit tests call the 4-arg form — unchanged.

### `instantiate()` — CDI, transactional

Existing 4-arg overload delegates to new 5-arg:

```java
@Transactional
public WorkItem instantiate(WorkItemTemplate template, String titleOverride,
        String assigneeIdOverride, String createdBy) {
    return instantiate(template, titleOverride, assigneeIdOverride, createdBy, null);
}

@Transactional
public WorkItem instantiate(WorkItemTemplate template, String titleOverride,
        String assigneeIdOverride, String createdBy, String callerRef) {
    if (template.instanceCount != null) {
        if (callerRef != null) {
            LOG.warnf("callerRef ignored for multi-instance template %s — see casehubio/work#166",
                template.id);
        }
        return multiInstanceSpawnService.get().createGroup(template, titleOverride, createdBy);
    }
    final WorkItemCreateRequest request =
        toCreateRequest(template, titleOverride, assigneeIdOverride, createdBy, callerRef);
    WorkItem workItem = workItemService.create(request);
    final List<WorkItemLabel> labels = parseLabels(template);
    for (final WorkItemLabel label : labels) {
        workItem = workItemService.addLabel(workItem.id, label.path, label.appliedBy);
    }
    return workItem;
}
```

All 13 existing test callers and 2 production callers (`WorkItemScheduleService`,
`WorkItemTemplateResource`) use the 4-arg form — unchanged.

### Out-of-scope (deliberate)

- **REST endpoint** — `POST /workitems/templates/{id}/instantiate` does not expose `callerRef`.
  callerRef is an internal engine routing key, not a human-initiated field.
- **Multi-instance + callerRef** — tracked in casehubio/work#166. Silently warned and ignored
  in this implementation.

---

## Tests

All new tests in `WorkItemTemplateServiceTest`:

| Test | Type | What it asserts |
|---|---|---|
| `toCreateRequest_setsCallerRef_whenProvided` | unit | 5-arg form → `req.callerRef()` equals supplied value |
| `toCreateRequest_callerRefNull_whenNotProvided` | unit | 4-arg delegate → `req.callerRef()` is null |
| `instantiate_setsCallerRef_onCreatedWorkItem` | `@QuarkusTest` | 5-arg instantiate → `workItem.callerRef` equals supplied value |
| `instantiate_multiInstanceTemplate_logsWarningAndIgnoresCallerRef` | `@QuarkusTest` | multi-instance template + non-null callerRef → parent created, `callerRef` absent on parent |

Existing 11 `toCreateRequest` unit tests and 13 `instantiate` integration tests: unchanged.
