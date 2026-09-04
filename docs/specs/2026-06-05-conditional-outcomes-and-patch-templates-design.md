# Design: Conditional Outcomes (#177) + PATCH Templates (#199)

**Branch:** `issue-177-outcomes-and-patch`
**Issues:** #177 (primary), #199
**Date:** 2026-06-05 (rev 4 — post-review)

---

## Overview

Two independent S-scale features on one branch:

1. **#177 — Conditional Outcomes:** `Outcome` gains an optional JEXL `condition` field. At completion or rejection, if the chosen outcome has a condition, it is evaluated against the WorkItem context. Condition false → 400.
2. **#199 — PATCH /workitem-templates/{id}:** JSON Merge Patch (RFC 7396) for partial template updates. Absent fields left unchanged; null clears.

---

## #177 — Conditional Outcomes

### Data Model

**`Outcome` record** (`api/src/main/java/io/casehub/work/api/Outcome.java`):

```java
public record Outcome(String name, String displayName, String condition) {}
```

`condition` is a nullable JEXL expression string. Null means unconditional. Adding this field is a source-breaking change: all `new Outcome("name", "label")` call sites become `new Outcome("name", "label", null)`. The break is intentional — callers become explicit.

**`WorkItemCreateRequest.permittedOutcomes` type change:**

`WorkItemCreateRequest` (the internal builder used by all spawn paths) currently declares `permittedOutcomes` as `List<String>`. This changes to `List<Outcome>`. This is an internal record — no REST API break.

**`WorkItem.permittedOutcomes` storage format change:**

Currently stores `["approved","rejected"]` (name strings). After this change stores full `Outcome` objects: `[{"name":"approved","displayName":"Approved","condition":"..."}]`.

No Flyway migration. `OutcomeCodecs.decodePermittedOutcomes()` uses explicit format detection via Jackson token inspection — not exception-as-control-flow:

```java
public static List<Outcome> decodePermittedOutcomes(final String json) {
    if (json == null || json.isBlank()) return null;
    try {
        ArrayNode arr = (ArrayNode) MAPPER.readTree(json);
        if (arr.isEmpty() || arr.get(0).isObject()) {
            // new format: array of Outcome objects (or empty)
            return MAPPER.convertValue(arr, new TypeReference<List<Outcome>>() {});
        } else {
            // old format: array of name strings — wrap with null displayName/condition
            return StreamSupport.stream(arr.spliterator(), false)
                .map(n -> new Outcome(n.asText(), null, null))
                .toList();
        }
    } catch (final Exception e) {
        LOG.warnf("Failed to decode permittedOutcomes JSON: %s", e.getMessage());
        return null;
    }
}
```

`readTree()` parses the JSON correctly regardless of whitespace between tokens. `arr.get(0).isObject()` detects format from the first element type, not the raw string. Null/blank guard and try-catch preserve the existing error contract (corrupt JSON → warn + return null). Old rows decode correctly; new instantiations write the new format.

**Deliberate decision — conditions are snapshotted at instantiation:** Conditions are stored in `WorkItem.permittedOutcomes` at instantiation time, not re-read from the template at completion time. This means changing a condition on a template has no effect on already-live WorkItems. This is intentional and consistent with `inputDataSchema` and `outputDataSchema` snapshotting. Operators should be aware: in-flight WorkItems are evaluated against the template state at their creation time.

### `OutcomeCodecs` Changes

| Method | Change |
|--------|--------|
| `decodePermittedOutcomes(String)` | Return type `List<String>` → `List<Outcome>`. Explicit format detection (see above). |
| `encodePermittedOutcomes(List<String>)` | **Removed.** No remaining callers after `WorkItemCreateRequest` type change. |
| `encodePermittedOutcomes(List<Outcome>)` | **Not added.** After the format change, `encodePermittedOutcomes(List<Outcome>)` and `encodeOutcomes(List<Outcome>)` would be functionally identical — same JSON, same column shape. Adding the wrapper creates confusion about which to use for which column. Callers use `encodeOutcomes()` directly for both the template `outcomes` column and the WorkItem `permittedOutcomes` column. |
| `parseOutcomeNames(String)` | **Removed.** Dead code after all 3 instantiation call sites updated (see below). |
| `encodeOutcomes(List<Outcome>)` | Unchanged. Used for both `WorkItemTemplate.outcomes` and `WorkItem.permittedOutcomes`. |
| `decodeOutcomes(String)` | Unchanged. |

