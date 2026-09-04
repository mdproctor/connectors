# 0002 — Labels as queues with MANUAL/INFERRED persistence

Date: 2026-04-15
Status: Accepted

## Context and Problem Statement

WorkItems need to support filtered work queues — named views of WorkItems
matching certain criteria — so that teams can manage unassigned work pools,
triage incoming items, and create personal or team-scoped views. The question
was how to model queue membership and label lifecycle without creating
conflicting filter semantics.

## Decision Drivers

* Queue membership must update automatically when a WorkItem changes
* Multiple independent queues can claim the same WorkItem simultaneously
* Filter rules must compose without conflict
* The queue feature should be an optional module that doesn't affect the core
  WorkItem lifecycle

## Considered Options

* **Option A** — Separate `Queue` entity with explicit membership records
* **Option B** — Labels as queue identifiers, with MANUAL / STATED / INFERRED persistence
* **Option C** — Labels as queue identifiers, with MANUAL / INFERRED persistence only (no STATED)

## Decision Outcome

Chosen option: **Option C**, because eliminating the STATED persistence type
removes the only scenario where two filters can conflict (one applying, one
removing the same label). INFERRED labels self-manage via re-evaluation;
MANUAL labels are untouchable by the filter engine. No conflict is possible.

A queue is not a separate entity — it is a label combined with a named view
configuration (sort order, additional predicates). This means queue views can
be created retroactively over any label with no data migration.

### Positive Consequences

* Filter semantics are conflict-free by construction — no remove-actions exist
* Queue membership is always current — INFERRED labels recompute on every mutation
* New queue views can be defined over existing labels without migrating data
* Core WorkItem model gains only a `labels` collection; queue logic is fully
  isolated in the optional `quarkus-work-queues` module

### Negative Consequences / Tradeoffs

* "Sticky filter-applied" labels (STATED) are not supported — if a label must
  persist after its filter condition becomes false, a human must apply it manually
* Re-evaluating all INFERRED filters on every WorkItem mutation may become
  expensive at high filter counts — will need indexing and selective evaluation

## Pros and Cons of the Options

### Option A — Separate Queue entity with membership records

* ✅ Queue membership is explicit and queryable without label lookups
* ❌ Queue membership can go stale if not updated on every WorkItem change
* ❌ Adds a third entity (Queue, QueueMember, WorkItem) with its own sync problem
* ❌ A WorkItem in multiple queues requires multiple membership records

### Option B — Labels with MANUAL / STATED / INFERRED

* ✅ Unified label model, queue = label
* ✅ STATED labels allow sticky filter-applied tags
* ❌ STATED + remove-actions create conflict: two filters disagreeing on a label
  have no deterministic resolution
* ❌ Conflict resolution requires filter priority ordering, adding complexity

### Option C — Labels with MANUAL / INFERRED only (chosen)

* ✅ Conflict-free by construction — no remove-actions in the filter engine
* ✅ Simpler mental model: filters only apply labels, never remove them
* ✅ Re-evaluation cycle is straightforward: strip INFERRED, re-run filters, re-insert
* ❌ No sticky filter-applied labels — must use MANUAL if persistence beyond
  condition lifetime is needed

## Links

* Design spec: `docs/specs/2026-04-15-queues-design.md`
