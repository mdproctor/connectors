# Quarkus WorkItems — Labels, Filters, and Queues Design

> *This document covers the label model, vocabulary, filter engine, and queue
> views. The queues feature is an optional module — `quarkus-work-queues`
> — built on top of label infrastructure in the core extension.*

---

## Design Principle: Labels Are Queues

A queue is not a separate entity. A queue is a **label** viewed through a
**named query**. Any WorkItem carrying that label is in that queue. The filter
engine decides which labels a WorkItem carries. The queue view decides how to
display them.

This keeps the core model simple: one set of labels per WorkItem, one filter
engine that maintains them, one query per queue view.

---

## Module Boundary

| Concern | Module |
|---|---|
| `WorkItemLabelEntity` type | `quarkus-work` core |
| `labels` collection on `WorkItemEntity` | `quarkus-work` core |
| `LabelVocabulary` + `LabelDefinition` | `quarkus-work` core |
| Label REST endpoints (add, remove, query by label) | `quarkus-work` core |
| `WorkItemFilter` (conditions + actions) | `quarkus-work-queues` |
| `FilterChain` (derivation graph + inverse index) | `quarkus-work-queues` |
| `WorkItemExpressionEvaluator` SPI + `ExpressionDescriptor` + built-ins | `quarkus-work-queues` |
| Filter evaluation engine | `quarkus-work-queues` |
| `QueueView` (named query over a label) | `quarkus-work-queues` |
| Queue REST endpoints | `quarkus-work-queues` |

The integration seam is `WorkItemLifecycleEvent` — already fired by
`WorkItemService` on every creation and mutation. The queue module observes
this event to trigger filter re-evaluation. If the module is absent, the event
fires into the void. The core is unchanged.

```
quarkus-work-parent
├── quarkus-work                  ← core (labels, vocabulary)
├── quarkus-work-deployment
├── quarkus-work-testing
├── quarkus-work-flow
├── quarkus-work-ledger
└── quarkus-work-queues           ← optional (filters, chains, queue views)
```

---

## Label Model

### WorkItemLabel

Each WorkItem carries a single ordered collection of labels:

```java
// WorkItem.java
List<WorkItemLabel> labels;  // 0..n entries
```

Each entry:

| Field | Type | Description |
|---|---|---|
| `path` | `String` | The label — a `/`-separated path, e.g. `legal`, `legal/contracts/nda` |
| `persistence` | `LabelPersistence` | `MANUAL` or `INFERRED` |
| `appliedBy` | `String` | userId — for MANUAL labels only |
| `supporters` | `Set<UUID>` | chainIds — for INFERRED labels only; label survives while non-empty |

```java
public enum LabelPersistence {
    MANUAL,   // human-applied; only a human or explicit API call removes it
    INFERRED  // filter-applied; maintained by the FilterChain graph
}
```

**Invariant:** an INFERRED label exists on a WorkItem if and only if its
`supporters` set is non-empty. When the last supporter is removed, the label
is removed with it.

### Label Path Semantics

A label is a `/`-separated sequence of terms. Each term is a single word.

```
legal                        ← single-term label
legal/contracts              ← two-segment path
legal/contracts/nda          ← three-segment path
```

A single-term label is identical in structure to a multi-segment one — there
is no distinction between "label" and "path label". The `/` separator is
purely a scoping convention.

**Wildcard matching** (used in filter conditions and queue queries):

| Pattern | Matches |
|---|---|
| `legal` | exactly `legal` |
| `legal/*` | one level below: `legal/contracts`, `legal/ip` — not `legal/contracts/nda` |
| `legal/**` | all descendants: `legal/contracts`, `legal/contracts/nda`, etc. |

### Relationship to `category`

`WorkItem.category` (existing field) is semantically equivalent to a MANUAL
label at the `category/<value>` path. It is kept as a dedicated field for
backwards compatibility and query convenience. Internally, it can be treated
as a reserved top-level namespace: filters matching `category/**` will match
any WorkItem whose `category` field is set.

A future version may unify `category` into the label collection entirely.

---

## Persistence Semantics

### MANUAL

- Applied by a human: at creation time, or via the label API post-creation.
- Removed only by a human (or an explicit API call).
- Never touched by the filter re-evaluation cycle.

### INFERRED

- Applied by the filter engine when a filter condition matches.
- Maintained by the `FilterChain` graph (see below).
- No explicit "remove label" action exists in the filter engine. A filter
  that should no longer apply simply doesn't match — the label loses that
  chain as a supporter, and is removed when supporters hits empty.

This eliminates filter conflicts: no two filters can disagree on a label
because there is no remove-action. A filter either matches (label present) or
doesn't (label absent).

---