`WorkItemTemplateService` static wrappers `parseOutcomeNames()`, `encodePermittedOutcomes(List<String>)`, and `decodePermittedOutcomes(String)` are also removed. `parseOutcomeNames()` and `encodePermittedOutcomes(List<String>)` delegate to removed methods. `decodePermittedOutcomes(String)` returns `List<String>` but delegates to `OutcomeCodecs.decodePermittedOutcomes()` which now returns `List<Outcome>` — compile error — and its only caller (`validateOutcome()` in `WorkItemService`) is also being removed. `encodeOutcomes(List<Outcome>)` is kept (unchanged, valid).

### Instantiation Change Sites — 3 Locations

All three call `parseOutcomeNames(template.outcomes)` to snapshot name strings. All three change to `OutcomeCodecs.decodeOutcomes(template.outcomes)` to snapshot full `List<Outcome>` objects (including conditions):

| File | Current | After |
|------|---------|-------|
| `WorkItemTemplateService.java:257` | `.permittedOutcomes(parseOutcomeNames(template.outcomes))` | `.permittedOutcomes(OutcomeCodecs.decodeOutcomes(template.outcomes))` |
| `MultiInstanceSpawnService.java:158` | `.permittedOutcomes(WorkItemTemplateService.parseOutcomeNames(template.outcomes))` | `.permittedOutcomes(OutcomeCodecs.decodeOutcomes(template.outcomes))` |
| `MultiInstanceSpawnService.java:182` | same | same |
| `WorkItemSpawnService.java:135` | `.permittedOutcomes(WorkItemTemplateService.parseOutcomeNames(template.outcomes))` | `.permittedOutcomes(OutcomeCodecs.decodeOutcomes(template.outcomes))` |

### New: `OutcomeValidator` CDI Bean

A new `@ApplicationScoped OutcomeValidator` in `runtime/service/` encapsulates condition evaluation, keeping `WorkItemService` focused on lifecycle transitions. `WorkItemService` injects `OutcomeValidator` (replacing the inline `validateOutcome()` logic).

```java
@ApplicationScoped
public class OutcomeValidator {

    @Inject JexlConditionEvaluator conditionEvaluator; // runtime.filter variant

    /**
     * Validates the submitted outcome name and evaluates its condition if present.
     *
     * @param item       the WorkItem being completed or rejected
     * @param outcome    the submitted outcome name
     * @param resolution the completion payload (null on reject paths)
     * @param reason     the rejection reason (null on complete paths)
     * @param actorId    who is completing or rejecting
     * @throws IllegalArgumentException if outcome is absent, not permitted, or condition not satisfied
     */
    public void validate(WorkItem item, String outcome, String resolution,
                         String reason, String actorId) {
        if (item.permittedOutcomes == null) return;
        if (outcome == null || outcome.isBlank()) throw ...;
        if (outcome.length() > 255) throw ...;

        List<Outcome> definitions = OutcomeCodecs.decodePermittedOutcomes(item.permittedOutcomes);
        if (definitions == null) {
            throw new IllegalStateException(
                "permittedOutcomes on WorkItem " + item.id + " is non-null but failed to decode — data integrity error");
        }
        Optional<Outcome> match = definitions.stream()
            .filter(o -> outcome.equals(o.name()))
            .findFirst();

        if (match.isEmpty()) {
            List<String> names = definitions.stream().map(Outcome::name).toList();
            throw new IllegalArgumentException("outcome '" + outcome + "' is not permitted; allowed: " + names);
        }

        String condition = match.get().condition();
        if (condition != null && !condition.isBlank()) {
            Map<String, Object> workItemContext = WorkItemContextBuilder.toMap(item); // static call
            Map<String, Object> extra = new HashMap<>();
            extra.put("resolution", resolution); // null on reject paths — intentional
            extra.put("reason", reason);         // null on complete paths — intentional
            extra.put("actorId", actorId);
            if (!conditionEvaluator.evaluate(condition, extra, workItemContext)) {
                throw new IllegalArgumentException(
                    "outcome '" + outcome + "' condition not satisfied (check template definition)");
            }
        }
    }
}
```

