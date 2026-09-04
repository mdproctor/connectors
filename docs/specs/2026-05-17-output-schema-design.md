# Design Spec — Schema-Validated Output Data (Issue #170)

**Epic:** epic-output-schema  
**Date:** 2026-05-17  
**Status:** Approved

---

## Problem

`WorkItemFormSchema` is a category-level entity that predates templates becoming the
canonical type system. It creates two problems:

1. **Split responsibility.** The template defines what a WorkItem looks like
   (category, priority, outcomes, deadlines) but the schema lives in a separate entity
   keyed by category. Two systems describe the same type; they can diverge silently.

2. **Coarse granularity.** A category-level schema applies to all WorkItems in that
   category regardless of which template created them. A template is a more specific
   unit — two templates in the same category can represent different processes with
   different resolution shapes.

The right design: the template IS the type definition. Schemas belong on the template.

---

## Solution

### Data model

**`WorkItemTemplate` gains two fields:**

| Field | Type | Description |
|-------|------|-------------|
| `inputDataSchema` | TEXT | JSON Schema (draft-07) for `WorkItem.payload`. Validated at instantiation. |
| `outputDataSchema` | TEXT | JSON Schema (draft-07) for `WorkItem.resolution`. Validated at completion. |

**`WorkItemEntity` gains two snapshotted fields:**

| Field | Type | Description |
|-------|------|-------------|
| `inputDataSchema` | TEXT | Copied from template at instantiation. Null for direct creation. |
| `outputDataSchema` | TEXT | Copied from template at instantiation. Null for direct creation. |

Both are snapshotted at instantiation — not live references. A WorkItem's validation
contract is fixed at birth, immune to subsequent template edits. This is the same
pattern as `permittedOutcomes` (issue #169). Template versioning (#180) is the fuller
answer but out of scope here.

**`WorkItemFormSchema` is removed:** entity, Flyway table, CRUD REST API
(`/workitem-form-schemas`), and all references. `FormSchemaValidationService` is
retained — it is a pure JSON Schema utility with no coupling to `WorkItemFormSchema`.

### Validation flow

**`WorkItemService.create()`**  
If `workItem.inputDataSchema` is non-null and `payload` is non-null/non-blank:
validate payload against the schema using `FormSchemaValidationService`. On violation:
throw `IllegalArgumentException` → REST layer returns 400 with violation list.

**`WorkItemService.complete()`**  
If `workItem.outputDataSchema` is non-null and `resolution` is non-null/non-blank:
validate resolution against the schema using `FormSchemaValidationService`. On violation:
throw `IllegalArgumentException` → REST layer returns 400 with violation list.

**Null/blank data is always valid.** If `resolution` is null or blank and
`outputDataSchema` is set, no violation — empty completion remains allowed. Schemas
only enforce shape when data is actually provided.

**`completeFromSystem()` is excluded.** System-initiated completions (multi-instance
threshold) bypass schema validation — same as outcome validation in #169.

**Validation moves to the service layer.** The current category-level resolution
validation in `WorkItemResource.complete()` (the `WorkItemFormSchema.findLatestByCategory()`
call) is removed. The resource layer becomes thin: extract params, call service, map
response.

### Removal scope

Files and classes to delete:
- `WorkItemFormSchema.java` (JPA entity)
- `WorkItemFormSchemaResource.java` (REST resource + nested DTOs)
- `WorkItemFormSchemaTest.java` (integration test)
- `WorkItemFormSchemaValidationTest.java` (integration test)
- `FormSchemaValidationServiceTest.java` — retained (tests the reusable validator)
- All Panache/Flyway references to `work_item_form_schema`

Flyway migrations:
- V23: add `input_data_schema`, `output_data_schema` to `work_item_template`
- V24: add `input_data_schema`, `output_data_schema` to `work_item`
- V25: drop `work_item_form_schema` table

### UI schema discovery

Previously: `GET /workitem-form-schemas?category=loan-approval`  
Now: `GET /workitem-templates/{id}` → `inputDataSchema` + `outputDataSchema`

No information is lost — the template carries both schemas. Consumers use the template
endpoint as the single source of truth for type-level metadata.

### Snapshotting in service paths

All three WorkItem creation paths snapshot template fields:
- `WorkItemTemplateService.instantiate()` → updates `toCreateRequest()` to include both schema fields
- `WorkItemSpawnService` → passes schema fields from template to `WorkItemCreateRequest`
- `MultiInstanceSpawnService` → same

`WorkItemCreateRequest` gains `inputDataSchema` and `outputDataSchema` parameters.
`WorkItemService.create()` sets them on the WorkItem entity.

### REST API surface changes

**Added to `CreateTemplateRequest`:**
- `inputDataSchema` (String, optional)
- `outputDataSchema` (String, optional)

**Added to template `toResponse()`:**
- `inputDataSchema`
- `outputDataSchema`

**Added to `WorkItemResponse` and `WorkItemWithAuditResponse`:**
- `inputDataSchema`
- `outputDataSchema`

`WorkItemMapper.toResponse()` and `toWithAudit()` updated accordingly.

**Removed:**
- All `/workitem-form-schemas` endpoints

### `FormSchemaValidationService` injection

Currently injected only into `WorkItemResource`. After this change:
- Injected into `WorkItemService` instead
- Removed from `WorkItemResource`

---

## Out-of-Scope

| Issue | Description |
|-------|-------------|
| #180 | Template versioning — immutable snapshots for full reproducibility |

---

## Testing

**Key test scenarios:**
- Template with `outputDataSchema` → instantiation snapshots it onto WorkItem
- `complete()` with valid resolution → passes
- `complete()` with invalid resolution → 400 with violation list
- `complete()` with null resolution when schema set → passes (lenient null behaviour)
- Template with `inputDataSchema` → instantiation validates payload
- `create()` with valid payload → passes
- `create()` with invalid payload → 400 with violation list
- `completeFromSystem()` → schema validation not triggered
- WorkItem created directly (no template) → no schema validation at create or complete
- `FormSchemaValidationService` unit tests → unchanged
