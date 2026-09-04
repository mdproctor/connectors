# CloudEvent CREATE — Full-Request WorkItem Creation

**Issue:** casehubio/work#299
**Date:** 2026-08-23
**Status:** Approved

## Problem

casehub-work has two inbound WorkItem creation paths:

1. **REST API** — synchronous, full `WorkItemCreateRequest` control
2. **CloudEvent `REQUESTED`** (#172) — async, template-only, opaque payload

The engine-adapter creates WorkItems via in-JVM `WorkItemCreator.create()` triggered by Vert.x event bus (`HumanTaskScheduleEvent`). This requires the engine and work to be co-located in the same Quarkus application.

There is no async path for creating WorkItems with full request control — candidateGroups, candidateScores, routingExperiences, outcomes, inline mode (no template), custom callerRef. External services that want these capabilities must use the synchronous REST API, coupling them to casehub-work's availability.

## Design

Add a second inbound CloudEvent type `io.casehub.work.workitem.create` whose data payload maps directly to `WorkItemCreateRequest` fields. This enables:

- **Distributed HumanTask** — engine emits a CloudEvent instead of a Vert.x event; a remote work service receives it and creates the WorkItem
- **Qhorus agent bridge** — agents request human work via CloudEvent instead of MCP tools
- **Any external system** — async, durable WorkItem creation with full field control

The type is general, not HumanTask-specific. `WorkItemCreateRequest` already carries all HumanTask fields generically (`candidateScores`, `routingExperiences`, `payloadTypeName`, outcomes). The engine-specific concerns in `HumanTaskScheduleHandler` (BlackboardRegistry lookup, PlanItem validation, PlanItem state persistence) belong on the engine side — they are not part of the WorkItem creation contract.

## CloudEvent Contract

### Type

`io.casehub.work.workitem.create`

Constant: `WorkCloudEventTypes.CREATE`

### Envelope

| CloudEvent field | Value |
|---|---|
| `specversion` | `1.0` |
| `type` | `io.casehub.work.workitem.create` |
| `source` | Caller-defined URI (e.g., `/engine/cases/{caseId}`, `/qhorus/channels/{channelId}`) |
| `id` | Caller-generated UUID — transport-level identity |
| `datacontenttype` | `application/json` |
| `tenancyid` ext | Required — tenant routing per `async-event-tenant-context-propagation` protocol |
| `data` | JSON object mapping to `WorkItemCreateRequest` fields |

No other extensions are required. `templateid` is NOT an extension (unlike `REQUESTED`) — if template-based creation is wanted, `templateId` goes in the data payload.

### Naming Convention

The type `io.casehub.work.workitem.create` (imperative, command) is one letter from the lifecycle type `io.casehub.work.workitem.created` (past-tense, event). This is deliberate: CloudEvents follow command-vs-event naming — imperative for inbound requests, past-tense for outbound facts. The existing `REQUESTED` follows the same convention. A drift prevention test asserts `CREATE` is not in the `WorkEventType` lifecycle enum (same pattern as the existing `REQUESTED` assertion).

### Data Fields

The data payload maps to `WorkItemCreateRequest` fields using the same camelCase naming. All fields are optional unless noted:

| Field | Type | Notes |
|---|---|---|
| `title` | string | **Required** when `templateId` absent |
| `description` | string | |
| `types` | string[] | |
| `formKey` | string | |
| `priority` | string | `LOW`, `MEDIUM`, `HIGH`, `URGENT` |
| `assigneeId` | string | |
| `candidateGroups` | string | CSV (e.g., `"legal,compliance"`) |
| `candidateUsers` | string | CSV |
| `requiredCapabilities` | string | CSV |
| `payload` | string | Opaque JSON string — the domain data |
| `callerRef` | string | Domain correlation key. Falls back to `ce.getId()` if absent |
| `templateId` | string | UUID. If present → template merge; absent → direct creation |
| `scope` | string | Hierarchical scope path |
| `expiresAt` | string | ISO-8601 instant |
| `claimDeadline` | string | ISO-8601 instant |
| `followUpDate` | string | ISO-8601 instant |
| `claimDeadlineBusinessHours` | integer | |
| `expiresAtBusinessHours` | integer | |
| `permittedOutcomes` | object[] | `[{"name": "approve"}, {"name": "reject", "displayName": "Reject", "condition": "..."}]` — maps to `Outcome(name, displayName, condition)` |
| `candidateScores` | string | Pre-serialized JSON: `"{\"user1\": 0.95}"` |
| `routingExperiences` | string | Pre-serialized JSON array |
| `routingStrategy` | string | Named strategy identifier |
| `minimumScore` | number | |
| `payloadTypeName` | string | |
| `resolutionTypeName` | string | |
| `excludedUsers` | string | CSV |
| `labels` | object[] | `[{"path": "...", "persistence": "MANUAL", "appliedBy": "..."}]` — maps to `WorkItemLabelRequest(path, persistence, appliedBy)`. `persistence` defaults to `MANUAL` if absent. |
| `confidenceScore` | number | |
| `inputDataSchema` | string | JSON Schema draft-07 |
| `outputDataSchema` | string | JSON Schema draft-07 |

**Overridden fields** (caller values ignored):

| Field | Override value | Why |
|---|---|---|
| `createdBy` | `"cloudevent:" + ce.getSource()` | Provenance tracking + partial unique index coverage |
| `tenancyId` | Explicitly set to `null` | Tenant context established via `TenantContextRunner` from the CloudEvent extension. The store stamps tenancy from `CurrentPrincipal` when the entity value is null. If a data payload smuggles a different `tenancyId`, the override prevents tenant isolation bypass. |
| `callerRef` | Resolved value (data field or `ce.getId()` fallback) | Ensures the idempotency key matches what was checked in step 5 |

**Excluded fields** (not supported via CloudEvent, set by the service internally):

| Field | Why excluded |
|---|---|
| `auditDetail` | Set by `WorkItemService` during creation — records group expansion notes |
| `templateVersion` | Set by `WorkItemTemplateService` during template merge — records template version at instantiation |

### Example: Engine HumanTask (distributed)

```json
{
  "type": "io.casehub.work.workitem.create",
  "source": "/engine/cases/abc-123",
  "id": "evt-001",
  "tenancyid": "tenant-42",
  "datacontenttype": "application/json",
  "data": {
    "title": "Review legal document",
    "candidateGroups": "legal-team,compliance",
    "callerRef": "case:abc-123/pi:def-456",
    "payload": "{\"documentId\": \"doc-789\", \"urgency\": \"high\"}",
    "scope": "casehubio/app/legal-review",
    "candidateScores": "{\"alice\": 0.95, \"bob\": 0.72}",
    "permittedOutcomes": [{"name": "approve"}, {"name": "reject"}],
    "expiresAt": "2026-08-24T18:00:00Z"
  }
}
```

### Example: Simple external system (template-based)

```json
{
  "type": "io.casehub.work.workitem.create",
  "source": "/workflows/doc-approval",
  "id": "evt-002",
  "tenancyid": "tenant-42",
  "datacontenttype": "application/json",
  "data": {
    "templateId": "550e8400-e29b-41d4-a716-446655440000",
    "payload": "{\"documentId\": \"doc-789\"}",
    "callerRef": "workflow:run-555/step-3"
  }
}
```

## Consumer — `WorkCloudEventInboundAdapter`

### Location

Existing class: `runtime/src/main/java/io/casehub/work/runtime/event/WorkCloudEventInboundAdapter.java`

Add a second `@ObservesAsync CloudEvent` method alongside the existing `REQUESTED` handler.

### Behavior

New method `onCreateCloudEvent(@ObservesAsync CloudEvent ce)`:

1. Filter on type `WorkCloudEventTypes.CREATE` — return immediately for other types
2. Extract `tenancyid` extension — missing → log ERROR, return
3. Wrap in `tenantContextRunner.runInTenantContext(tenancyId, ...)`
4. Parse CloudEvent data bytes to `JsonNode` via ObjectMapper
5. Resolve `callerRef`: `node.get("callerRef")` text value, fall back to `ce.getId()`
6. Idempotency check: `workItemService.findByCallerRef(callerRef)` — if exists → log DEBUG, return
7. Build `WorkItemCreateRequest` by reading JsonNode fields into the Builder (manual construction — `WorkItemCreateRequest` has no Jackson annotations and uses a private constructor with Builder pattern)
8. Override `createdBy` to `"cloudevent:" + ce.getSource()`
9. Override `callerRef` to the resolved value from step 5
10. Override `tenancyId` to `null` (tenant context via CDI, not request field — prevents tenant isolation bypass)
11. Route:
    - `templateId` present → `templateService.createFromTemplate(request)`
    - `templateId` absent → `workItemService.create(request)`
12. Catch `PersistenceException` with unique constraint violation → idempotent success (DEBUG log)

**Deserialization approach:** Manual `JsonNode` → Builder construction, NOT `objectMapper.readValue(data, WorkItemCreateRequest.class)`. `WorkItemCreateRequest` has a private constructor and no `@JsonCreator`/`@JsonDeserialize` annotations — direct deserialization fails with `InvalidDefinitionException`. The REST layer uses the same pattern: a separate `CreateWorkItemRequest` DTO deserialized by Jackson, then mapped to `WorkItemCreateRequest.Builder`. Here, `JsonNode` plays the DTO role — simpler than creating another record.

**Idempotency note:** The `callerRef`-based idempotency for `CREATE` is a semantic shift from `REQUESTED`. With `REQUESTED`, `ce.getId()` IS the callerRef — transport identity and domain correlation are unified. With `CREATE`, they are separate: `ce.getId()` is transport-level (same event redelivered), `callerRef` is domain-level (caller's correlation key). The fallback to `ce.getId()` when callerRef is absent preserves the simpler model for callers that don't need custom correlation.

### Dependencies

New injection: `ObjectMapper` (already available via Quarkus Jackson — no new dependency).

Existing injections (`WorkItemTemplateService`, `WorkItemService`, `TenantContextRunner`) are sufficient.

### Error Handling

Same classification as `REQUESTED`:

| Category | Examples | Action |
|---|---|---|
| Configuration | Missing `tenancyid`, template not found, payload schema validation failure, `title` null without `templateId` | Log ERROR, return. Retry would produce the same failure. |
| Deserialization | Malformed JSON data, null data bytes, unrecognized field type coercion failure | Log ERROR, return. Same as configuration — deterministic failure. |
| Infrastructure | Database, transaction, CDI runtime | Propagate. Transport retries (Kafka offset not committed, HTTP 500). |
| Idempotent duplicate | `findByCallerRef` hit or unique constraint violation | Log DEBUG, return. Not an error. |

## Changes

### `WorkCloudEventTypes` (api module)

Add constant:

```java
public static final String CREATE = PREFIX + "create";
```

### `WorkCloudEventInboundAdapter` (runtime module)

Add `ObjectMapper` injection. Add `onCreateCloudEvent` method. Extract shared tenant-context and idempotency logic where it reduces duplication (private helper methods — not an abstraction layer).

### Drift prevention test (api module)

Add assertion: `CREATE` is NOT in the `WorkEventType` lifecycle enum — same pattern as the existing `REQUESTED` assertion. This prevents future confusion between the inbound command `CREATE` (`io.casehub.work.workitem.create`) and the lifecycle event `CREATED` (`io.casehub.work.workitem.created`).

## Testing

### Unit tests (`WorkCloudEventInboundAdapterTest`)

1. **Happy path — inline creation:** CloudEvent with `CREATE` type, no `templateId` in data → `workItemService.create()` called with correct fields
2. **Happy path — template creation:** CloudEvent with `CREATE` type, `templateId` in data → `templateService.createFromTemplate()` called
3. **Missing tenancyid:** → ERROR logged, no creation
4. **Missing title without templateId:** → validation error from `WorkItemCreateRequest.build()`, ERROR logged
5. **callerRef fallback:** No `callerRef` in data → `ce.getId()` used as callerRef
6. **callerRef from data:** `callerRef` in data → data value used, not `ce.getId()`
7. **Idempotency — callerRef exists:** `findByCallerRef` returns existing → skip, DEBUG log
8. **Idempotency — constraint violation:** Concurrent duplicate caught by DB → skip, DEBUG log
9. **createdBy override:** Data contains `createdBy` field → overridden to `"cloudevent:" + source`
10. **tenancyId override:** Data contains `tenancyId: "tenant-B"` but extension is `tenant-A` → request.tenancyId is null, WorkItem created under tenant-A context (prevents tenant isolation bypass)
11. **Non-CREATE type ignored:** CloudEvent with `REQUESTED` or `COMPLETED` type → method returns immediately
12. **Field mapping completeness:** All supported `WorkItemCreateRequest` fields populated from data → all present on created WorkItem
13. **Malformed data:** Invalid JSON in CloudEvent data → ERROR logged, no creation
14. **Null data:** CloudEvent with null data bytes → ERROR logged, no creation

### Integration test (runtime module)

Round-trip test:
1. Create a `WorkItemTemplate`
2. Fire CDI `Event<CloudEvent>.fireAsync()` with type `CREATE`, `templateId` in data, arbitrary JSON payload
3. Assert: WorkItem created with template fields merged, payload contains domain data, callerRef matches data field, createdBy is `"cloudevent:" + source`
4. Assert: outbound CloudEvent with type `CREATED` fired (confirms creation round-trip)
5. Complete the WorkItem
6. Assert: outbound CloudEvent with type `COMPLETED` fired, data contains callerRef for correlation
7. Fire same CloudEvent again (same callerRef) → no duplicate created

Inline variant:
1. Fire `CREATE` CloudEvent with no `templateId`, title and candidateGroups in data
2. Assert: WorkItem created with exact data fields, no template merge

**H2 limitation:** The partial unique index `uq_workitem_cloudevent_idempotency` (`WHERE created_by LIKE 'cloudevent:%'`) is Postgres-specific. Integration tests on H2 cannot exercise the TOCTOU database-level guard. The constraint violation code path (step 12) is tested via unit test with a mocked `PersistenceException`. Application-level idempotency (step 6) is testable on H2.

## Not In Scope

- **Engine-side CloudEvent emitter** — future: engine binding evaluator emits `io.casehub.work.workitem.create` instead of Vert.x `HumanTaskScheduleEvent`. Engine-side PlanItem validation and DELEGATED persistence happen before emission. Follow-up issue in casehubio/engine.
- **Engine-side lifecycle CloudEvent consumer** — future: engine observes outbound `io.casehub.work.workitem.completed` etc. CloudEvents and translates to PlanItem transitions. Replaces co-located `WorkItemLifecycleAdapter` for the distributed case. Follow-up issue in casehubio/engine.
- **ActionGate CloudEvent path** — `ActionGateWorkItemHandler` has the same co-location constraint. Same pattern applies but is a separate issue.

## Architectural Notes

### Relationship to `REQUESTED`

Both types coexist. They serve different use cases:

| | `REQUESTED` | `CREATE` |
|---|---|---|
| Template required | Yes (`templateid` extension) | Optional (`templateId` in data) |
| Data semantics | Opaque domain payload | Structured `WorkItemCreateRequest` fields |
| callerRef | `ce.getId()` always | Data field, fallback to `ce.getId()` |
| Caller control | Template defines everything; data merges payload | Caller defines everything |
| Use case | External systems with pre-configured templates | Engine, Qhorus, systems needing full control |

`REQUESTED` is not deprecated. It serves a different use case with simpler semantics — the CloudEvent id unifies transport identity and domain correlation, and the template-only path means callers never need to know `WorkItemCreateRequest` fields. `CREATE` subsumes `REQUESTED` functionally (a caller could use `CREATE` with `templateId` in data), but `REQUESTED` remains the recommended path for simple integrations where a pre-configured template defines everything.

### Distributed HumanTask — full picture

With this change, the distributed HumanTask pattern needs:

1. **This issue (#299):** Work-side inbound consumer ← YOU ARE HERE
2. **Engine emitter (future):** Engine detects co-located vs distributed mode. Co-located: Vert.x event bus (existing). Distributed: CloudEvent emission with all `WorkItemCreateRequest` fields.
3. **Engine lifecycle consumer (future):** Engine observes `io.casehub.work.workitem.completed` etc. CloudEvents, parses callerRef (`case:{caseId}/pi:{planItemId}`), calls `PlanItemCompletionApplier`.

Steps 2 and 3 are engine-repo issues. This issue makes step 1 available.

## References

- `engine-adapter/src/main/java/io/casehub/work/engine/HumanTaskScheduleHandler.java` — current co-located handler; informed field mapping
- `runtime/src/main/java/io/casehub/work/runtime/event/WorkCloudEventInboundAdapter.java` — existing REQUESTED handler; consumer pattern
- `runtime/src/main/java/io/casehub/work/runtime/event/WorkCloudEventAdapter.java` — outbound adapter; return path already works
- `api/src/main/java/io/casehub/work/api/WorkItemCreateRequest.java` — data field mapping source
- `api/src/main/java/io/casehub/work/api/WorkCloudEventTypes.java` — type constants
- `docs/protocols/casehub/async-event-tenant-context-propagation.md` — tenant context in `@ObservesAsync`
- `specs/issue-273-work-cloudevent-adapter/2026-06-23-work-cloudevent-adapter-design.md` — outbound adapter design (#273)
- `specs/2026-07-04-cloudevent-workitem-bridge-design.md` — inbound REQUESTED design (#172)
- casehubio/work#290 — engine-adapter relocation (completed prerequisite)
- casehubio/work#298 — event-as-request refactoring (related, not blocked)
