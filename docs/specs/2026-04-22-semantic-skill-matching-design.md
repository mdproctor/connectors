# Semantic Skill Matching — Design Spec
**Date:** 2026-04-22
**Epic:** #100 — AI-Native WorkItem features
**Status:** Approved for implementation

---

## Overview

Adds semantic skill matching to `WorkerSelectionStrategy`. Instead of only considering
workload (`LeastLoadedStrategy`) or capability tags, `SemanticWorkerSelectionStrategy`
embeds each candidate's skill profile and the work item's requirement, then pre-assigns
to the closest semantic match. The entire stack is pluggable — profile source, scoring
method, and the strategy itself are all SPIs.

---

## New SPIs in `quarkus-work-api`

### `SkillProfile`
```java
public record SkillProfile(
    String narrative,               // prose for embedding matchers
    Map<String, Object> attributes  // structured data for numerical matchers
) {
    public static SkillProfile ofNarrative(String narrative) {
        return new SkillProfile(narrative, Map.of());
    }
}
```

### `SkillProfileProvider`
```java
public interface SkillProfileProvider {
    SkillProfile getProfile(String workerId, Set<String> capabilities);
}
```
One active implementation — CDI `@Alternative @Priority(1)` to override the built-in.
`capabilities` is passed from the pre-resolved `WorkerCandidate` so providers do not
need to re-resolve it.

### `SkillMatcher`
```java
public interface SkillMatcher {
    double score(SkillProfile workerProfile, SelectionContext context);
}
```
Returns a score in any range; higher = better match. One active implementation —
CDI `@Alternative @Priority(1)` to override.

### `SelectionContext` — field additions
Two new fields added to the existing record:
```java
public record SelectionContext(
    String category,
    String priority,
    String requiredCapabilities,
    String candidateGroups,
    String candidateUsers,
    String title,           // new — work item title
    String description      // new — work item description
) {}
```
`WorkItemAssignmentService` populates `title` and `description` from the `WorkItemEntity`
entity when building the context. All existing callers that construct `SelectionContext`
directly (tests, CaseHub) need updating — no external consumers yet.

---

## Implementations in `quarkus-work-ai`

### `CapabilitiesSkillProfileProvider`
Joins `capabilities` into a narrative: `"Worker skills: approval, legal, nda-review"`.
Zero DB access. Useful as a baseline when no richer profile data is available.

### `WorkerProfileSkillProfileProvider`
Reads `WorkerSkillProfile` entity by `workerId`. Returns `SkillProfile.ofNarrative(narrative)`.
Falls back to `SkillProfile.ofNarrative("")` if no profile exists for the worker.

### `ResolutionHistorySkillProfileProvider`
Queries `WorkItemStore` for completed WorkItems where `assigneeId = workerId`.
Aggregates their categories and labels into a frequency summary:
`"Completed work: legal×23, nda×18, finance×4"`.
Capped at the last N items (config: `quarkus.work.ai.semantic.history-limit`,
default 50), ordered by `completedAt` descending.

### `EmbeddingSkillMatcher`
Uses LangChain4j `@Inject EmbeddingModel` (consuming app configures provider via
Quarkus LangChain4j properties — OpenAI, Ollama, Azure, etc.).

```
score(profile, context):
  requirementText = context.title + " " + context.description
                    + " " + context.requiredCapabilities + " " + context.category
  workerEmbedding     = embeddingModel.embed(profile.narrative())
  requirementEmbedding = embeddingModel.embed(requirementText)
  return cosineSimilarity(workerEmbedding, requirementEmbedding)
  // on EmbeddingModel exception → return -1.0
```

### `SemanticWorkerSelectionStrategy`
Annotated `@ApplicationScoped @Alternative @Priority(1)`. Adding `quarkus-work-ai`
to the classpath auto-activates it — `WorkItemAssignmentService.activeStrategy()` picks
up `@Alternative` beans over the config-selected built-in. No beans.xml entry needed
(same pattern as `LowConfidenceFilterProducer`).
```
select(context, candidates):
  for each candidate:
    profile = skillProfileProvider.getProfile(candidate.id(), candidate.capabilities())
    score   = skillMatcher.score(profile, context)
  filter candidates where score > threshold
  if none remain → return noChange() + log WARN
  return assignTo(highest-scoring candidate)
  // catches SkillMatcher exception → noChange() + log WARN (issue #120 tracks fallback)
```

### `WorkerSkillProfile` entity (Flyway V14)

| Column | Type | Notes |
|---|---|---|
| `worker_id` | VARCHAR PK | Unique per worker; not a FK — decoupled from any user table |
| `narrative` | TEXT | Free-text skill description |
| `created_at` | TIMESTAMP | Set on insert |
| `updated_at` | TIMESTAMP | Set on update |

