# Escalation and SkillMatch YAML Expansion — Design Spec

**Date:** 2026-08-28
**Status:** Draft
**Issue:** casehubio/work#362
**Parent:** casehubio/parent#422 — TypeScript programming model, Phase 2
**Decisions:** [decisions.md](decisions.md) D1-D3

## Motivation

`@Escalate` and `@SkillMatch` are annotation-only configuration in `casehub-work-annotations`. The parent#422 epic requires YAML parity for all declarative configurations across the platform. These two annotations map naturally to nested objects under the existing `humanTask:` YAML schema — adding them completes the work module's YAML surface.

## YAML Surface (D1)

```yaml
bindings:
  - name: review-binding
    humanTask:
      title: "Review investigation"
      candidateGroups: [senior-reviewers]
      claimDeadlineHours: 4
      escalation:
        onExpiry: "team-leads"
        onClaimDeadline: "managers"
        deadline: "PT4H"
        generateSummary: true
      skillMatch:
        strategy: "semantic"
        requiredCapabilities: ["java", "security-review"]
        minimumScore: 0.7
    on:
      contextChange:
        filter: ".readyForReview == true"
```

Both `escalation` and `skillMatch` are optional. All fields within each are optional — partial configuration is valid (e.g. `escalation:` with only `onExpiry`).

## Changes

### Engine: CaseDefinition.yaml — Schema (D1)

Add `escalation` and `skillMatch` as entries under `HumanTask.properties` (the schema uses `unevaluatedProperties: false`, so they must appear here to pass validation):

```yaml
escalation:
  type: object
  unevaluatedProperties: false
  properties:
    onExpiry:
      type: string
      description: Candidate group to escalate to when the WorkItem expires.
    onClaimDeadline:
      type: string
      description: Candidate group to escalate to when claim deadline passes.
    deadline:
      type: string
      description: >-
        ISO-8601 duration for the escalated WorkItem's new completion window.
        Only applies to onExpiry (completion-expiry) escalations; has no effect
        on onClaimDeadline escalations, where the new claim deadline is always
        computed via ClaimSlaPolicy.
    generateSummary:
      type: boolean
      default: true
      description: Whether to generate an AI summary on escalation.
skillMatch:
  type: object
  unevaluatedProperties: false
  properties:
    strategy:
      type: string
      description: Named WorkerSelectionStrategy (e.g. "semantic", "keyword").
    requiredCapabilities:
      type: array
      items:
        type: string
      description: Capabilities the assigned worker must possess.
    minimumScore:
      type: number
      minimum: 0
      maximum: 1
      description: Minimum skill-match score threshold.
```

### Engine: HumanTaskTarget — Model (D2)

Add two inner records and corresponding fields:

```java
public record EscalationConfig(
    String onExpiry,
    String onClaimDeadline,
    String deadline,
    Boolean generateSummary
) {
    public EscalationConfig {
        if (deadline != null && !deadline.isEmpty()) {
            java.time.Duration d = java.time.Duration.parse(deadline);
            if (d.isZero() || d.isNegative()) {
                throw new IllegalArgumentException(
                    "escalation deadline must be positive, was: " + deadline);
            }
        }
    }
}

public record SkillMatchConfig(
    String strategy,
    Set<String> requiredCapabilities,
    Double minimumScore
) {
    public SkillMatchConfig {
        if (minimumScore != null && (minimumScore < 0.0 || minimumScore > 1.0)) {
            throw new IllegalArgumentException(
                "minimumScore must be in [0.0, 1.0], was: " + minimumScore);
        }
    }
}
```

Add fields to `HumanTaskTarget`:
- `private final EscalationConfig escalation;`
- `private final SkillMatchConfig skillMatch;`

Add builder methods:
- `escalation(EscalationConfig)`, `skillMatch(SkillMatchConfig)`

**Annotation mapping path:** When parent#422 wires the annotation processor to production code, `CompositeWorkItemDescriptor` (which holds `EscalationDescriptor`) will map to `HumanTaskTarget.EscalationConfig` in the engine-adapter layer. The mapping is mechanical — `EscalationDescriptor` and `EscalationConfig` have identical field names; types differ for nullable semantics. Mapping rules for the annotation path:

- `EscalationDescriptor.generateSummary` (`boolean`, annotation default: `true`) → `Boolean.valueOf(desc.generateSummary())` — no sentinel needed; the `@Escalate` annotation's presence is the opt-in signal
- `SkillMatchDescriptor.minimumScore` (`double`, sentinel: `-1.0`) → `null` when `-1.0`, otherwise `Double.valueOf(...)`
- `SkillMatchDescriptor.requiredCapabilities` (`List<String>`) → `Set<String>` via `new LinkedHashSet<>(...)`

