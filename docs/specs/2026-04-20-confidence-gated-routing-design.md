# Confidence-Gated Routing — Design Spec
**Date:** 2026-04-20  
**Epic:** #100 — AI-Native WorkItem features  
**Status:** Approved for implementation

---

## Overview

Adds confidence-gated routing as the first AI-native feature. An AI agent submits a WorkItem with a `confidenceScore` (0.0–1.0). When the score falls below a configurable threshold the WorkItem automatically receives the label `ai/low-confidence`, signalling human reviewers to apply extra scrutiny.

The mechanism reuses the existing `WorkItemFilter` label-application pattern via two new modules: a generic **filter-registry** (persistent, runtime-configurable filter rules with a pluggable action SPI) and a **workitems-ai** module that ships the low-confidence filter as a CDI-produced permanent rule.

---

## Module Structure

```
runtime (core)
  WorkItem.confidenceScore     Double, nullable
  WorkItemCreateRequest        gains confidenceScore field
  V13 migration                confidence_score DOUBLE column

quarkus-work-filter-registry  (new)
  depends on: runtime
  FilterAction SPI             CDI interface — apply(WorkItem, Map<String,Object>)
  ActionDescriptor             record: type (String), params (Map<String,Object>)
  FilterDefinition             record: name, description, enabled, events, condition, actions
  Built-in actions             ApplyLabelAction, OverrideCandidateGroupsAction, SetPriorityAction
  PermanentFilterRegistry      collects @Produces FilterDefinition CDI beans
  DynamicFilterRegistry        DB-persisted FilterRule entity + CRUD REST
  FilterRegistryEngine         observes WorkItemLifecycleEvent → evaluate → apply
  V3001 migration              filter_rule table (separate prefix from queues V2001)

quarkus-work-ai  (new)
  depends on: runtime + quarkus-work-filter-registry + quarkus-langchain4j (provider-agnostic)
  WorkItemsAiConfig            @ConfigMapping prefix=quarkus.work.ai
  LowConfidenceFilterProducer  @Produces FilterDefinition for ai/low-confidence filter
```

---

## Key Types

### FilterDefinition (record — permanent, CDI-produced)
```
name:        String            // unique identifier, e.g. "ai/low-confidence"
description: String
enabled:     boolean
events:      Set<FilterEvent>  // ADD, UPDATE, REMOVE (default: all three)
condition:   String            // JEXL expression evaluated against workItem
actions:     List<ActionDescriptor>
```

### ActionDescriptor (record)
```
type:    String              // matches CDI bean name: "APPLY_LABEL", "OVERRIDE_CANDIDATE_GROUPS"
params:  Map<String,Object>  // action-specific parameters
```

### FilterAction SPI
```java
// CDI @ApplicationScoped, discovered by bean name matching ActionDescriptor.type
void apply(WorkItem workItem, Map<String, Object> params);
```

### Built-in FilterActions
| Bean name | Params | Effect |
|---|---|---|
| `APPLY_LABEL` | `{path, persistence}` | Calls `WorkItemService.addLabel()` |
| `OVERRIDE_CANDIDATE_GROUPS` | `{groups}` | Mutates `workItem.candidateGroups` |
| `SET_PRIORITY` | `{priority}` | Mutates `workItem.priority` |

### FilterRule (DB entity — dynamic)
```
id, name, description, enabled, condition (TEXT),
events (VARCHAR — comma-separated), actionsJson (TEXT), createdAt
```

---

## Evaluation Engine

`FilterRegistryEngine` is `@ApplicationScoped` and observes `WorkItemLifecycleEvent`:

1. Determine event type (ADD / UPDATE / REMOVE)
2. Collect all enabled `FilterDefinition`s (permanent + DB-loaded) matching the event type
3. For each: evaluate JEXL condition against the WorkItem
4. If condition is true: for each `ActionDescriptor`, resolve CDI bean by type name, call `apply()`

JEXL context exposes `workItem` as the root variable. The JEXL engine is self-contained within the filter-registry — no dependency on the queues module's expression evaluator.

