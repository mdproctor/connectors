# Goals and Constraints on AgentDescriptor

**Issue:** casehubio/eidos#100
**Date:** 2026-07-25
**Status:** Approved

## Problem

Agent goals are embedded in the `briefing` text field as natural language.
This prevents programmatic goal visibility control, goal-aware rendering,
and structured constraint enforcement. Goals like "find the diamond" and
constraints like "never break cover" should be first-class descriptor fields
with explicit visibility, priority, and format-discriminated rendering.

## Design Decisions

- **Goals and aims are the same concept** — both collapse into `AgentGoal`
- **Beliefs are out of scope** — pre-loaded knowledge intersects RAG; separate issue
- **No goal-based querying** — goals are identity + rendering only; `AgentQuery` unchanged
- **BDI as orientation vocabulary, not execution architecture** — goals/constraints
  structure the system prompt that bootstraps the LLM; the LLM does the reasoning
- **`AgentGoal` (standing, descriptor) and `GoalContext` (current, prompt context)
  coexist** — same concept at different time scales; no rename needed
- **Rendering order: goals/constraints after capabilities, before disposition** —
  motivation before behavioral style
- **Two priority levels, not three** — issue #100 proposed TERTIARY; dropped because
  the meaningful distinction is main objective vs supporting objective. Three levels
  invite hair-splitting between SECONDARY and TERTIARY with no behavioral difference
  in prompt rendering. Equal-priority goals are expressed by giving multiple goals
  the same level.
- **Briefing may still contain legacy goal text** — descriptor authors should
  migrate goal-related content from `briefing` to the `goals` field. When both
  briefing and goals contain the same objective, the text appears in both sections
  (whether enrichment is on or off). This spec does not enforce or automate
  migration — it is author guidance. No programmatic deduplication is planned.
- **Standing goals in A2A_CARD are consistent with ADR-0002** — ADR-0002 prohibits
  transient `GoalContext` from contaminating A2A cards. Standing `AgentGoal` on the
  descriptor IS stable identity metadata, not transient request state. PUBLIC goals
  in A2A_CARD are descriptor-derived and context-independent.

### Deferred Use Cases (from issue #100)

The following use cases from issue #100 are explicitly out of scope for this spec
and tracked as separate issues:

- **Goal-based routing** — matching agents to tasks by declared goals (eidos#101)
- **Goal-based termination** — detecting when an agent's goal is met (eidos#101)
- **Cross-agent goal awareness** — agents reasoning about others' public goals (eidos#101)
- **Goal-based querying** — filtering via `AgentQuery` by goal fields (eidos#101)
- **`hasGoal(String name)` convenience method** — no current consumer; trivial
  via `goals.stream().anyMatch(g -> g.name().equals(name))` (eidos#101)

## Data Model

### AgentGoal (Tier 1, api/)

```java
public record AgentGoal(
    String name,           // unique within descriptor, <= 100 chars
    String description,    // human/LLM-readable, <= 500 chars
    GoalPriority priority, // PRIMARY or SECONDARY
    Visibility visibility  // PUBLIC or PRIVATE
) {}
```

Validated in compact constructor: name required, description required,
priority required, visibility required. No control characters.
Constants: `MAX_GOAL_NAME = 100`, `MAX_GOAL_DESCRIPTION = 500`.

### GoalPriority (Tier 1, api/)

```java
public enum GoalPriority { PRIMARY, SECONDARY }
```

Two levels. PRIMARY = main objectives. SECONDARY = supporting objectives.
Equal-priority goals expressed by giving multiple goals the same level.

### AgentConstraint (Tier 1, api/)

```java
public record AgentConstraint(
    String name,           // unique within descriptor, <= 100 chars
    String description,    // human/LLM-readable, <= 500 chars
    Visibility visibility  // PUBLIC or PRIVATE
) {}
```

Validated in compact constructor: name required, description required,
visibility required. No control characters.
Constants: `MAX_CONSTRAINT_NAME = 100`, `MAX_CONSTRAINT_DESCRIPTION = 500`.

### Visibility (Tier 1, api/)

```java
public enum Visibility { PUBLIC, PRIVATE }
```

Shared enum for both goals and constraints. PUBLIC items appear in all
render formats including A2A_CARD. PRIVATE items appear only in the
owning agent's system prompt (MARKDOWN/PROSE) and are completely absent
from A2A_CARD — not paraphrased, not hinted at, omitted entirely.

### AgentDescriptor Changes

Two new fields:

```java
public record AgentDescriptor(
    // ... existing 18 fields ...
    List<AgentGoal> goals,
    List<AgentConstraint> constraints
) {}
```

Both nullable in constructor, defaulting to `List.of()`.

Builder gains `.goals(List<AgentGoal>)` and `.constraints(List<AgentConstraint>)`.

Constants: `MAX_GOALS = 10`, `MAX_CONSTRAINTS = 10`.

Convenience methods:
- `publicGoals()` — filters by `Visibility.PUBLIC`
- `publicConstraints()` — filters by `Visibility.PUBLIC`

Validation in compact constructor:
- Goal names unique within the descriptor
- Constraint names unique within the descriptor
- `goals.size() <= MAX_GOALS` and `constraints.size() <= MAX_CONSTRAINTS`

Convention: at least one goal should have PRIMARY priority. Not enforced in
the compact constructor (issue #100 specifies "warning, not error" and
the record validation pattern only supports hard errors). Documented as
guidance for descriptor authors.

## Rendering

### Prompt Section Order

1. Header (name, model, provider)
2. Role (slot)
3. Capabilities
4. **Objectives** (NEW — standing goals from `AgentGoal`)
5. **Constraints** (NEW)
6. How You Operate (disposition)
7. Operating Principles (briefing)
8. Data Handling
9. Current Goal (from GoalContext — transient task context)
10. Resources
11. Context

"Objectives" (not "Goals") as the section heading avoids naming collision with
"Current Goal" — standing identity vs transient task are semantically distinct
but lexically near-identical in a prompt where both sections appear together.
The data model remains `AgentGoal`; only the rendered heading changes.

### Format-Specific Rendering

**MARKDOWN:**
```markdown
## Objectives
- **[PRIMARY]** Find the Doily Diamond
- **[SECONDARY]** Help other treasure hunters

## Constraints
- You see the best in everyone and trust them by default
- You do not notice when you are in personal danger
```

**PROSE:**
Goals as flowing sentence ("Your primary objectives are X and Y.
You also aim to Z."). Constraints as behavioral directives
("You must always... You must never...").

**A2A_CARD (JSON):**
```json
{
  "goals": [
    {"name": "win-treasure", "description": "Win the treasure hunt",
     "priority": "SECONDARY"}
  ],
  "constraints": [
    {"name": "elaborate-schemes",
     "description": "Your schemes must be elaborate and theatrical"}
  ]
}
```
Only PUBLIC items. PRIVATE items completely absent.

### Empty Section Handling

Sections for empty collections are **omitted entirely**, consistent with
how other optional sections (Data Handling, Current Goal, Resources) behave:

- **MARKDOWN/PROSE:** No "Objectives" heading when `goals` is empty.
  No "Constraints" heading when `constraints` is empty.
- **A2A_CARD:** `"goals"` and `"constraints"` keys are absent (not `[]`).
  An agent with no public goals produces no goals key at all.

### Enrichment Pipeline

Goals and constraints render **structurally always**. They are NOT sent
to the LLM enrichment step. Rationale:
- Goals are already natural language — no axis-to-prose conversion needed
- Constraints are behavioral guardrails — LLM rephrasing risks softening
  or losing critical wording

The existing enrichment step continues to handle disposition (axis → prose)
and current goal (GoalContext → narrative).

### Render Ordering

Goals are rendered sorted by priority (PRIMARY before SECONDARY), then
alphabetically by name within each priority level. This ordering is
applied at render time, not via JPA `@OrderBy` — render-time sorting
is deterministic regardless of database insertion order or query plan.

Constraints are rendered sorted alphabetically by name. Without a
deterministic render-time sort, `@OneToMany` without `@OrderBy` produces
non-deterministic ordering after JPA round-trip, causing cache pollution
and flaky test assertions.

### Pipeline Integration

The rendering pipeline is three-stage (Payload Builder → Semantic Enrichment →
Format Assembly). Goals and constraints integrate as follows:

- **Stage 1 (Payload Builder):** Goals and constraints are added to
  `buildDescriptorPayload()` so they contribute to `descriptorHash`.
  This ensures cache invalidation when goals or constraints change.
  PRIVATE goals/constraints are included in the payload for all formats
  except A2A_CARD, where only PUBLIC items appear.
- **Stage 2 (Semantic Enrichment):** Skipped — goals/constraints are
  structural, not enriched.
- **Stage 3 (Format Assembly):** Rendered directly from the `AgentDescriptor`
  argument. Each format assembles its own goals/constraints section from
  the record fields, filtering by visibility as appropriate.

### Cache Coherence

`descriptorHash` is a SHA-256 fingerprint of the serialized descriptor
payload JSON (via `EidosRenderPipeline.fingerprint()`), not
`AgentDescriptor.hashCode()`. Adding goals and constraints to
`buildDescriptorPayload()` automatically includes them in the hash.
Cache invalidation on goal/constraint changes is automatic.

## YAML Format

```yaml
descriptors:
  - agentId: hooded-claw
    name: The Hooded Claw
    slot: villain
    tenancyId: wacky-manor
    goals:
      - name: eliminate-penelope
        description: "Kill Penelope Pitstop before she finds the treasure"
        priority: PRIMARY
        visibility: PRIVATE
      - name: win-treasure
        description: "Win the treasure hunt"
        priority: SECONDARY
        visibility: PUBLIC
    constraints:
      - name: never-break-cover
        description: "Never reveal your true identity as The Hooded Claw"
        visibility: PRIVATE
      - name: elaborate-schemes
        description: "Your schemes must be elaborate and theatrical"
        visibility: PUBLIC
```

New inner config classes `GoalConfig` and `ConstraintConfig` in
`ClasspathYamlDescriptorRegistrar`.

All fields are required — no YAML defaults. `priority` and `visibility`
must be specified explicitly on every goal; `visibility` must be specified
on every constraint. A missing field deserializes as `null` and the
compact constructor throws. This is intentional: defaulting `visibility`
to PUBLIC risks accidentally exposing PRIVATE goals.

## JPA Persistence

Flyway V7 — two new child entity tables:

### agent_goal

| Column | Type | Constraint |
|---|---|---|
| id | BIGINT | PK, auto-generated |
| descriptor_id | BIGINT | FK → agent_descriptor(internal_id), NOT NULL |
| agent_id | VARCHAR(255) | NOT NULL |
| tenancy_id | VARCHAR(255) | NOT NULL |
| name | VARCHAR(100) | NOT NULL |
| description | TEXT | NOT NULL |
| priority | VARCHAR(20) | NOT NULL |
| visibility | VARCHAR(20) | NOT NULL |

### agent_constraint

| Column | Type | Constraint |
|---|---|---|
| id | BIGINT | PK, auto-generated |
| descriptor_id | BIGINT | FK → agent_descriptor(internal_id), NOT NULL |
| agent_id | VARCHAR(255) | NOT NULL |
| tenancy_id | VARCHAR(255) | NOT NULL |
| name | VARCHAR(100) | NOT NULL |
| description | TEXT | NOT NULL |
| visibility | VARCHAR(20) | NOT NULL |

Additional constraint: `UNIQUE (descriptor_id, name)` — enforces name
uniqueness at the storage layer, consistent with compact constructor
validation. Applied to both `agent_goal` and `agent_constraint` tables.

Entity classes `AgentGoalEntity` and `AgentConstraintEntity` follow the
`AgentCapabilityEntity` pattern: `@OneToMany(mappedBy, cascade=ALL,
orphanRemoval=true)` on `AgentDescriptorEntity`.

JPA ordering is intentionally absent (`@OrderBy` not used). Goals are
sorted at render time by priority and name — see §Render Ordering.
Database row ordering is irrelevant to the rendered output.

`AgentDescriptorMapper` gains `toGoal`/`toGoalEntity` and
`toConstraint`/`toConstraintEntity` methods.

## Comparator

`AgentDescriptorComparator` gains `compareGoals()` and `compareConstraints()`
methods following the `compareCapabilities()` pattern:

- Keyed by name
- Detect present/absent drift
- Field-level drift within matching entries

Constants:
- `COMPARED_FIELD_COUNT` incremented by 2
- `COMPARED_GOAL_FIELD_COUNT = 3` (description, priority, visibility)
- `COMPARED_CONSTRAINT_FIELD_COUNT = 2` (description, visibility)

Note: `compareCapabilities()` uses `Collectors.toMap(AgentCapability::name, c -> c)`
which assumes capability names are unique, but the `AgentCapability` compact
constructor does not enforce this — a pre-existing gap (eidos#102). This spec
does not retroactively fix it to avoid scope creep. The new `compareGoals()` and
`compareConstraints()` are safe because `AgentDescriptor` validates goal
and constraint name uniqueness in the compact constructor.

## What This Does NOT Change

- `AgentQuery` — no goal-based querying
- `AgentRegistry.find()` — no filtering by goals
- `GoalContext` / `AgentPromptContext` — unchanged
- `CapabilityHealth` / `BehavioralSignalStore` — goals are identity, not health
- `VocabularyRegistry` — no vocabulary grounding for goals/constraints
- Enrichment pipeline — goals/constraints are structural, not enriched

## Test Coverage

- `AgentGoal` / `AgentConstraint` compact constructor validation (nulls, blanks, length, control chars)
- `AgentDescriptor` goal/constraint name uniqueness validation
- `AgentDescriptor` goal/constraint collection size limit validation
- `AgentDescriptor.publicGoals()` / `publicConstraints()` filtering
- YAML loading with goals and constraints
- YAML loading with missing/empty goals and constraints (backward compat)
- MARKDOWN rendering with goals and constraints sections
- MARKDOWN rendering: goals sorted by priority (PRIMARY first), then by name
- MARKDOWN rendering: constraints sorted alphabetically by name
- PROSE rendering with goals and constraints
- A2A_CARD rendering — PUBLIC only, PRIVATE absent
- Empty goals: no "Objectives" section rendered; A2A_CARD has no `"goals"` key
- Empty constraints: no "Constraints" section rendered; A2A_CARD has no `"constraints"` key
- Combined rendering: descriptor with `AgentGoal` (standing) AND prompt context
  with `GoalContext` (current) renders both "Objectives" and "Current Goal"
  sections with distinguishable headings
- `AgentDescriptorComparator` drift detection for goals and constraints
- JPA round-trip: persist and retrieve goals and constraints
- JPA unique constraint: duplicate goal/constraint names rejected at DB level
- Existing tests remain green (goals/constraints default to empty lists)
