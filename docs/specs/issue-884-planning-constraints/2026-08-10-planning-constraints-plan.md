# Planning Constraints Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #884 — Planning under constraints (time budgets, resource limits)
**Issue group:** #884 (covered by epic #881)

**Goal:** Add cost budgets and priority weights to PlanningConstraints, wire them through LLM prompts and adaptation, add a feasibility audit event, and extend YAML parsing.

**Architecture:** Extends the existing `PlanningConstraints` record with a `costBudgets` map and a `hasHardConstraints()` method. Wires the dead-code `weights` map and new `costBudgets` into LLM prompt constraint text in both decomposition and adaptation paths. Adds `CONSTRAINTS_INFEASIBLE` audit event to `DefaultGoalDecomposer`. Extends YAML parsing in both `CaseDefinitionYamlMapper` and `PatternWorkerFunctionProvider`.

**Tech Stack:** Java 21, Quarkus, JUnit 5, AssertJ, Mockito

## Global Constraints

- Pre-release platform — breaking changes cost nothing
- Engine-only scope — blocks companion tracked as casehubio/blocks#100
- v1 cost enforcement is prompt-hint-only — no runtime metering
- IntelliJ MCP mandatory for all Java edits

---

### Task 1: Extend PlanningConstraints record

**Files:**
- Modify: `api/src/main/java/io/casehub/engine/plan/PlanningConstraints.java`
- Modify: `api/src/test/java/io/casehub/engine/plan/PlanningConstraintsTest.java`

**Interfaces:**
- Produces: `PlanningConstraints(Duration, Integer, Map<String, Double>, Map<String, Integer>)` — 4-arg canonical constructor. `hasHardConstraints()` — returns true when `timeBudget != null || resourceLimit != null || !costBudgets.isEmpty()`.

- [ ] **Step 1: Write failing tests for costBudgets and hasHardConstraints**

```java
@Test
void fullConstructorSetsAllFieldsIncludingCostBudgets() {
  var weights = Map.of("speed", 0.8);
  var costs = Map.of("tokens", 5000, "apiCalls", 10);
  var c = new PlanningConstraints(Duration.ofMinutes(10), 5, weights, costs);
  assertThat(c.timeBudget()).isEqualTo(Duration.ofMinutes(10));
  assertThat(c.resourceLimit()).isEqualTo(5);
  assertThat(c.weights()).containsEntry("speed", 0.8);
  assertThat(c.costBudgets()).containsEntry("tokens", 5000).containsEntry("apiCalls", 10);
}

@Test
void costBudgetsMapIsUnmodifiable() {
  var c = new PlanningConstraints(null, null, Map.of(), Map.of("tokens", 100));
  assertThat(c.costBudgets()).isUnmodifiable();
}

@Test
void costBudgetsDefaultsToEmptyWhenNull() {
  var c = new PlanningConstraints(null, null, null, null);
  assertThat(c.costBudgets()).isEmpty();
}

@Test
void hasHardConstraintsReturnsTrueForTimeBudget() {
  var c = PlanningConstraints.of(Duration.ofMinutes(5), null);
  assertThat(c.hasHardConstraints()).isTrue();
}

@Test
void hasHardConstraintsReturnsTrueForCostBudgets() {
  var c = new PlanningConstraints(null, null, Map.of(), Map.of("tokens", 100));
  assertThat(c.hasHardConstraints()).isTrue();
}

@Test
void hasHardConstraintsReturnsFalseForWeightsOnly() {
  var c = new PlanningConstraints(null, null, Map.of("speed", 0.8), Map.of());
  assertThat(c.hasHardConstraints()).isFalse();
}

@Test
void hasHardConstraintsReturnsFalseWhenUnconstrained() {
  assertThat(PlanningConstraints.unconstrained().hasHardConstraints()).isFalse();
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl api -Dtest=PlanningConstraintsTest -f /Users/mdproctor/claude/casehub/slots/105/engine/pom.xml`
Expected: Compilation failure — `costBudgets` component and `hasHardConstraints()` don't exist yet.

- [ ] **Step 3: Implement PlanningConstraints changes**

Use `ide_edit_member` to replace the `PlanningConstraints` record declaration. The new record has 4 components, updated compact constructor, updated factories, and a new `hasHardConstraints()` method.

