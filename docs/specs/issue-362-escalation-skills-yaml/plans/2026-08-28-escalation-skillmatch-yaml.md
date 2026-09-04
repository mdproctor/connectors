# Escalation and SkillMatch YAML Expansion — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #362 — feat: Escalation and skills YAML — @Escalate, @SkillMatch
**Issue group:** #362

**Goal:** Add `escalation:` and `skillMatch:` YAML config to the `humanTask:` binding schema, with full data flow from YAML parsing through WorkItem persistence to breach-time consumption.

**Architecture:** Engine-side adds inner records to `HumanTaskTarget`, schema entries to `CaseDefinition.yaml`, and parsing to `BindingDeserializer`. Work-side adds fields to `WorkItemCreateRequest`, `WorkItem`, `WorkItemEntity` (with Flyway migration), threads through `HumanTaskScheduleHandler`, and adds per-item escalation precedence logic in `ExpiryLifecycleService` with summary gating in `EscalationSummaryObserver`.

**Tech Stack:** Java 21, Quarkus 3.32.2, JPA/Hibernate, Flyway, Jackson, JUnit 5

## Global Constraints

- Engine repo: `~/claude/casehub/engine`
- Work repo: `~/claude/casehub/work`
- Build: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl <module>`
- Flyway migration path: `runtime/src/main/resources/db/work/migration/`
- Next Flyway V-number: V42 (latest is V41)
- `unevaluatedProperties: false` on HumanTask schema — new properties MUST go under `HumanTask.properties`
- `minimumScore` range: `[0.0, 1.0]` — no `-1.0` sentinel in YAML path (annotation-only)
- `escalationDeadline` is persisted as ISO-8601 string, parsed at breach time — NOT at creation time

---

## Batch 1: Engine — Model, Schema, and Parsing

### Task 1: Add EscalationConfig and SkillMatchConfig to HumanTaskTarget

**Files:**
- Modify: `~/claude/casehub/engine/api/src/main/java/io/casehub/api/model/HumanTaskTarget.java`
- Modify: `~/claude/casehub/engine/schema/src/main/resources/schema/CaseDefinition.yaml:716` (HumanTask $defs)
- Test: `~/claude/casehub/engine/api/src/test/java/io/casehub/api/model/HumanTaskTargetTest.java`

**Interfaces:**
- Produces: `HumanTaskTarget.EscalationConfig(String onExpiry, String onClaimDeadline, String deadline, Boolean generateSummary)`
- Produces: `HumanTaskTarget.SkillMatchConfig(String strategy, Set<String> requiredCapabilities, Double minimumScore)`
- Produces: `HumanTaskTarget.escalation()`, `HumanTaskTarget.skillMatch()` accessors
- Produces: `HumanTaskTarget.Builder.escalation(EscalationConfig)`, `HumanTaskTarget.Builder.skillMatch(SkillMatchConfig)`

- [ ] **Step 1: Write failing test — EscalationConfig validation**

```java
@Test
void escalationConfigRejectsNegativeDuration() {
    assertThrows(IllegalArgumentException.class, () ->
        new HumanTaskTarget.EscalationConfig("team-leads", null, "-PT1H", true));
}

@Test
void escalationConfigRejectsZeroDuration() {
    assertThrows(IllegalArgumentException.class, () ->
        new HumanTaskTarget.EscalationConfig("team-leads", null, "PT0S", true));
}

@Test
void escalationConfigAcceptsValidDuration() {
    var config = new HumanTaskTarget.EscalationConfig("team-leads", "managers", "PT4H", true);
    assertEquals("PT4H", config.deadline());
    assertEquals("team-leads", config.onExpiry());
}

@Test
void escalationConfigAcceptsNullDeadline() {
    var config = new HumanTaskTarget.EscalationConfig("team-leads", null, null, true);
    assertNull(config.deadline());
}
```

- [ ] **Step 2: Write failing test — SkillMatchConfig validation**

```java
@Test
void skillMatchConfigRejectsScoreAboveOne() {
    assertThrows(IllegalArgumentException.class, () ->
        new HumanTaskTarget.SkillMatchConfig("semantic", Set.of("java"), 1.5));
}