---

## REST API

### Permanent filter rules
```
GET  /filter-rules/permanent                    — list all permanent filters
PUT  /filter-rules/permanent/{name}/enabled     — enable/disable at runtime (in-memory)
```

### Dynamic filter rules
```
POST   /filter-rules          { name, description, condition, events, actions[] }  → 201
GET    /filter-rules                                                                → 200 []
GET    /filter-rules/{id}                                                           → 200 / 404
PUT    /filter-rules/{id}     — update                                              → 200 / 404
DELETE /filter-rules/{id}                                                           → 204 / 404
```

---

## Confidence Gating (quarkus-work-ai)

### Configuration
```properties
quarkus.work.ai.confidence-threshold=0.7
quarkus.work.ai.low-confidence-filter.enabled=true
```

### LowConfidenceFilterProducer
Produces a `FilterDefinition`:
- **condition:** `workItem.confidenceScore != null && workItem.confidenceScore < threshold`
  (`threshold` is exposed as a JEXL context variable by the engine, populated from config;
   the producer passes `{threshold: configValue}` in the filter's condition context params)
- **events:** `{ADD}` only
- **actions:** `[{type: "APPLY_LABEL", params: {path: "ai/low-confidence", persistence: "INFERRED"}}]`
- **enabled:** controlled by `quarkus.work.ai.low-confidence-filter.enabled`

---

## End-to-End Flow

```
AI agent:
  POST /workitems { title, category, confidenceScore: 0.55, candidateGroups: "analysts" }

WorkItemService.create()
  → persists WorkItem with confidenceScore=0.55
  → fires WorkItemLifecycleEvent(ADD, workItem)

FilterRegistryEngine.onLifecycleEvent()
  → evaluates LowConfidence filter condition: 0.55 < 0.7 → true
  → ApplyLabelAction: adds label "ai/low-confidence" (INFERRED) to WorkItem

Result:
  Reviewer: GET /workitems/inbox?label=ai%2F*   → sees low-confidence items
  Reviewer claims, reviews carefully, completes
  Audit trail: ai/low-confidence label was INFERRED (by filter, not human)
```

---

## Testing Strategy

### Unit tests (no Quarkus boot)
- `FilterRegistryEngineTest` — condition true/false/disabled/wrong-event
- `ApplyLabelActionTest`, `OverrideCandidateGroupsActionTest`, `SetPriorityActionTest`

### Integration tests — filter-registry module (@QuarkusTest)
- `PermanentFilterRegistryTest` — CDI-produced filter visible in REST, enable/disable toggle
- `DynamicFilterRegistryTest` — CRUD lifecycle, 201/200/404
- `FilterRuleEvaluationTest` — end-to-end: matching condition fires action, non-matching skips

### Integration tests — quarkus-work-ai module (@QuarkusTest)
- `LowConfidenceFilterTest`:
  - score=0.55 (< 0.7 default) → label applied
  - score=0.85 (≥ 0.7) → no label
  - score=null → no label
  - custom threshold=0.5, score=0.55 → no label
  - filter disabled → no label regardless of score

### E2E
- AI agent creates WorkItem with low confidence → reviewer inbox filter → claim → complete
- Audit trail confirms INFERRED label

---

## Child Issues to Create

| # | Title | Epic |
|---|---|---|
| TBD | `confidenceScore` field on WorkItem + V13 migration | #100 |
| TBD | `quarkus-work-filter-registry` module — FilterAction SPI + built-ins + engine | #100 |
| TBD | Permanent + dynamic filter rule registry + REST API | #100 |
| TBD | `quarkus-work-ai` module — LowConfidenceFilterProducer + config | #100 |

---

## Out of Scope (this iteration)

- File-based (JSON) permanent filter loading — CDI producers only for now
- LLM-backed FilterAction implementations — future child of this epic
- WorkItemRouter SPI — next feature in Epic #100
- Semantic skill matching, resolution suggestion, escalation summarisation — later iterations