The two record families exist in separate modules because engine-api cannot depend on work-annotations (wrong dependency direction per D2). This structural duplication is intentional and preferred over introducing a shared module for four fields.

### Engine: BindingDeserializer — Parsing

Add to `deserializeHumanTask()`, following the existing pattern (check `node.has()`, parse nested object, set on builder):

```java
if (node.has("escalation")) {
    JsonNode esc = node.get("escalation");
    String deadline = textOrNull(esc, "deadline");
    if (deadline != null && !deadline.isEmpty()) {
        try {
            java.time.Duration d = java.time.Duration.parse(deadline);
            if (d.isZero() || d.isNegative()) {
                throw new IllegalArgumentException(
                    "Binding '" + bindingName + "' escalation deadline must be positive, got: " + deadline);
            }
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException(
                "Binding '" + bindingName + "' escalation has invalid deadline: " + deadline, e);
        }
    }
    String onExpiry = textOrNull(esc, "onExpiry");
    String onClaimDeadline = textOrNull(esc, "onClaimDeadline");
    if (onExpiry != null && !node.has("expiresIn") && !node.has("expiresInExpression")
            && !node.has("expiresAtExpression")) {
        LOG.warnf("Binding '%s': escalation.onExpiry is set but no expiry deadline "
                + "(expiresIn/expiresInExpression/expiresAtExpression) — escalation may "
                + "never fire unless a case-level budget deadline applies", bindingName);
    }
    if (onClaimDeadline != null && !node.has("claimDeadlineHours")) {
        LOG.warnf("Binding '%s': escalation.onClaimDeadline is set but no "
                + "claimDeadlineHours — claim escalation will not fire", bindingName);
    }
    if (deadline != null && onExpiry == null && onClaimDeadline == null) {
        LOG.warnf("Binding '%s': escalation.deadline is set but no escalation target "
                + "(onExpiry/onClaimDeadline) — deadline has no effect", bindingName);
    }
    if (deadline != null && onClaimDeadline != null && onExpiry == null) {
        LOG.warnf("Binding '%s': escalation.deadline only applies to completion-expiry "
                + "escalations (onExpiry); it has no effect on claim-deadline escalations "
                + "(onClaimDeadline)", bindingName);
    }
    b.escalation(new HumanTaskTarget.EscalationConfig(
        onExpiry, onClaimDeadline, deadline,
        esc.has("generateSummary") ? esc.get("generateSummary").asBoolean() : true
    ));
}
if (node.has("skillMatch")) {
    JsonNode sm = node.get("skillMatch");
    Set<String> caps = new LinkedHashSet<>();
    if (sm.has("requiredCapabilities")) {
        sm.get("requiredCapabilities").forEach(n -> caps.add(n.asText()));
    }
    b.skillMatch(new HumanTaskTarget.SkillMatchConfig(
        textOrNull(sm, "strategy"),
        caps.isEmpty() ? null : caps,
        sm.has("minimumScore") ? sm.get("minimumScore").asDouble() : null
    ));
}
```

### Engine: Generator — BindingTargetModule