```java
public record PlanningConstraints(
    Duration timeBudget, Integer resourceLimit, Map<String, Double> weights,
    Map<String, Integer> costBudgets) {

  public PlanningConstraints {
    weights = weights != null ? Map.copyOf(weights) : Map.of();
    costBudgets = costBudgets != null ? Map.copyOf(costBudgets) : Map.of();
  }

  public static PlanningConstraints unconstrained() {
    return new PlanningConstraints(null, null, Map.of(), Map.of());
  }

  public static PlanningConstraints of(Duration timeBudget, Integer resourceLimit) {
    return new PlanningConstraints(timeBudget, resourceLimit, Map.of(), Map.of());
  }

  public boolean hasHardConstraints() {
    return timeBudget != null || resourceLimit != null || !costBudgets.isEmpty();
  }
}
```

- [ ] **Step 4: Fix existing 3-arg constructor call sites**

The canonical constructor is now 4 args. `CaseDefinitionYamlMapper` line 614 calls `new PlanningConstraints(timeBudget, resourceLimit, weights)` — this will fail. Use `ide_find_references` on the `PlanningConstraints` constructor to find all 3-arg call sites. Fix each by appending `Map.of()` as the 4th argument. Key sites:
- `CaseDefinitionYamlMapper.java` line 614: `new PlanningConstraints(timeBudget, resourceLimit, weights)` → `new PlanningConstraints(timeBudget, resourceLimit, weights, Map.of())`

- [ ] **Step 5: Update existing PlanningConstraintsTest assertions**

The existing `fullConstructorSetsAllFields` test calls the 3-arg constructor. Update to 4-arg.

- [ ] **Step 6: Run tests to verify they pass**

Run: `mvn test -pl api -Dtest=PlanningConstraintsTest -f /Users/mdproctor/claude/casehub/slots/105/engine/pom.xml`
Expected: All PASS

- [ ] **Step 7: Run ide_diagnostics to verify no compilation errors across api module**

- [ ] **Step 8: Commit**

```
feat(#884): add costBudgets and hasHardConstraints to PlanningConstraints
```

---

### Task 2: Extend LLM decomposition prompt with costBudgets and weights

**Files:**
- Modify: `planning/src/main/java/io/casehub/engine/planning/decomposition/LlmDecompositionStrategy.java`
- Modify: `planning/src/test/java/io/casehub/engine/planning/decomposition/LlmDecompositionStrategyTest.java`

**Interfaces:**
- Consumes: `PlanningConstraints.costBudgets()`, `PlanningConstraints.weights()` from Task 1
- Produces: `buildConstraintText(PlanningConstraints)` — renders all four constraint types into prompt text

- [ ] **Step 1: Write failing tests**

```java
@Test
void includesCostBudgetsInPromptWhenPresent() {
  var capturedPrompt = new java.util.concurrent.atomic.AtomicReference<String>();
  // ... same capturing pattern as includesConstraintsInPromptWhenPresent ...

  var constraints = new io.casehub.engine.plan.PlanningConstraints(
      null, null, Map.of(), Map.of("tokens", 5000, "apiCalls", 10));
  var context = new GoalDecompositionContext(
      MAPPER.createObjectNode(), 0,
      List.of(new Capability("analysis", "", "", null)), constraints);
  var task = new TaskNode.CompoundTask<JsonNode>("research", "research", List.of());

  strategy.decompose(task, context).await().indefinitely();

  assertThat(capturedPrompt.get()).contains("5000").contains("apiCalls");
}

@Test
void includesWeightsInPromptWhenPresent() {
  var capturedPrompt = new java.util.concurrent.atomic.AtomicReference<String>();
  // ... same capturing pattern ...

  var constraints = new io.casehub.engine.plan.PlanningConstraints(
      null, null, Map.of("speed", 0.8, "quality", 0.2), Map.of());
  var context = new GoalDecompositionContext(
      MAPPER.createObjectNode(), 0,
      List.of(new Capability("analysis", "", "", null)), constraints);
  var task = new TaskNode.CompoundTask<JsonNode>("research", "research", List.of());

  strategy.decompose(task, context).await().indefinitely();

  assertThat(capturedPrompt.get()).contains("speed").contains("0.8").contains("quality");
}

@Test
void rendersCostBudgetsOnlyWithNoTimeBudgetOrResourceLimit() {
  var capturedPrompt = new java.util.concurrent.atomic.AtomicReference<String>();
  // ... same capturing pattern ...

  var constraints = new io.casehub.engine.plan.PlanningConstraints(
      null, null, Map.of(), Map.of("tokens", 3000));
  var context = new GoalDecompositionContext(
      MAPPER.createObjectNode(), 0,
      List.of(new Capability("analysis", "", "", null)), constraints);
  var task = new TaskNode.CompoundTask<JsonNode>("research", "research", List.of());

  strategy.decompose(task, context).await().indefinitely();

  assertThat(capturedPrompt.get()).contains("3000").contains("Constraints:");
}

@Test
void omitsConstraintTextWhenUnconstrained() {
  var capturedPrompt = new java.util.concurrent.atomic.AtomicReference<String>();
  // ... same capturing pattern ...

  var context = new GoalDecompositionContext(
      MAPPER.createObjectNode(), 0,
      List.of(new Capability("analysis", "", "", null)));
  var task = new TaskNode.CompoundTask<JsonNode>("research", "research", List.of());

  strategy.decompose(task, context).await().indefinitely();

  assertThat(capturedPrompt.get()).doesNotContain("Constraints:");
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl planning -Dtest=LlmDecompositionStrategyTest -f /Users/mdproctor/claude/casehub/slots/105/engine/pom.xml`
Expected: FAIL — costBudgets/weights not rendered, guard blocks cost-only constraints