## FilterChain — Derivation Graph

### Purpose

The `FilterChain` is the engine's internal bookkeeping structure. It is not
exposed directly through the REST API. Users define `WorkItemFilter` objects;
the engine builds and maintains chains automatically.

A `FilterChain` represents one **derivation path** through the filter graph —
the ordered sequence of filters that together caused a label to be applied to
a WorkItem. It also maintains the **inverse index**: which WorkItems have been
reached via this chain.

### Structure

```java
FilterChain:
  id:           UUID
  filterPath:   List<UUID>          // ordered sequence of filterIds
  workItems:    Set<UUID>           // inverse index — workItemIds reached via this chain
```

```java
WorkItemLabel (INFERRED):
  path:         String
  persistence:  INFERRED
  supporters:   Set<UUID>           // chainIds that justify this label's presence
```

### Why Templates, Not Execution Records

Each `FilterChain` is a **shared template**: if 500 WorkItems all went through
`[filterA → filterB → filterC]` to acquire a label, there is one chain object
with 500 entries in its `workItems` set — not 500 copies of the chain. This
gives three benefits:

1. **Efficient cascade on filter deletion** — the chain knows exactly which
   WorkItems to visit without scanning the full WorkItem table.
2. **Efficient cascade on filter addition** — same inverse index, same
   targeted reach.
3. **Natural deduplication** — identical derivation paths are automatically
   shared.

### Re-evaluation on WorkItem Mutation

When a WorkItem changes (any field, including labels):

1. Strip all `INFERRED` label entries from the WorkItem.
2. Run all active filters against the WorkItem (strip-and-recompute).
3. For each matching filter chain, re-insert the label as `INFERRED` with
   the chain's id in `supporters`.

Strip-and-recompute guarantees correctness and simplicity. The chain inverse
index (`workItems` set) is updated accordingly.

### Cascade on Filter Deletion

When a `WorkItemFilter` is deleted:

1. Find all `FilterChain` objects whose `filterPath` contains the deleted filterId.
2. For each such chain:
   a. For each workItemId in `chain.workItems`:
      - Remove this chainId from the WorkItem label's `supporters` set.
      - If `supporters` is now empty → remove the INFERRED label from the WorkItem.
      - If the label was removed and other filters conditioned on it → their
        chains on downstream WorkItems may also lose support → recurse.
   b. Delete the `FilterChain` object.

This is a **graph traversal, not a table scan**. The chain's `workItems` set
is the directed edge; the recursion follows dependency paths downward.

### Circular Detection

Label propagation can create cycles: filter A watches label X and applies
label Y; filter B watches label Y and applies label X. During chain
construction, if extending a chain would introduce a filter already present in
`filterPath`, the extension is rejected and no label is applied via that path.
The existing chain terminates. No visited-set tracking is needed at query time
— the `filterPath` itself is the witness.

---

## Vocabulary

Labels are **vocabulary-controlled**: a label path must be declared in a
`LabelVocabulary` before it can be applied to a WorkItem.

### Scopes

Vocabularies are organised in a hierarchy:

```
GLOBAL   ← platform-wide; accessible everywhere
  └── ORG      ← organisation-level
        └── TEAM     ← team-level
              └── PERSONAL  ← individual user
```

Each scope can reference labels from any higher scope. A PERSONAL vocabulary
can use terms from GLOBAL, ORG, and TEAM vocabularies. A TEAM vocabulary
cannot use PERSONAL terms.

### Open Contribution

Any user can contribute to any vocabulary at or below their access level:

- Org admin → ORG vocabulary
- Team member → TEAM vocabulary
- Any user → PERSONAL vocabulary

When a user attempts to apply an undeclared label, the system prompts:
> *"Label `legal/contracts/nda` is not in any vocabulary you have access to.
> Add it to: [TEAM: legal-team] [PERSONAL]?"*

### LabelDefinition

| Field | Description |
|---|---|
| `path` | The full label path |
| `vocabularyId` | Which vocabulary it belongs to |
| `description` | Human-readable description (optional) |
| `createdBy` | Who declared it |
| `createdAt` | When declared |

---

## Filter Model

Filters live in `quarkus-work-queues`. They are evaluated by observing
`WorkItemLifecycleEvent`.

### WorkItemFilter

| Field | Description |
|---|---|
| `id` | UUID |
| `name` | Human-readable name |
| `scope` | `PERSONAL`, `TEAM`, or `ORG` |
| `owner` | userId (PERSONAL) or groupId (TEAM/ORG) |
| `condition` | The predicate — see Filter Conditions below |
| `actions` | List of label-apply actions |
| `active` | Whether the filter runs automatically |

### Filter Conditions