If the victools generator is already active (engine#975), the inner records on `HumanTaskTarget` should be picked up automatically by reflection. Verify the generated schema includes the new nested objects. If the generator is not yet active, the hand-written schema change above is sufficient.

### Work: WorkItemCreateRequest — New fields (D3)

Add four fields:

| Field | Type | Maps from | Lifecycle |
|-------|------|-----------|-----------|
| `escalationOnExpiry` | `String` | `EscalationConfig.onExpiry` | Persisted |
| `escalationOnClaimDeadline` | `String` | `EscalationConfig.onClaimDeadline` | Persisted |
| `escalationDeadline` | `String` | `EscalationConfig.deadline` | Persisted |
| `escalationGenerateSummary` | `Boolean` | `EscalationConfig.generateSummary` | Persisted |

All four escalation fields are **persisted** on `WorkItemEntity` and `WorkItem` for consumption at breach time. This contrasts with skill-match routing fields (`routingStrategy`, `minimumScore`) which are transient creation-time metadata consumed by the routing/assignment service and not persisted.

`escalationDeadline` is an ISO-8601 duration (e.g. `PT4H`) persisted on the WorkItem. At breach time, `ExpiryLifecycleService` parses it and applies `now.plus(duration)` to set the new `expiresAt` on the escalated WorkItem. It is NOT consumed at creation time — it is stored and read later.

Skill-match fields already exist: `routingStrategy`, `requiredCapabilities`, `minimumScore`.

### Work: HumanTaskScheduleHandler — Threading

In both `handleInlineMode` and `handleTemplateMode`, read the new config from `HumanTaskTarget` and pass to the `WorkItemCreateRequest.Builder`.

**Template-mode fix:** `handleTemplateMode` currently does not set `.claimDeadlineBusinessHours(target.claimDeadlineHours())` — this is a pre-existing gap (inline mode sets it). This spec fixes the gap because `escalation.onClaimDeadline` requires a claim deadline to trigger. Add `.claimDeadlineBusinessHours(target.claimDeadlineHours())` to the template-mode builder.

```java
if (target.escalation() != null) {
    requestBuilder
        .escalationOnExpiry(target.escalation().onExpiry())
        .escalationOnClaimDeadline(target.escalation().onClaimDeadline())
        .escalationDeadline(target.escalation().deadline())
        .escalationGenerateSummary(target.escalation().generateSummary());
}
if (target.skillMatch() != null) {
    requestBuilder
        .routingStrategy(target.skillMatch().strategy())
        .requiredCapabilities(toCsv(target.skillMatch().requiredCapabilities()))
        .minimumScore(target.skillMatch().minimumScore());
}
```

### Work: WorkItemEntity — Persistence

Add four nullable columns via Flyway migration (V-number per `docs/FLYWAY.md` reservation procedure):

| Column | Type | JPA mapping |
|--------|------|-------------|
| `escalation_on_expiry` | `VARCHAR(255)` | `@Column String escalationOnExpiry` |
| `escalation_on_claim_deadline` | `VARCHAR(255)` | `@Column String escalationOnClaimDeadline` |
| `escalation_deadline` | `VARCHAR(32)` | `@Column String escalationDeadline` |
| `escalation_generate_summary` | `BOOLEAN` | `@Column Boolean escalationGenerateSummary` |

All four are nullable — `NULL` means "no per-item escalation config for this dimension." `ExpiryLifecycleService` reads these fields at breach time via the `WorkItem` SPI record.

### Work: WorkItem SPI — Record expansion

`io.casehub.work.api.WorkItem` is the service-layer interface — all runtime code (including `ExpiryLifecycleService`) operates on this record, not the JPA entity. Additions required:

1. **WorkItem record** — four new components: `String escalationOnExpiry`, `String escalationOnClaimDeadline`, `String escalationDeadline`, `Boolean escalationGenerateSummary`
2. **WorkItem.Builder** — four new builder methods
3. **WorkItem.toBuilder()** — field propagation for all four fields
4. **Entity ↔ WorkItem mapping** — bidirectional mapping in the entity-to-record and record-to-entity conversion code

### Work: ExpiryLifecycleService — Per-item escalation precedence

Per-item YAML escalation config and the `SlaBreachPolicy` SPI are two escalation mechanisms. Their interaction model:

**Per-item config takes precedence; `SlaBreachPolicy` is the fallback.**

When a WorkItem breaches its SLA deadline, `ExpiryLifecycleService` checks for per-item escalation config on the `WorkItem` before consulting the policy:

```java
private BreachDecision resolveBreachDecision(WorkItem item, SlaBreachContext ctx) {
    String target = switch (ctx.breachType()) {
        case COMPLETION_EXPIRED -> item.escalationOnExpiry();
        case CLAIM_EXPIRED -> item.escalationOnClaimDeadline();
    };
    if (target != null) {
        Set<String> currentGroups = parseCandidateGroups(item.candidateGroups());
        if (currentGroups.equals(Set.of(target))) {
            return slaBreachPolicy.onBreach(ctx);
        }
        Duration deadline = null;
        if (item.escalationDeadline() != null) {
            try {
                deadline = Duration.parse(item.escalationDeadline());
            } catch (DateTimeParseException e) {
                LOG.warnf("WorkItem %s has invalid escalationDeadline '%s' — ignoring per-item deadline",
                          item.id(), item.escalationDeadline());
            }
        }
        var decision = EscalateTo.to(target);
        return deadline != null ? decision.withDeadline(deadline) : decision;
    }
    return slaBreachPolicy.onBreach(ctx);
}
```

**Self-detection guard:** Before firing the declarative escalation, the method checks whether `candidateGroups` is **exactly** the target group (single-element set equality). `executeEscalateTo` replaces candidateGroups entirely with `String.join(",", escalate.groups())`, so after a per-item escalation to `"team-leads"`, candidateGroups becomes exactly `"team-leads"`. The `equals(Set.of(target))` check correctly detects this post-escalation state, while a `contains` check would false-positive when the original candidateGroups includes the target alongside other groups. This mirrors the stateless tier-detection pattern from `SlaBreachPolicy` (GE-20260522-f7db12) and prevents infinite re-escalation to the same group.

**Defensive deadline parse:** The persisted `escalationDeadline` string is validated at YAML parse time by `BindingDeserializer`. However, if an invalid value is persisted through an alternative path (REST API without validation, DB corruption, migration error), `Duration.parse()` would throw `DateTimeParseException`. An uncaught `DateTimeParseException` in the breach path would roll back the transaction and re-trigger on every timer tick — an infinite failure loop. The try-catch degrades gracefully: the per-item escalation still fires (correct group), but uses the default deadline from `config.defaultExpiryHours()` instead of the corrupted value.

Rationale for this precedence model:
- Per-item YAML config is **declarative routing** — the YAML author specifies what happens to this specific task on breach
- `SlaBreachPolicy` is the **deployment-wide programmatic default** — consulted only when no per-item config exists, or when per-item config is exhausted (already at escalation tier)
- `BreachedTask` stays unchanged — minimal projection for policy use, no config data leaks into SPI types
- `SlaBreachContext` stays unchanged — no new fields needed
- **No changes** to the `SlaBreachPolicy` SPI contract — existing implementations continue to work for WorkItems without per-item config
- The existing stateless multi-tier pattern (GE-20260522-f7db12) continues to work via `SlaBreachPolicy` for WorkItems that rely on `candidateGroups`-based tier detection

### Work: EscalationSummaryObserver — generateSummary gate

`EscalationSummaryObserver.onEscalation()` currently generates summaries for `EXPIRED` and `CLAIM_EXPIRED` events only. With per-item declarative escalation, the observer must also handle `SLA_REASSIGNED` — this is the event fired by `executeEscalateTo()` when a completion-expiry declarative escalation fires.

**Event semantics and the asymmetry they create:**
- `CLAIM_EXPIRED` fires **before** the breach decision (see `processClaimDeadline` line ~170), so the observer already catches claim-deadline breaches regardless of escalation path.
- Completion-expiry declarative escalation produces `EscalateTo` → `executeEscalateTo` → fires `SLA_REASSIGNED`. No `EXPIRED` event fires (that requires `Fail`). Without this fix, `generateSummary: true` with `onExpiry` would be inert.

**Two event groups, two default semantics:**

| Event | Default (null) | Rationale |
|-------|---------------|-----------|
| `EXPIRED`, `CLAIM_EXPIRED` | summary generated | Breach/failure events — backward compatible, existing behavior |
| `SLA_REASSIGNED` | no summary | Continuation event — opt-in only via per-item `generateSummary` |

```java
void onEscalation(@Observes final WorkItemLifecycleEvent event) {
    final WorkEventType type = event.eventType();
    final WorkItem wi = event.workItem();
    if (wi == null) return;

    switch (type) {
        case SLA_REASSIGNED -> {
            if (Boolean.TRUE.equals(wi.escalationGenerateSummary())) {
                summaryStore.put(summaryService.buildSummary(wi.id(), type.name()));
            }
        }
        case EXPIRED, CLAIM_EXPIRED -> {
            if (Boolean.FALSE.equals(wi.escalationGenerateSummary())) return;
            // Defer to SLA_REASSIGNED when per-item claim-deadline escalation is
            // configured with explicit generateSummary — avoids double summary
            // (CLAIM_EXPIRED fires before the breach decision, SLA_REASSIGNED after).
            if (type == WorkEventType.CLAIM_EXPIRED
                    && wi.escalationOnClaimDeadline() != null
                    && wi.escalationGenerateSummary() != null) return;
            summaryStore.put(summaryService.buildSummary(wi.id(), type.name()));
        }
        default -> { }
    }
}
```

**Duplicate prevention for claim-deadline breaches:** `processClaimDeadline` fires `CLAIM_EXPIRED` *before* the breach decision (pre-decision notification). When per-item `onClaimDeadline` config triggers an `EscalateTo` decision, `executeEscalateTo` fires `SLA_REASSIGNED` (post-decision outcome). Without the skip guard, both events generate summaries. The guard defers to `SLA_REASSIGNED` when per-item config is active with explicit `generateSummary`. The completion-expiry path has no equivalent issue because no pre-decision event fires — the event comes from within `executeEscalateTo` or `executeFail`.

**`escalationGenerateSummary` lifecycle scope:** This flag is per-WorkItem, not per-mechanism. When non-null, it governs all escalation events for that WorkItem, including policy-driven escalations after per-item config exhaustion (self-detection guard fires, falls back to `SlaBreachPolicy`). This is intentional — the YAML author configured this WorkItem for summary generation regardless of which mechanism fires the escalation. WorkItems without per-item config (`escalationGenerateSummary == null`) are unaffected: `SLA_REASSIGNED` produces no summary, preserving backward compatibility with existing `SlaBreachPolicy` implementations.

### Test Fixtures

**Engine tests:**
- `BindingDeserializerTest` — YAML snippet with `escalation:` and `skillMatch:` on humanTask; verify parsed `HumanTaskTarget` carries the config
- `HumanTaskTargetTest` — builder validation (invalid minimumScore, invalid deadline duration)
- Schema validation — YAML fixture validates against updated `CaseDefinition.yaml`

**Work tests:**
- `HumanTaskScheduleHandlerTest` — verify escalation/skillMatch fields flow from `HumanTaskTarget` through to `WorkItemCreateRequest`
- `ExpiryLifecycleServiceTest` — verify per-item escalation config takes precedence over `SlaBreachPolicy`; verify fallback to policy when per-item config is absent; verify `escalationDeadline` duration parsing and application to new `expiresAt`; verify self-detection guard uses exact set equality (original groups including target must NOT bypass per-item escalation); verify invalid persisted `escalationDeadline` degrades gracefully to default deadline
- `EscalationSummaryObserverTest` — verify `SLA_REASSIGNED` with `escalationGenerateSummary=true` generates summary (opt-in); verify `SLA_REASSIGNED` with `null` does NOT generate summary (backward compat); verify `EXPIRED` with `null` generates summary (existing default); verify `EXPIRED` with `escalationGenerateSummary=false` suppresses summary (opt-out); verify `CLAIM_EXPIRED` with `escalationOnClaimDeadline` set and `escalationGenerateSummary=true` does NOT generate summary (deferred to `SLA_REASSIGNED` — duplicate prevention); verify `CLAIM_EXPIRED` with `escalationOnClaimDeadline` set but `escalationGenerateSummary=null` DOES generate summary (no per-item generateSummary override)
- `WorkItemCreateRequestTest` — verify escalation fields are carried through builder and equals/hashCode

## Deferred

### REST API boundary validation for escalation fields

`CreateWorkItemRequest` (rest module) does not currently expose escalation fields. When the REST API is extended to accept `escalationOnExpiry`, `escalationOnClaimDeadline`, `escalationDeadline`, and `escalationGenerateSummary`, the REST boundary must validate `escalationDeadline` (parseable ISO-8601, positive duration) — not rely solely on the defensive breach-time parse in `ExpiryLifecycleService`. Deferred to casehubio/work#369.

### @RequiresQuorum YAML assessment

Issue #362 notes: "assess whether standalone quorum needs a separate YAML field." SubCase already supports `groupId`, `requiredCount`, `totalInGroup`, and `onThresholdReached` in YAML (via `BindingDeserializer.deserializeSubCase()`). Whether standalone quorum outside SubCase needs a separate YAML field is deferred to casehubio/work#367.

## References

- `annotations/runtime/src/main/java/io/casehub/work/annotations/Escalate.java` — annotation definition
- `annotations/runtime/src/main/java/io/casehub/work/annotations/SkillMatch.java` — annotation definition
- `engine/api/src/main/java/io/casehub/api/model/HumanTaskTarget.java` — target model
- `engine/api/src/main/java/io/casehub/api/model/converter/deser/BindingDeserializer.java` — YAML parsing
- `engine/schema/src/main/resources/schema/CaseDefinition.yaml:716` — HumanTask $defs
- `engine-adapter/src/main/java/io/casehub/work/engine/HumanTaskScheduleHandler.java` — adapter wiring
- `api/src/main/java/io/casehub/work/api/WorkItemCreateRequest.java` — create request DTO
- `api/src/main/java/io/casehub/work/api/WorkItem.java` — SPI record
- `api/src/main/java/io/casehub/work/api/BreachedTask.java` — breach projection (unchanged)
- `api/src/main/java/io/casehub/work/api/SlaBreachContext.java` — breach context (unchanged)
- `api/src/main/java/io/casehub/work/api/spi/SlaBreachPolicy.java` — breach policy SPI (unchanged)
- `runtime/src/main/java/io/casehub/work/runtime/service/ExpiryLifecycleService.java` — breach execution
- `runtime/src/main/java/io/casehub/work/runtime/model/WorkItemEntity.java` — JPA entity
- GE-20260511-3e5a75 — SlaBreachPolicy escalation pattern
- GE-20260522-f7db12 — stateless multi-tier escalation via candidateGroups
- casehubio/parent#422 — TypeScript programming model epic