- [ ] **Step 3: Update buildConstraintText()**

Use `ide_replace_member` on `buildConstraintText` in `LlmDecompositionStrategy`:

```java
if (constraints.timeBudget() == null && constraints.resourceLimit() == null
    && constraints.costBudgets().isEmpty() && constraints.weights().isEmpty()) {
  return "";
}
var sb = new StringBuilder("Constraints:\n");
if (constraints.timeBudget() != null) {
  long minutes = constraints.timeBudget().toMinutes();
  sb.append("- Time budget: ").append(minutes).append(" minutes. ");
  sb.append("Plan steps that can complete within this time.\n");
}
if (constraints.resourceLimit() != null) {
  sb.append("- Resource limit: ").append(constraints.resourceLimit());
  sb.append(" available agents. Prefer parallelism when resource limits allow.\n");
}
for (var entry : constraints.costBudgets().entrySet()) {
  var key = entry.getKey();
  var label = Character.toUpperCase(key.charAt(0)) + key.substring(1);
  sb.append("- ").append(label).append(" budget: ").append(entry.getValue());
  sb.append(". Plan steps that stay within this ").append(key).append(" budget.\n");
}
if (!constraints.weights().isEmpty()) {
  sb.append("- Priority weights: ");
  var entries = constraints.weights().entrySet().stream()
      .map(e -> e.getKey() + "=" + e.getValue())
      .collect(java.util.stream.Collectors.joining(", "));
  sb.append(entries);
  sb.append(". Prioritize steps aligned with higher-weighted dimensions. ");
  sb.append("If constraints force trade-offs, keep steps serving high-weight priorities.\n");
}
return sb.toString();
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -pl planning -Dtest=LlmDecompositionStrategyTest -f /Users/mdproctor/claude/casehub/slots/105/engine/pom.xml`
Expected: All PASS

- [ ] **Step 5: Commit**

```
feat(#884): wire costBudgets and weights into LLM decomposition prompt
```

---

### Task 3: Add constraint text to ForwardReplanRevision

**Files:**
- Modify: `planning/src/main/java/io/casehub/engine/planning/adaptation/ForwardReplanRevision.java`
- Modify: `planning/src/test/java/io/casehub/engine/planning/adaptation/ForwardReplanRevisionTest.java`

**Interfaces:**
- Consumes: `RevisionContext.adaptationContext().definition().getPlanningConstraints()` — accesses constraints from the case definition
- Produces: Constraint text appended to the revision prompt

- [ ] **Step 1: Write failing test**