Filter conditions are evaluated by pluggable `WorkItemExpressionEvaluator`
implementations. Language and expression travel together as an `ExpressionDescriptor`
record — preventing accidental mismatches between language and expression string.
Three built-in evaluators are provided:

#### SPI

```java
// ExpressionDescriptor — language and expression travel together
public record ExpressionDescriptor(String language, String expression) {
    public static ExpressionDescriptor of(String language, String expression) { ... }
}

public interface WorkItemExpressionEvaluator {
    /** Language identifier — e.g. "jexl", "jq", "lambda". */
    String language();

    /** Returns true if the WorkItem matches the expression. Never throws. */
    boolean evaluate(WorkItem workItem, ExpressionDescriptor descriptor);
}
```

#### Built-in: JEXL

Apache JEXL expressions. Natural Java property access, sandboxed execution,
native-image friendly. Default evaluator.

```
// Examples
priority == 'HIGH' && assigneeId == null
labels.any(l -> l.path.startsWith('legal/'))
status == 'PENDING' && category == 'security-review'
```

Fields exposed in the JEXL context: all `WorkItemEntity` fields, plus a `labels`
collection supporting `.any()`, `.all()`, `.path` access.

#### Built-in: JQ

JQ expressions evaluated against the WorkItem serialised as JSON. Familiar to
infrastructure and DevOps practitioners. Backed by `jackson-jq`.

```
// Examples
.priority == "HIGH" and .assigneeId == null
.labels[] | select(.path | startswith("legal/")) | any
.status == "PENDING"
```

#### Built-in: Lambda

Programmatic CDI beans. No expression string, no serialisation, full Java
type safety, native-image clean. The natural choice for complex conditions
that don't fit neatly in an expression, or when compile-time verification
matters.

```java
@ApplicationScoped
public class HighPriorityUnassignedFilter implements WorkItemFilter {

    @Override
    public boolean matches(WorkItem wi) {
        return wi.getPriority() == HIGH && wi.getAssigneeId() == null;
    }

    @Override
    public List<FilterAction> actions() {
        return List.of(ApplyLabel.of("triage/high-priority"));
    }

    @Override
    public FilterScope scope() { return FilterScope.ORG; }
}
```

Lambda filters are discovered automatically via CDI. They are always `active`
while deployed; pausing requires undeployment or an `@Alternative` override.
They are not stored in the database — they are code.

#### Custom evaluators

Any `@ApplicationScoped` bean implementing `WorkItemExpressionEvaluator` is
discovered automatically and available by its `language()` identifier.

### Filter Condition Fields (JEXL / JQ)

| Field | Match type |
|---|---|
| `status` | enum equality / set membership |
| `priority` | enum equality / comparison |
| `category` | string equality / prefix |
| `assigneeId` | string equality / null check |
| `candidateGroups` | contains |
| `labels` | path wildcard match (`legal/**`) |
| `title` / `description` | substring / keyword |
| `createdAt` / `expiresAt` | date range |

### Actions

For the initial release, one action type:

**`ApplyLabel`**
- `path` — label path to apply (must exist in an accessible vocabulary)
- Applied as `INFERRED` via the FilterChain graph

No remove-label action exists. Removal of INFERRED labels is implicit via the
chain supporter model.

### Filter Lifecycle

**Ad-hoc filters** — not persisted. The user constructs a condition and gets
a live result set back. Equivalent to a parameterised query. Can be promoted
to a saved filter.

**Saved filters** — persisted, named, scoped. Run automatically on every
`WorkItemLifecycleEvent`. The filter's `active` flag can pause it without
deleting it.

---

## Queue Model

A **queue** is a label combined with a named view configuration. It is not a
stored entity separate from the label — it is a query.

### QueueView

| Field | Description |
|---|---|
| `id` | UUID |
| `name` | Human-readable name, e.g. "Unassigned legal — oldest first" |
| `labelPattern` | Label path or wildcard, e.g. `legal/contracts/**` |
| `scope` | `PERSONAL`, `TEAM`, or `ORG` |
| `owner` | userId or groupId |
| `filterConditions` | Additional predicates within the label (e.g. `status = PENDING`) |
| `sortOrder` | e.g. `createdAt ASC`, `priority DESC` |

**View ownership is independent of label ownership.** An ORG-scoped label
`legal/contracts` can have multiple team-owned views of it — one for the
contracts team sorted by creation date, one for triage sorted by priority.
Anyone can define a view over any label they can read.

### The Triage / Pickup Use Case

The primary use case for queues is **unassigned work**: a pool of WorkItems
waiting for someone to claim them.

A typical triage queue view:
```
labelPattern:       intake/**
filterConditions:   status = PENDING
sortOrder:          createdAt ASC    (oldest first)
```