**Which `JexlConditionEvaluator`:** `runtime.filter.JexlConditionEvaluator` — the `@ApplicationScoped` bean used by filter rules. The `queues.service.JexlConditionEvaluator` implements a different interface and is not used here.

**`resolution` on reject paths:** `null`. Conditions testing `resolution != null` correctly fail on rejection paths. The `reason` variable is bound separately so conditions can use `reason` if needed.

**System paths bypass condition evaluation:** `completeFromSystem()` and `rejectFromSystem()` do not call `OutcomeValidator`. This is intentional — system-context completions bypass all user-level constraints (outcome name validation, condition evaluation, schema validation). This is the existing contract.

**Silent JEXL failure:** If a condition expression is syntactically invalid JEXL, `JexlConditionEvaluator.evaluate()` returns false (silent with `strict(false).silent(true)`). The outcome becomes permanently unselectable on any WorkItem instantiated from that template. The error message `"condition not satisfied (check template definition)"` signals this but cannot distinguish a logic-false result from a broken expression. Syntax validation at template-creation time is deferred (out of scope for this issue).

**JEXL security profile:** Conditions are trusted-template-author input, not end-user input. The JEXL engine runs with `strict(false).silent(true)` — the same profile as filter rules. `workItem` is a `Map<String,Object>`, so property traversal is limited to map key lookup; arbitrary class graph traversal is not possible.

### `WorkItemContextBuilder` Change

`WorkItemContextBuilder` is a `public final class` with a private constructor and static `toMap(WorkItem)` method — not a CDI bean. It is called statically from `OutcomeValidator`.

Line 70 must be updated to preserve backward compatibility with existing filter rule expressions:

```java
// Before
map.put("permittedOutcomes", OutcomeCodecs.decodePermittedOutcomes(workItem.permittedOutcomes));

// After — still exposes List<String> for filter rules
List<Outcome> defs = OutcomeCodecs.decodePermittedOutcomes(workItem.permittedOutcomes);
map.put("permittedOutcomes", defs == null ? null : defs.stream().map(Outcome::name).toList());
```

### API Surface

**`WorkItemResponse`:** `permittedOutcomes` field changes type from `List<String>` to `List<Outcome>`. The JSON response changes from `["approved","rejected"]` to `[{"name":"approved","displayName":"Approved","condition":null}]`. This is a REST API breaking change acknowledged explicitly — no external consumers in this platform.

`WorkItemResponse.java` must be updated accordingly.

**Template create/update requests:** `List<Outcome> outcomes` already exists in `CreateTemplateRequest` and `UpdateTemplateRequest`. Callers start passing `condition` where needed. No structural change.

### Condition Failure HTTP Status

400 Bad Request. The existing `validateOutcome()` contract maps `IllegalArgumentException` → 400. Condition failure is semantic input validation — the submitted outcome is not acceptable given current WorkItem state. 409 (Conflict) in this codebase means duplicate resource; reusing it would conflate two different error categories. 422 would be technically precise but is not used anywhere in this stack.

### JEXL Condition Context Reference

Available variables at condition evaluation time:

| Variable | Type | Notes |
|----------|------|-------|
| `workItem` | `Map<String,Object>` | Full WorkItem field map — see below |
| `resolution` | `String` or null | Completion payload. **Always null on `reject()` paths.** |
| `reason` | `String` or null | Rejection reason. Always null on `complete()` paths. |
| `actorId` | `String` | Who is completing or rejecting. |

**`workItem` field types (key details for condition authors):**

| Key | Type | Notes |
|-----|------|-------|
| `workItem.priority` | `WorkItemPriority` enum | Enum constant — use `workItem.priority.name()` to get string |
| `workItem.status` | `WorkItemStatus` enum | Same — use `.name()` for string comparison |
| `workItem.payload` | `String` | Raw JSON string. Use `.contains("x")` not `.someField`. |
| `workItem.resolution` | `null` **always** | Not yet set when condition is evaluated — assignment happens after validation |
| `workItem.permittedOutcomes` | `List<String>` | Name strings (for filter rule compatibility) |
| `workItem.assigneeId` | `String` or null | — |
| `workItem.candidateGroups` | `String` or null | Comma-separated |
| `workItem.createdBy` | `String` | — |

> **Trap:** `workItem.resolution` is always null at condition evaluation time. Use the top-level `resolution` variable instead.