```java
@Test
void includesConstraintTextInRevisionPrompt() {
  var capturedPrompt = new java.util.concurrent.atomic.AtomicReference<String>();

  ChatModel capturingModel = new ChatModel() {
    @Override
    public ChatResponse doChat(ChatRequest request) {
      for (var msg : request.messages()) {
        if (msg instanceof dev.langchain4j.data.message.UserMessage um) {
          capturedPrompt.set(um.singleText());
        }
      }
      return ChatResponse.builder().aiMessage(AiMessage.from(
          "{\"steps\": [{\"id\": \"s1\", \"description\": \"step\", \"capabilityName\": \"analysis\"}]}"
      )).build();
    }
  };

  ChatModelProvider provider = new ChatModelProvider() {
    @Override public ModelType type() { return ModelType.ANTHROPIC; }
    @Override public ChatModel get() { return capturingModel; }
  };

  var revision = new ForwardReplanRevision();
  setField(revision, "chatModelProviders", satisfiedInstance(provider));

  var constraints = new PlanningConstraints(
      Duration.ofMinutes(30), 3, Map.of("speed", 0.8), Map.of("tokens", 5000));
  var definition = CaseDefinition.builder("test", "io.test", "1.0").build();
  definition.setPlanningConstraints(constraints);

  var adaptCtx = new AdaptationContext(
      UUID.randomUUID(), "tenant-1", "compound-1", "analyse",
      List.of(), List.of(), List.of(),
      new ObjectMapper().createObjectNode(), definition,
      TaskStatus.COMPLETED, "binding-1", 0);
  var cause = new AdaptationCause.StepCompleted("step-1");
  var context = new RevisionContext(adaptCtx, cause,
      List.of(new Capability("analysis", "", "", null)), List.of());

  revision.revise(context).await().indefinitely();

  assertThat(capturedPrompt.get())
      .contains("30 minutes")
      .contains("3")
      .contains("5000")
      .contains("speed");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl planning -Dtest=ForwardReplanRevisionTest -f /Users/mdproctor/claude/casehub/slots/105/engine/pom.xml`
Expected: FAIL — no constraint text in prompt

- [ ] **Step 3: Add constraint rendering to ForwardReplanRevision**

Use `ide_insert_member` to add a `buildConstraintText` method (duplicated from `LlmDecompositionStrategy` — different packages, package-private sharing not possible). Then use `ide_replace_member` on `revise` to append constraint text to the user prompt, sourced from `context.adaptationContext().definition().getPlanningConstraints()`.

The key addition to the `revise` method is after building `userPrompt`:
```java
var planningConstraints = adaptCtx.definition().getPlanningConstraints();
if (planningConstraints != null) {
  var constraintText = buildConstraintText(planningConstraints);
  if (!constraintText.isEmpty()) {
    userPrompt = userPrompt + "\n\n" + constraintText;
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl planning -Dtest=ForwardReplanRevisionTest -f /Users/mdproctor/claude/casehub/slots/105/engine/pom.xml`
Expected: All PASS

- [ ] **Step 5: Commit**

```
feat(#884): add constraint text to ForwardReplanRevision prompt
```

---

### Task 4: Add CONSTRAINTS_INFEASIBLE audit event

**Files:**
- Modify: `api/src/main/java/io/casehub/api/model/event/CaseHubEventType.java`
- Modify: `planning/src/main/java/io/casehub/engine/planning/decomposition/DefaultGoalDecomposer.java`
- Modify: `planning/src/test/java/io/casehub/engine/planning/decomposition/GoalDecompositionContextTest.java`

**Interfaces:**
- Consumes: `PlanningConstraints.hasHardConstraints()` from Task 1
- Produces: `CONSTRAINTS_INFEASIBLE` EventLog written when empty plan + hard constraints

- [ ] **Step 1: Add CONSTRAINTS_INFEASIBLE to CaseHubEventType**

Use `ide_insert_member` to add after `GOAL_PROPOSED`:
```java
CONSTRAINTS_INFEASIBLE,
```

- [ ] **Step 2: Write failing test for DefaultGoalDecomposer**

Create a new test class. The test needs to set up a strategy that returns an empty plan (all capabilities unknown), apply non-trivial constraints, and verify a `CONSTRAINTS_INFEASIBLE` event is written to the EventLog.

```java
@Test
void emitsConstraintsInfeasibleWhenEmptyPlanWithHardConstraints() {
  // Setup: strategy returns plan with steps referencing unknown capabilities
  // Definition has hard constraints (timeBudget set)
  // After capability filtering, validNodes is empty
  // Verify: EventLogRepository receives CONSTRAINTS_INFEASIBLE event
}

@Test
void noInfeasibleEventWhenEmptyPlanWithoutConstraints() {
  // Same setup but definition has no constraints
  // Verify: no CONSTRAINTS_INFEASIBLE event written
}
```