@Test
void skillMatchConfigRejectsNegativeScore() {
    assertThrows(IllegalArgumentException.class, () ->
        new HumanTaskTarget.SkillMatchConfig("semantic", Set.of("java"), -0.1));
}

@Test
void skillMatchConfigAcceptsValidScore() {
    var config = new HumanTaskTarget.SkillMatchConfig("semantic", Set.of("java", "security"), 0.7);
    assertEquals("semantic", config.strategy());
    assertEquals(0.7, config.minimumScore());
}

@Test
void skillMatchConfigAcceptsNullScore() {
    var config = new HumanTaskTarget.SkillMatchConfig("keyword", null, null);
    assertNull(config.minimumScore());
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -Dtest=HumanTaskTargetTest -pl api` (in engine repo)
Expected: compilation errors — `EscalationConfig` and `SkillMatchConfig` don't exist yet

- [ ] **Step 4: Implement inner records on HumanTaskTarget**

Add two public inner records inside `HumanTaskTarget`:

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

Add fields, constructor wiring, accessors, and builder methods:
- `private final EscalationConfig escalation;` + `public EscalationConfig escalation()`
- `private final SkillMatchConfig skillMatch;` + `public SkillMatchConfig skillMatch()`
- `Builder.escalation(EscalationConfig)`, `Builder.skillMatch(SkillMatchConfig)`
- Wire in `Builder` field, constructor assignment

- [ ] **Step 5: Write failing test — HumanTaskTarget builder with configs**

```java
@Test
void builderCarriesEscalationAndSkillMatch() {
    var esc = new HumanTaskTarget.EscalationConfig("team-leads", "managers", "PT4H", true);
    var sm = new HumanTaskTarget.SkillMatchConfig("semantic", Set.of("java"), 0.7);
    HumanTaskTarget target = HumanTaskTarget.inline()
        .title("Review")
        .escalation(esc)
        .skillMatch(sm)
        .build();
    assertEquals(esc, target.escalation());
    assertEquals(sm, target.skillMatch());
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -Dtest=HumanTaskTargetTest -pl api` (in engine repo)
Expected: PASS

- [ ] **Step 7: Update CaseDefinition.yaml schema**

Add `escalation` and `skillMatch` under `HumanTask.properties` (after `resolutionType`, before closing of properties block):

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
            description: ISO-8601 duration for the escalated WorkItem's new completion window.
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

- [ ] **Step 8: Commit**

```bash
git -C ~/claude/casehub/engine add api/src/main/java/io/casehub/api/model/HumanTaskTarget.java api/src/test/java/io/casehub/api/model/HumanTaskTargetTest.java schema/src/main/resources/schema/CaseDefinition.yaml
git -C ~/claude/casehub/engine commit -m "feat: add EscalationConfig and SkillMatchConfig to HumanTaskTarget + schema

Refs casehubio/work#362"
```

### Task 2: Add BindingDeserializer parsing and engine integration tests

**Files:**
- Modify: `~/claude/casehub/engine/api/src/main/java/io/casehub/api/model/converter/deser/BindingDeserializer.java`
- Test: `~/claude/casehub/engine/api/src/test/java/io/casehub/api/model/converter/deser/BindingDeserializerTest.java`

**Interfaces:**
- Consumes: `HumanTaskTarget.EscalationConfig`, `HumanTaskTarget.SkillMatchConfig`, `HumanTaskTarget.Builder.escalation()`, `HumanTaskTarget.Builder.skillMatch()`
- Produces: Parsed `HumanTaskTarget` with populated escalation/skillMatch from YAML nodes

- [ ] **Step 1: Write failing test — escalation parsing**

Add to `BindingDeserializerTest`:

```java
@Test
void humanTaskWithEscalation() throws Exception {
    String yaml = """
        name: test-binding
        humanTask:
          title: "Review"
          candidateGroups: [reviewers]
          claimDeadlineHours: 4
          expiresIn: PT8H
          escalation:
            onExpiry: "team-leads"
            onClaimDeadline: "managers"
            deadline: "PT4H"
            generateSummary: true
        on:
          contextChange:
            filter: ".ready == true"
        """;
    Binding result = deserialize(yaml);
    assertInstanceOf(HumanTaskTarget.class, result.target());
    HumanTaskTarget ht = (HumanTaskTarget) result.target();
    assertNotNull(ht.escalation());
    assertEquals("team-leads", ht.escalation().onExpiry());
    assertEquals("managers", ht.escalation().onClaimDeadline());
    assertEquals("PT4H", ht.escalation().deadline());
    assertTrue(ht.escalation().generateSummary());
}
```

- [ ] **Step 2: Write failing test — skillMatch parsing**

```java
@Test
void humanTaskWithSkillMatch() throws Exception {
    String yaml = """
        name: test-binding
        humanTask:
          title: "Review"
          skillMatch:
            strategy: "semantic"
            requiredCapabilities: ["java", "security"]
            minimumScore: 0.7
        on:
          contextChange:
            filter: ".ready == true"
        """;
    Binding result = deserialize(yaml);
    HumanTaskTarget ht = (HumanTaskTarget) result.target();
    assertNotNull(ht.skillMatch());
    assertEquals("semantic", ht.skillMatch().strategy());
    assertEquals(Set.of("java", "security"), ht.skillMatch().requiredCapabilities());
    assertEquals(0.7, ht.skillMatch().minimumScore());
}
```

- [ ] **Step 3: Write failing test — no escalation/skillMatch produces null**

```java
@Test
void humanTaskWithoutEscalationOrSkillMatch() throws Exception {
    String yaml = """
        name: test-binding
        humanTask:
          title: "Simple task"
        on:
          contextChange:
            filter: ".ready == true"
        """;
    Binding result = deserialize(yaml);
    HumanTaskTarget ht = (HumanTaskTarget) result.target();
    assertNull(ht.escalation());
    assertNull(ht.skillMatch());
}
```

- [ ] **Step 4: Run tests to verify they fail**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -Dtest=BindingDeserializerTest -pl api` (in engine repo)
Expected: FAIL — `escalation` and `skillMatch` YAML nodes are unknown/ignored

- [ ] **Step 5: Implement parsing in BindingDeserializer.deserializeHumanTask()**

Add at the end of `deserializeHumanTask()`, before `return b.build()`:

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

- [ ] **Step 6: Run tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -Dtest=BindingDeserializerTest -pl api` (in engine repo)
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git -C ~/claude/casehub/engine add api/src/main/java/io/casehub/api/model/converter/deser/BindingDeserializer.java api/src/test/java/io/casehub/api/model/converter/deser/BindingDeserializerTest.java
git -C ~/claude/casehub/engine commit -m "feat: parse escalation and skillMatch from humanTask YAML

Refs casehubio/work#362"
```

---

## Batch 2: Work — Data Layer and Adapter Threading

### Task 3: Add escalation fields to WorkItemCreateRequest, WorkItem, WorkItemEntity, and Flyway migration

**Files:**
- Modify: `api/src/main/java/io/casehub/work/api/WorkItemCreateRequest.java`
- Modify: `api/src/main/java/io/casehub/work/api/WorkItem.java`
- Modify: `runtime/src/main/java/io/casehub/work/runtime/model/WorkItemEntity.java`
- Modify: `runtime/src/main/java/io/casehub/work/runtime/repository/WorkItemEntityMapper.java`
- Create: `runtime/src/main/resources/db/work/migration/V42__escalation_fields.sql`
- Test: `api/src/test/java/io/casehub/work/api/WorkItemCreateRequestTest.java`

**Interfaces:**
- Produces: `WorkItemCreateRequest.escalationOnExpiry`, `.escalationOnClaimDeadline`, `.escalationDeadline`, `.escalationGenerateSummary` (fields + builder methods)
- Produces: `WorkItem.escalationOnExpiry()`, `.escalationOnClaimDeadline()`, `.escalationDeadline()`, `.escalationGenerateSummary()` (record components + builder methods)
- Produces: `WorkItemEntity.escalationOnExpiry`, `.escalationOnClaimDeadline`, `.escalationDeadline`, `.escalationGenerateSummary` (JPA columns)

- [ ] **Step 1: Write failing test — WorkItemCreateRequest carries escalation fields**

Add to `WorkItemCreateRequestTest`:

```java
@Test
void escalationFieldsCarriedThroughBuilder() {
    WorkItemCreateRequest request = WorkItemCreateRequest.builder()
        .title("Test")
        .escalationOnExpiry("team-leads")
        .escalationOnClaimDeadline("managers")
        .escalationDeadline("PT4H")
        .escalationGenerateSummary(true)
        .build();
    assertEquals("team-leads", request.escalationOnExpiry);
    assertEquals("managers", request.escalationOnClaimDeadline);
    assertEquals("PT4H", request.escalationDeadline);
    assertTrue(request.escalationGenerateSummary);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -Dtest=WorkItemCreateRequestTest -pl api`
Expected: compilation error — fields don't exist

- [ ] **Step 3: Add fields to WorkItemCreateRequest**

Add four fields alongside the existing routing fields:
- `public final String escalationOnExpiry;`
- `public final String escalationOnClaimDeadline;`
- `public final String escalationDeadline;`
- `public final Boolean escalationGenerateSummary;`

Add corresponding Builder fields, builder methods, constructor assignment, equals/hashCode/toString wiring.

- [ ] **Step 4: Add fields to WorkItem record**

Add four record components after `routingExperiences`:
- `String escalationOnExpiry`
- `String escalationOnClaimDeadline`
- `String escalationDeadline`
- `Boolean escalationGenerateSummary`

Add corresponding Builder fields, builder methods, `toBuilder()` propagation.

- [ ] **Step 5: Add columns to WorkItemEntity**

Add four JPA fields:
```java
@Column(name = "escalation_on_expiry")
public String escalationOnExpiry;

@Column(name = "escalation_on_claim_deadline")
public String escalationOnClaimDeadline;

@Column(name = "escalation_deadline", length = 32)
public String escalationDeadline;

@Column(name = "escalation_generate_summary")
public Boolean escalationGenerateSummary;
```

- [ ] **Step 6: Update WorkItemEntityMapper — bidirectional mapping**

In `toDomain()`: map entity fields to WorkItem builder.
In `copyFieldsToEntity()`: map WorkItem fields to entity.

- [ ] **Step 7: Create Flyway migration V42**

Create `runtime/src/main/resources/db/work/migration/V42__escalation_fields.sql`:
```sql
ALTER TABLE work_items ADD COLUMN escalation_on_expiry VARCHAR(255);
ALTER TABLE work_items ADD COLUMN escalation_on_claim_deadline VARCHAR(255);
ALTER TABLE work_items ADD COLUMN escalation_deadline VARCHAR(32);
ALTER TABLE work_items ADD COLUMN escalation_generate_summary BOOLEAN;
```

- [ ] **Step 8: Run tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -Dtest=WorkItemCreateRequestTest -pl api`
Expected: PASS

- [ ] **Step 9: Commit**

```bash
git -C ~/claude/casehub/work add api/src/main/java/io/casehub/work/api/WorkItemCreateRequest.java api/src/main/java/io/casehub/work/api/WorkItem.java runtime/src/main/java/io/casehub/work/runtime/model/WorkItemEntity.java runtime/src/main/java/io/casehub/work/runtime/repository/WorkItemEntityMapper.java runtime/src/main/resources/db/work/migration/V42__escalation_fields.sql api/src/test/java/io/casehub/work/api/WorkItemCreateRequestTest.java
git -C ~/claude/casehub/work commit -m "feat: add escalation fields to WorkItemCreateRequest, WorkItem, WorkItemEntity

Flyway V42 adds four nullable escalation columns.
Refs #362"
```

### Task 4: Thread escalation/skillMatch through HumanTaskScheduleHandler

**Files:**
- Modify: `engine-adapter/src/main/java/io/casehub/work/engine/HumanTaskScheduleHandler.java`
- Test: `engine-adapter/src/test/java/io/casehub/work/engine/HumanTaskScheduleHandlerTest.java`

**Interfaces:**
- Consumes: `HumanTaskTarget.escalation()`, `HumanTaskTarget.skillMatch()`, `WorkItemCreateRequest.Builder.escalationOnExpiry()`, etc.
- Produces: Populated `WorkItemCreateRequest` with escalation and skillMatch data from `HumanTaskTarget`

- [ ] **Step 1: Write failing test — inline mode threads escalation**

```java
@Test
void inlineModeThreadsEscalationConfig() {
    HumanTaskTarget.EscalationConfig esc =
        new HumanTaskTarget.EscalationConfig("team-leads", "managers", "PT4H", true);
    HumanTaskTarget target = HumanTaskTarget.inline()
        .title("Review")
        .escalation(esc)
        .build();
    // ... set up request, invoke schedule, capture WorkItemCreateRequest
    assertEquals("team-leads", capturedRequest.escalationOnExpiry);
    assertEquals("managers", capturedRequest.escalationOnClaimDeadline);
    assertEquals("PT4H", capturedRequest.escalationDeadline);
    assertTrue(capturedRequest.escalationGenerateSummary);
}
```

- [ ] **Step 2: Write failing test — inline mode threads skillMatch**

```java
@Test
void inlineModeThreadsSkillMatchConfig() {
    HumanTaskTarget.SkillMatchConfig sm =
        new HumanTaskTarget.SkillMatchConfig("semantic", Set.of("java", "security"), 0.7);
    HumanTaskTarget target = HumanTaskTarget.inline()
        .title("Review")
        .skillMatch(sm)
        .build();
    // ... set up request, invoke schedule, capture WorkItemCreateRequest
    assertEquals("semantic", capturedRequest.routingStrategy);
    assertEquals("java,security", capturedRequest.requiredCapabilities);
    assertEquals(0.7, capturedRequest.minimumScore);
}
```

- [ ] **Step 3: Write failing test — template mode threads escalation + claimDeadlineBusinessHours fix**

```java
@Test
void templateModeThreadsEscalationAndSetsClaimDeadlineHours() {
    HumanTaskTarget target = HumanTaskTarget.template("template-uuid")
        .claimDeadlineHours(8)
        .escalation(new HumanTaskTarget.EscalationConfig(null, "managers", null, false))
        .build();
    // ... set up request, invoke schedule, capture WorkItemCreateRequest
    assertEquals("managers", capturedRequest.escalationOnClaimDeadline);
    assertEquals(8, capturedRequest.claimDeadlineBusinessHours);
}
```

- [ ] **Step 4: Run tests to verify they fail**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -Dtest=HumanTaskScheduleHandlerTest -pl engine-adapter`
Expected: FAIL — escalation/skillMatch not threaded

- [ ] **Step 5: Implement threading in HumanTaskScheduleHandler**

In both `handleInlineMode`/`createInline` and `handleTemplateMode`, add after the existing builder calls:

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

Also fix template-mode gap: add `.claimDeadlineBusinessHours(target.claimDeadlineHours())` to `handleTemplateMode`.

- [ ] **Step 6: Run tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -Dtest=HumanTaskScheduleHandlerTest -pl engine-adapter`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git -C ~/claude/casehub/work add engine-adapter/src/main/java/io/casehub/work/engine/HumanTaskScheduleHandler.java engine-adapter/src/test/java/io/casehub/work/engine/HumanTaskScheduleHandlerTest.java
git -C ~/claude/casehub/work commit -m "feat: thread escalation/skillMatch from HumanTaskTarget through to WorkItemCreateRequest

Also fixes template-mode gap: claimDeadlineBusinessHours now set in template mode.
Refs #362"
```

---

## Batch 3: Work — Breach-Time Behavior

### Task 5: ExpiryLifecycleService per-item escalation precedence

**Files:**
- Modify: `runtime/src/main/java/io/casehub/work/runtime/service/ExpiryLifecycleService.java`
- Test: `runtime/src/test/java/io/casehub/work/runtime/service/ExpiryLifecycleServiceTest.java`

**Interfaces:**
- Consumes: `WorkItem.escalationOnExpiry()`, `.escalationOnClaimDeadline()`, `.escalationDeadline()`, `.escalationGenerateSummary()`
- Consumes: `BreachDecision.EscalateTo`, `SlaBreachPolicy.onBreach(SlaBreachContext)`
- Produces: Per-item escalation precedence — per-item config fires first, SlaBreachPolicy is fallback

- [ ] **Step 1: Write failing test — per-item onExpiry takes precedence over SlaBreachPolicy**

```java
@Test
void perItemOnExpiryTakesPrecedenceOverPolicy() {
    WorkItem item = workItemWithEscalation("team-leads", null, "PT4H", true);
    // Mock SlaBreachPolicy to return Fail — should NOT be called
    // Trigger completion-expiry breach
    // Assert: candidateGroups changed to "team-leads", not Fail
}
```

- [ ] **Step 2: Write failing test — falls back to SlaBreachPolicy when no per-item config**

```java
@Test
void fallsBackToPolicyWhenNoPerItemConfig() {
    WorkItem item = workItemWithoutEscalation();
    // Mock SlaBreachPolicy to return EscalateTo("senior-group")
    // Trigger completion-expiry breach
    // Assert: SlaBreachPolicy.onBreach() was called
}
```

- [ ] **Step 3: Write failing test — self-detection guard prevents re-escalation**

```java
@Test
void selfDetectionGuardPreventsReEscalation() {
    WorkItem item = workItemWithEscalation("team-leads", null, null, null);
    // Set candidateGroups to exactly "team-leads" (already escalated)
    // Trigger breach — should fall through to SlaBreachPolicy
}
```

- [ ] **Step 4: Write failing test — escalationDeadline applies to escalated WorkItem**

```java
@Test
void escalationDeadlineAppliedToEscalatedWorkItem() {
    WorkItem item = workItemWithEscalation("team-leads", null, "PT4H", true);
    // Trigger breach
    // Assert: EscalateTo has deadline Duration.parse("PT4H")
}
```

- [ ] **Step 5: Write failing test — invalid escalationDeadline degrades gracefully**

```java
@Test
void invalidEscalationDeadlineDegracesGracefully() {
    WorkItem item = workItemWithEscalation("team-leads", null, "INVALID", true);
    // Trigger breach
    // Assert: escalation fires without deadline (no exception thrown)
}
```

- [ ] **Step 6: Run tests to verify they fail**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -Dtest=ExpiryLifecycleServiceTest#perItem* -pl runtime`
Expected: FAIL

- [ ] **Step 7: Implement resolveBreachDecision in ExpiryLifecycleService**

Add the `resolveBreachDecision` method per the spec (checks per-item config first, self-detection guard, defensive deadline parse, falls back to `slaBreachPolicy.onBreach(ctx)`). Wire it into the existing breach handling flow.

- [ ] **Step 8: Run tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -Dtest=ExpiryLifecycleServiceTest -pl runtime`
Expected: PASS

- [ ] **Step 9: Commit**

```bash
git -C ~/claude/casehub/work add runtime/src/main/java/io/casehub/work/runtime/service/ExpiryLifecycleService.java runtime/src/test/java/io/casehub/work/runtime/service/ExpiryLifecycleServiceTest.java
git -C ~/claude/casehub/work commit -m "feat: per-item escalation precedence in ExpiryLifecycleService

Per-item YAML config takes precedence over SlaBreachPolicy SPI.
Self-detection guard prevents infinite re-escalation.
Refs #362"
```

### Task 6: EscalationSummaryObserver generateSummary gate

**Files:**
- Modify: `ai/src/main/java/io/casehub/work/ai/escalation/EscalationSummaryObserver.java`
- Test: `ai/src/test/java/io/casehub/work/ai/escalation/EscalationSummaryObserverTest.java`

**Interfaces:**
- Consumes: `WorkItem.escalationGenerateSummary()`, `WorkItem.escalationOnClaimDeadline()`
- Consumes: `WorkEventType.SLA_REASSIGNED`, `WorkEventType.EXPIRED`, `WorkEventType.CLAIM_EXPIRED`

- [ ] **Step 1: Write failing test — SLA_REASSIGNED with generateSummary=true generates summary**

```java
@Test
void slaReassignedWithGenerateSummaryTrueProducesSummary() {
    WorkItem item = itemWith(escalationGenerateSummary: true);
    fire(WorkEventType.SLA_REASSIGNED, item);
    verify(summaryStore).put(any());
}
```

- [ ] **Step 2: Write failing test — SLA_REASSIGNED with null generateSummary does NOT generate**

```java
@Test
void slaReassignedWithNullGenerateSummaryNoSummary() {
    WorkItem item = itemWith(escalationGenerateSummary: null);
    fire(WorkEventType.SLA_REASSIGNED, item);
    verifyNoInteractions(summaryStore);
}
```

- [ ] **Step 3: Write failing test — EXPIRED with generateSummary=false suppresses**

```java
@Test
void expiredWithGenerateSummaryFalseSuppressed() {
    WorkItem item = itemWith(escalationGenerateSummary: false);
    fire(WorkEventType.EXPIRED, item);
    verifyNoInteractions(summaryStore);
}
```

- [ ] **Step 4: Write failing test — CLAIM_EXPIRED with onClaimDeadline defers to SLA_REASSIGNED**

```java
@Test
void claimExpiredWithPerItemConfigDefersToSlaReassigned() {
    WorkItem item = itemWith(escalationOnClaimDeadline: "managers", escalationGenerateSummary: true);
    fire(WorkEventType.CLAIM_EXPIRED, item);
    verifyNoInteractions(summaryStore); // deferred — SLA_REASSIGNED will handle it
}
```

- [ ] **Step 5: Run tests to verify they fail**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -Dtest=EscalationSummaryObserverTest -pl ai`
Expected: FAIL

- [ ] **Step 6: Implement the gated observer**

Update `onEscalation()` per the spec — switch on event type, check `escalationGenerateSummary`, handle SLA_REASSIGNED opt-in, EXPIRED/CLAIM_EXPIRED defaults, and duplicate prevention for claim-deadline breaches.

- [ ] **Step 7: Run tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -Dtest=EscalationSummaryObserverTest -pl ai`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git -C ~/claude/casehub/work add ai/src/main/java/io/casehub/work/ai/escalation/EscalationSummaryObserver.java ai/src/test/java/io/casehub/work/ai/escalation/EscalationSummaryObserverTest.java
git -C ~/claude/casehub/work commit -m "feat: generateSummary gate on EscalationSummaryObserver

SLA_REASSIGNED opt-in via per-item escalationGenerateSummary.
CLAIM_EXPIRED defers to SLA_REASSIGNED when per-item config active.
Refs #362"
```

---

## References

- [2026-08-28-escalation-skillmatch-yaml-design.md](../2026-08-28-escalation-skillmatch-yaml-design.md) — design spec
- [decisions.md](../decisions.md) — D1-D3
- `engine/api/src/main/java/io/casehub/api/model/HumanTaskTarget.java` — target model
- `engine/api/src/main/java/io/casehub/api/model/converter/deser/BindingDeserializer.java:226` — deserializeHumanTask
- `engine/schema/src/main/resources/schema/CaseDefinition.yaml:716` — HumanTask $defs
- `api/src/main/java/io/casehub/work/api/WorkItemCreateRequest.java` — create request DTO
- `api/src/main/java/io/casehub/work/api/WorkItem.java` — SPI record
- `runtime/src/main/java/io/casehub/work/runtime/model/WorkItemEntity.java` — JPA entity
- `runtime/src/main/java/io/casehub/work/runtime/repository/WorkItemEntityMapper.java` — entity mapper
- `runtime/src/main/java/io/casehub/work/runtime/service/ExpiryLifecycleService.java` — breach execution
- `ai/src/main/java/io/casehub/work/ai/escalation/EscalationSummaryObserver.java` — summary observer
- `engine-adapter/src/main/java/io/casehub/work/engine/HumanTaskScheduleHandler.java` — adapter
- GE-20260522-f7db12 — stateless multi-tier escalation pattern
- GitHub #362 — focal issue