**Example conditions:**

```
workItem.priority.name() == 'URGENT'
actorId.startsWith('mgr-')
resolution != null && resolution.contains('APPROVED')
```

### Test Plan

**`OutcomeCodecs` (unit — extend `OutcomeTest` or new class):**
- `decodePermittedOutcomes` with new object-array format → `List<Outcome>` with correct fields
- `decodePermittedOutcomes` with old string-array format → wraps to `List<Outcome>` with null displayName/condition
- `decodePermittedOutcomes` with whitespace-formatted JSON e.g. `[ {"name":"approved"} ]` → correct (regression against the old `startsWith` bug)
- `decodePermittedOutcomes` with null/blank → null
- `decodePermittedOutcomes` with corrupt JSON → null (warn, no throw)
- Round-trip: `encodeOutcomes(outcomes)` → `decodePermittedOutcomes()` → same `List<Outcome>`

**`OutcomeValidator` (unit — new `OutcomeValidatorTest`):**
- `permittedOutcomes` null: returns without throwing (unconstrained)
- Null condition on matching outcome: accepted by name alone
- Condition evaluating true: accepted
- Condition evaluating false: `IllegalArgumentException`
- Condition referencing `resolution`: non-null on complete path, null on reject path — verified separately
- Condition referencing `reason`: non-null on reject path, null on complete path
- Condition referencing `actorId`: matched correctly
- Old-format `permittedOutcomes` (string-array): fallback decode, name validation still works
- `decodePermittedOutcomes` returns null (corrupt data in `permittedOutcomes`): `IllegalStateException`, not NPE

**Integration (add scenario to `WorkItemLifecycleIT` or `CreditDecisionScenario`):**
- Create template with conditional outcome; instantiate; complete with satisfied condition → 200
- Same; complete with unsatisfied condition → 400
- Create WorkItem directly with old-format string-array `permittedOutcomes` value in DB; complete → correct name validation via fallback decode

---

### ADR Needed

Two decisions embedded in this spec warrant ADR entries:
- **Evaluation timing:** conditions evaluated at completion time, not instantiation time (the "filter at instantiation" alternative is deferred)
- **Language choice:** JEXL as condition language (consistent with filter rules)

These will be filed as GitHub issues on this branch and created as ADRs at epic close.

---

## #199 — PATCH /workitem-templates/{id}

### Endpoint

```
PATCH /workitem-templates/{id}
Content-Type: application/merge-patch+json
```

New handler in `WorkItemTemplateResource`. No new Maven dependency — `JsonNode` already imported.

### Implementation Strategy

The handler accepts `JsonNode patch`. For each of the 25 template fields, it applies `patch.has(fieldName)` to distinguish absent (leave unchanged) from present (apply — null clears). This is explicit, readable, and maintains the same validation path as PUT.

**Three standard patterns by field type (20 of 25 fields):**

```java
// String fields (13): description, category, candidateGroups, candidateUsers,
//   requiredCapabilities, defaultPayload, labelPaths, parentRole,
//   assignmentStrategy, onThresholdReached, excludedUsers, excludedGroups, scope
JsonNode node = patch.get("description");
if (patch.has("description")) t.description = node.isNull() ? null : node.asText();

// Integer fields (6): defaultExpiryHours, defaultClaimHours, defaultExpiryBusinessHours,
//   defaultClaimBusinessHours, instanceCount, requiredCount
// intValue() returns 0 on null nodes; isNull() guard is required.
if (patch.has("defaultExpiryHours")) t.defaultExpiryHours = node.isNull() ? null : node.intValue();

// Boolean fields (1): allowSameAssignee
// booleanValue() returns false on null nodes; isNull() guard is required.
if (patch.has("allowSameAssignee")) t.allowSameAssignee = node.isNull() ? null : node.booleanValue();
```

Use `intValue()` / `booleanValue()`, not `asInt()` / `asBoolean()` — the `as*()` variants return defaults (0 / false) on null nodes, defeating the clear-on-null semantic.

**Special fields (5 of 25) requiring explicit handling:**