REST at `/worker-skill-profiles`:

| Endpoint | Behaviour |
|---|---|
| `POST /` | Upsert by `workerId`; body: `{workerId, narrative}` |
| `GET /` | List all profiles |
| `GET /{workerId}` | Get single; 404 if absent |
| `DELETE /{workerId}` | Delete; 204 / 404 |

---

## Data Flow

```
WorkItemAssignmentService.assign(workItem, CREATED):
  1. SelectionContext now populated with workItem.title + workItem.description
  2. Candidates resolved via WorkerRegistry + JpaWorkloadProvider (unchanged)
  3. WorkBroker.apply(context, trigger, candidates, semanticStrategy)

SemanticWorkerSelectionStrategy.select(context, candidates):
  4. For each candidate:
       profile = skillProfileProvider.getProfile(candidate.id(), candidate.capabilities())
       score   = skillMatcher.score(profile, context)
  5. Filter by score threshold
  6. Rank descending; return assignTo(best) or noChange()

EmbeddingSkillMatcher.score(profile, context):
  7. Embed profile.narrative() and requirement text
  8. Return cosine similarity ∈ [−1, 1]
  9. Exception → return −1.0 (below any sensible threshold)
```

---

## Configuration

```properties
quarkus.work.ai.semantic.enabled=true         # false = SemanticStrategy is a no-op
quarkus.work.ai.semantic.score-threshold=0.0  # minimum score to pre-assign
quarkus.work.ai.semantic.history-limit=50     # max past WorkItems for history provider
```

---

## Module Placement

| What | Module |
|---|---|
| `SkillProfile`, `SkillProfileProvider`, `SkillMatcher` | `quarkus-work-api` |
| `SelectionContext` (add title + description) | `quarkus-work-api` |
| `CapabilitiesSkillProfileProvider` | `quarkus-work-ai` |
| `WorkerProfileSkillProfileProvider` | `quarkus-work-ai` |
| `ResolutionHistorySkillProfileProvider` | `quarkus-work-ai` |
| `EmbeddingSkillMatcher` | `quarkus-work-ai` |
| `SemanticWorkerSelectionStrategy` | `quarkus-work-ai` |
| `WorkerSkillProfile` entity + REST | `quarkus-work-ai` |
| `WorkItemsAiConfig` semantic sub-group | `quarkus-work-ai` |
| `WorkItemAssignmentService` update | `runtime` |
| `quarkus-langchain4j-core` dependency | `quarkus-work-ai/pom.xml` |

---

## Dependency Graph

```
quarkus-work-api          ← SkillProfile, SkillProfileProvider, SkillMatcher, SelectionContext
       ↑
quarkus-work-core         ← WorkBroker (unchanged; routes to SemanticWorkerSelectionStrategy)
       ↑
quarkus-work         ← WorkItemAssignmentService (populates title+description)
       ↑
quarkus-work-ai      ← all implementations + entity + REST
       ↑
quarkus-langchain4j-core  ← EmbeddingModel (consumer configures provider)
```

---

## Testing

**Unit tests (no Quarkus boot):**
- `SkillProfileTest` — record construction, `ofNarrative()` factory
- `CapabilitiesSkillProfileProviderTest` — capabilities → narrative, empty set, null capabilities
- `WorkerProfileSkillProfileProviderTest` — profile found returns narrative; absent returns empty narrative
- `ResolutionHistorySkillProfileProviderTest` — aggregates categories + labels, respects history-limit, worker with no history → empty narrative
- `EmbeddingSkillMatcherTest` — cosine similarity calculation with stub `EmbeddingModel` returning deterministic vectors; exception → −1.0
- `SemanticWorkerSelectionStrategyTest` — highest scorer assigned, all below threshold → `noChange()`, empty candidates → `noChange()`, matcher exception → `noChange()` with WARN

**Integration tests (`@QuarkusTest`):**
- `WorkerSkillProfileResourceTest` — full CRUD; upsert replaces existing; 404 on missing
- `SemanticRoutingIT` — create WorkItem with two candidateUsers, set profiles via REST,
  use stub `EmbeddingModel` returning fixed vectors; verify pre-assignment goes to
  semantically closer candidate

---

## Out of Scope (this iteration)

- `CompositeSkillProfileProvider` — merge from multiple sources (issue #119)
- `CachedEmbeddingSkillMatcher` / `VectorStoreSkillMatcher` — caching strategies (pluggable via `SkillMatcher` SPI)
- Fallback to `LeastLoadedStrategy` on embedding failure (issue #120)
- `RoundRobinStrategy` — deferred issue #117