Since `DefaultGoalDecomposer` has many CDI injections, this test will inject mocks for `EngineStrategyResolver`, `GoalAbandonmentEvaluator`, `BlackboardRegistry`, `PlanItemStore`, and `EventLogRepository`. The recording mock on `EventLogRepository` verifies the event type.

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn test -pl planning -Dtest=DefaultGoalDecomposerTest -f /Users/mdproctor/claude/casehub/slots/105/engine/pom.xml`
Expected: FAIL — no CONSTRAINTS_INFEASIBLE event emitted

- [ ] **Step 4: Add feasibility check to DefaultGoalDecomposer.decomposeGoal()**

In `decomposeGoal()`, after `if (validNodes.isEmpty()) return;` on line 173, insert the infeasible event emission before the return:

```java
if (validNodes.isEmpty()) {
  if (definition.getPlanningConstraints() != null
      && definition.getPlanningConstraints().hasHardConstraints()) {
    var infeasibleLog = new EventLog();
    infeasibleLog.setCaseId(instance.getUuid());
    infeasibleLog.setEventType(CaseHubEventType.CONSTRAINTS_INFEASIBLE);
    infeasibleLog.setStreamType(EventStreamType.CASE);
    infeasibleLog.setTimestamp(Instant.now());
    var infeasibleMeta = OBJECT_MAPPER.createObjectNode();
    infeasibleMeta.put("goalName", goal.name());
    infeasibleMeta.put("strategyId", definition.getDecompositionStrategy());
    var pc = definition.getPlanningConstraints();
    if (pc.timeBudget() != null) {
      infeasibleMeta.put("timeBudget", pc.timeBudget().toString());
    }
    if (pc.resourceLimit() != null) {
      infeasibleMeta.put("resourceLimit", pc.resourceLimit());
    }
    if (!pc.costBudgets().isEmpty()) {
      infeasibleMeta.set("costBudgets", OBJECT_MAPPER.valueToTree(pc.costBudgets()));
    }
    infeasibleLog.setMetadata(infeasibleMeta);
    eventLogRepository.append(infeasibleLog, instance.tenancyId);
  }
  return;
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn test -pl planning -Dtest=DefaultGoalDecomposerTest -f /Users/mdproctor/claude/casehub/slots/105/engine/pom.xml`
Expected: All PASS

- [ ] **Step 6: Commit**

```
feat(#884): emit CONSTRAINTS_INFEASIBLE audit event on empty plan with hard constraints
```

---

### Task 5: Extend YAML parsing for costBudgets

**Files:**
- Modify: `api/src/main/java/io/casehub/api/model/converter/CaseDefinitionYamlMapper.java`
- Modify: `api/src/test/java/io/casehub/api/model/converter/CaseDefinitionYamlMapperTest.java`
- Modify: `agentic-engine/src/main/java/io/casehub/engine/agentic/PatternWorkerFunctionProvider.java`
- Modify: `agentic-engine/src/test/java/io/casehub/engine/agentic/PatternWorkerFunctionProviderTest.java`

**Interfaces:**
- Consumes: `PlanningConstraints` 4-arg constructor from Task 1
- Produces: YAML `spec.planningConstraints.costBudgets:` and `pattern.constraints.costBudgets:` parsed correctly

- [ ] **Step 1: Write failing tests for CaseDefinitionYamlMapper**

```java
@Test
void parsesCostBudgetsFromPlanningConstraints() throws Exception {
  var yaml = """
      name: test
      namespace: io.casehub.test
      version: "1.0"
      spec:
        planningConstraints:
          timeBudget: PT30M
          resourceLimit: 3
          costBudgets:
            tokens: 5000
            apiCalls: 10
        capabilities:
          - name: analysis
      """;
  var def = CaseDefinitionYamlMapper.load(new java.io.ByteArrayInputStream(yaml.getBytes()));

  assertThat(def.getPlanningConstraints().costBudgets())
      .containsEntry("tokens", 5000)
      .containsEntry("apiCalls", 10);
}

@Test
void costBudgetsEmptyWhenNotSpecified() throws Exception {
  var yaml = """
      name: test
      namespace: io.casehub.test
      version: "1.0"
      spec:
        planningConstraints:
          timeBudget: PT30M
        capabilities:
          - name: analysis
      """;
  var def = CaseDefinitionYamlMapper.load(new java.io.ByteArrayInputStream(yaml.getBytes()));

  assertThat(def.getPlanningConstraints().costBudgets()).isEmpty();
}
```

- [ ] **Step 2: Write failing test for PatternWorkerFunctionProvider**

```java
@Test
void parsesCostBudgetsFromPatternConstraints() {
  ObjectNode node = mapper.createObjectNode();
  ObjectNode pattern = node.putObject("pattern");
  pattern.put("type", "debate");
  ObjectNode constraints = pattern.putObject("constraints");
  constraints.put("timeBudget", "PT15M");
  ObjectNode costs = constraints.putObject("costBudgets");
  costs.put("tokens", 3000);

  var fn = (PatternWorkerFunction) provider.create(node);
  assertThat(fn.planningConstraints()).isNotNull();
  assertThat(fn.planningConstraints().costBudgets()).containsEntry("tokens", 3000);
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `mvn test -pl api -Dtest=CaseDefinitionYamlMapperTest#parsesCostBudgetsFromPlanningConstraints -f /Users/mdproctor/claude/casehub/slots/105/engine/pom.xml`
Run: `mvn test -pl agentic-engine -Dtest=PatternWorkerFunctionProviderTest#parsesCostBudgetsFromPatternConstraints -f /Users/mdproctor/claude/casehub/slots/105/engine/pom.xml`
Expected: FAIL — costBudgets not parsed

- [ ] **Step 4: Update CaseDefinitionYamlMapper**

In the `planningConstraints` parsing block (around line 597-615), add costBudgets parsing and update the constructor call:

```java
Map<String, Integer> costBudgets = new LinkedHashMap<>();
if (pcNode.has("costBudgets") && pcNode.get("costBudgets").isObject()) {
  pcNode.get("costBudgets").fields()
      .forEachRemaining(e -> costBudgets.put(e.getKey(), e.getValue().asInt()));
}
def.setPlanningConstraints(
    new io.casehub.engine.plan.PlanningConstraints(timeBudget, resourceLimit, weights, costBudgets));
```

- [ ] **Step 5: Update PatternWorkerFunctionProvider**

In the constraints parsing block (around line 39-49), add costBudgets parsing and update the factory call:

```java
Map<String, Integer> costBudgets = new java.util.LinkedHashMap<>();
if (cNode.has("costBudgets") && cNode.get("costBudgets").isObject()) {
  cNode.get("costBudgets").fields()
      .forEachRemaining(e -> costBudgets.put(e.getKey(), e.getValue().asInt()));
}
constraints = new io.casehub.engine.plan.PlanningConstraints(
    timeBudget, resourceLimit, java.util.Map.of(), costBudgets);
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `mvn test -pl api -Dtest=CaseDefinitionYamlMapperTest -f /Users/mdproctor/claude/casehub/slots/105/engine/pom.xml`
Run: `mvn test -pl agentic-engine -Dtest=PatternWorkerFunctionProviderTest -f /Users/mdproctor/claude/casehub/slots/105/engine/pom.xml`
Expected: All PASS

- [ ] **Step 7: Commit**

```
feat(#884): parse costBudgets from YAML planningConstraints and pattern constraints
```

---

### Task 6: Extend GoalDecompositionContext test and full verification

**Files:**
- Modify: `planning/src/test/java/io/casehub/engine/planning/decomposition/GoalDecompositionContextTest.java`

**Interfaces:**
- Consumes: `PlanningConstraints` with `costBudgets` from Task 1

- [ ] **Step 1: Write test for costBudgets threading**

```java
@Test
void costBudgetsThreadedThroughConstraints() {
  var constraints = new PlanningConstraints(
      Duration.ofMinutes(30), 3, Map.of(), Map.of("tokens", 5000));
  var ctx = new GoalDecompositionContext(MAPPER.createObjectNode(), 0, List.of(), constraints);
  assertThat(ctx.constraints().costBudgets()).containsEntry("tokens", 5000);
}
```

- [ ] **Step 2: Run test to verify it passes**

Run: `mvn test -pl planning -Dtest=GoalDecompositionContextTest -f /Users/mdproctor/claude/casehub/slots/105/engine/pom.xml`
Expected: PASS (this should pass immediately — costBudgets flows through the record automatically)

- [ ] **Step 3: Run full test suite across affected modules**

Run: `mvn test -pl api,planning,agentic-engine -f /Users/mdproctor/claude/casehub/slots/105/engine/pom.xml`
Expected: All PASS

- [ ] **Step 4: Commit**

```
test(#884): add costBudgets threading test for GoalDecompositionContext
```

---

### Task 7: Update CLAUDE.md and verify

- [ ] **Step 1: Update CLAUDE.md PlanningConstraints section**

Update the `PlanningConstraints` documentation to reflect the new `costBudgets` field, `hasHardConstraints()` method, and YAML schema changes.

- [ ] **Step 2: Run full build**

Run: `mvn clean test -f /Users/mdproctor/claude/casehub/slots/105/engine/pom.xml`
Expected: BUILD SUCCESS