| Field | Handling |
|-------|---------|
| `name` | Present + null → 400 (`name` required when provided). Present + non-null → conflict check same as PUT. |
| `priority` | Non-null: `WorkItemPriority.valueOf(node.asText())` in try-catch → 400 on invalid enum value. Null → clear. |
| `outcomes` | Non-null: `MAPPER.convertValue(node, new TypeReference<List<Outcome>>(){})` → `OutcomeCodecs.encodeOutcomes()`. Null → null (clear). |
| `inputDataSchema` | Non-null: must be object node (`node.isObject()`) → `node.toString()` for storage; non-object → 400. Null → null (clear). |
| `outputDataSchema` | Same as `inputDataSchema`. |

**`createdBy`:** Not patchable — authorship is immutable. If present in the patch body, silently ignore it. This follows the same convention as PUT, which also has no `t.createdBy = request.createdBy()` assignment. A 400 on unexpected fields would be stricter but inconsistent with how PUT handles the same constraint (it simply omits the field from the request type rather than rejecting it).

After applying all present fields: run `WorkItemTemplateValidationService.validate(t)` → same as PUT. Return 200 `toResponse(t)`.

### Test Plan

**Unit tests (`WorkItemTemplatePatchTest` — new):**

Generic field semantics:
- PATCH with absent field: existing value preserved
- PATCH with non-null value: field updated
- PATCH with null value: field cleared
- Empty patch `{}`: 200, entity unchanged
- Non-existent template: 404

Name-specific:
- PATCH `name` with null → 400
- PATCH `name` to conflicting name → 409
- PATCH `name` unchanged (same value as current) → 200, no conflict

Special fields:
- PATCH `priority` with valid value → updated
- PATCH `priority` with invalid value → 400
- PATCH `priority` with null → cleared
- PATCH `outcomes` with list including condition field → encoded correctly; decode round-trips
- PATCH `outcomes` with null → cleared
- PATCH `inputDataSchema` with JSON object → stored as string
- PATCH `inputDataSchema` with null → cleared
- PATCH `inputDataSchema` with non-object (array) → 400
- PATCH `outputDataSchema`: same three cases

**Integration test (add to `WorkItemTemplateIT`):**
- Create template with all fields set; PATCH one field; GET confirms only that field changed, all others intact.

---

## Full Change Surface Summary

| File | Change |
|------|--------|
| `api/.../Outcome.java` | Add `condition` field; source break at all constructor call sites |
| `runtime/.../OutcomeCodecs.java` | `decodePermittedOutcomes()` → `List<Outcome>` with `readTree()` format detection; remove `encodePermittedOutcomes(List<String>)` and `parseOutcomeNames()`; `encodeOutcomes()` now used for both columns |
| `runtime/.../WorkItemTemplateService.java` | Remove delegate wrappers: `parseOutcomeNames()`, `encodePermittedOutcomes(List<String>)`, `decodePermittedOutcomes(String)`; update `instantiate()` call site |
| `runtime/.../MultiInstanceSpawnService.java` | Update 2 call sites: `parseOutcomeNames()` → `decodeOutcomes()` |
| `runtime/.../WorkItemSpawnService.java` | Update 1 call site: same change |
| `runtime/.../WorkItemCreateRequest.java` | `permittedOutcomes` field type `List<String>` → `List<Outcome>` |
| `runtime/.../OutcomeValidator.java` | **New** `@ApplicationScoped` bean encapsulating condition validation |
| `runtime/.../WorkItemService.java` | Replace `validateOutcome()` with `OutcomeValidator.validate()` injection; add `resolution`/`actorId`/`reason` threading. Line 107: `WorkItemTemplateService.encodePermittedOutcomes(request.permittedOutcomes)` → `WorkItemTemplateService.encodeOutcomes(request.permittedOutcomes)` |
| `runtime/.../WorkItemContextBuilder.java` | Line 70: extract name strings from `List<Outcome>` before putting in JEXL context |
| `runtime/.../WorkItemResponse.java` | `permittedOutcomes` type `List<String>` → `List<Outcome>` (REST API break, acknowledged) |
| `runtime/.../WorkItemMapper.java` | Update response mapping for `permittedOutcomes` new type |
| `runtime/.../WorkItemWithAuditResponse.java` | `permittedOutcomes` type `List<String>` → `List<Outcome>` (REST API break, same as `WorkItemResponse`) |
| `runtime/.../WorkItemTemplateResource.java` | Add `@PATCH` handler |
| Test files | New/extended per test plan above |