When a user picks a WorkItem from this queue, the existing `claim` endpoint
is called (`PUT /workitems/{id}/claim`), transitioning it `PENDING → ASSIGNED`.
Once assigned, a filter conditioned on `status == PENDING` no longer matches,
so the `intake/**` INFERRED label loses its supporter and is removed — the
item leaves the queue automatically.

### Soft Assignment

A user may claim a WorkItem (ASSIGNED) without yet starting it (IN_PROGRESS),
signalling to others: "I have this, but feel free to take it."

This is modelled as a `relinquishable` flag on the WorkItem, managed by the
queues module (not core). A relinquishable ASSIGNED WorkItem is visible in
queue views with a visual indicator. Any eligible candidate can invoke
`PUT /workitems/{id}/claim` to take it (the queue module relaxes the normal
"already assigned" guard when `relinquishable = true`).

The assignee sets `relinquishable = true` via:
```
PUT /workitems/{id}/relinquishable
{ "relinquishable": true }
```

Setting `relinquishable = false` or starting work (`PUT /{id}/start`) clears it.

---

## REST API Surface

### Core additions (`quarkus-work`)

| Method | Path | Description |
|---|---|---|
| `GET` | `/workitems?label=legal/**` | Query WorkItems by label pattern |
| `POST` | `/workitems/{id}/labels` | Add a MANUAL label |
| `DELETE` | `/workitems/{id}/labels/{path}` | Remove a MANUAL label |
| `GET` | `/vocabulary` | List accessible label vocabularies |
| `POST` | `/vocabulary/{scope}` | Add a LabelDefinition to a vocabulary |

### Queues module (`quarkus-work-queues`)

| Method | Path | Description |
|---|---|---|
| `GET` | `/filters` | List filters visible to the caller |
| `POST` | `/filters` | Create a saved filter (JEXL or JQ) |
| `PUT` | `/filters/{id}` | Update a saved filter |
| `DELETE` | `/filters/{id}` | Delete a saved filter (cascades via FilterChain) |
| `POST` | `/filters/evaluate` | Ad-hoc filter evaluation (not saved) |
| `GET` | `/queues` | List QueueViews accessible to the caller |
| `POST` | `/queues` | Create a QueueView |
| `GET` | `/queues/{id}` | WorkItems in this queue view |
| `PUT` | `/workitems/{id}/relinquishable` | Set soft-assignment flag |

---

## Key Design Decisions

### Labels are queues, not queue members

A label is not registered with a queue. The queue is a query against the label.
This means a new QueueView can be created over any existing label retroactively,
with no migration of WorkItem data.

### INFERRED labels have no remove-action

Removing explicit remove-actions from the filter engine eliminates filter
conflicts entirely. If a label should not be present, the filter condition
simply should not match. This keeps filter semantics predictable and
composable.

### FilterChain as shared template with inverse index

INFERRED label provenance is tracked via shared `FilterChain` objects rather
than per-WorkItem execution records. This enables O(affected) cascade on
filter deletion rather than O(all WorkItems) scans, and naturally deduplicates
identical derivation paths.

### Three built-in condition evaluators, one SPI

JEXL (Java-native expressions), JQ (JSON queries), and Lambda (CDI beans)
cover scripted runtime-editable conditions and compiled type-safe conditions
respectively. The `WorkItemExpressionEvaluator` SPI allows additional languages.
Lambda filters are code, not data — not stored in the database.

### Vocabulary is strict but open-contribution

Labels must be declared before use. This prevents label sprawl and enables
autocomplete in UIs. The open-contribution model (anyone can propose at their
scope level) keeps governance lightweight.

### `relinquishable` lives in the queues module

Soft assignment is a queue-pickup concept, not a core lifecycle concept. The
core WorkItem lifecycle (`PENDING → ASSIGNED → IN_PROGRESS`) remains
unchanged. The queues module extends pickup behaviour without modifying core.

---

## Open Items

| Item | Notes |
|---|---|
| Filter condition OR-grouping | Initial release uses AND-only; OR grouping deferred |
| Label vocabulary search / autocomplete API | Useful for UI; not required for first implementation |
| `category` unification | `category` field may eventually be sugar for a `category/**` MANUAL label |
| Notification actions | Future filter action type: notify a user or group when a filter matches |
| Webhook / CDI actions | Future: fire external event when filter matches |
| Lambda filter pause/resume | Lambda filters are always active while deployed; runtime pause requires separate mechanism |
| FilterChain persistence strategy | Whether chains are persisted to DB or rebuilt on startup needs evaluation |
| ProvenanceLink labels | PROV-O graph entries (issue #39) may carry labels once CaseHub/Qhorus are ready |
