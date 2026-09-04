# 0006 — Evaluate outcome conditions at completion time, not at instantiation

Date: 2026-06-06
Status: Accepted

## Context and Problem Statement

`Outcome` definitions on a `WorkItemTemplate` can declare a JEXL `condition`
expression that restricts when that outcome is applicable. Two evaluation points
were considered: at WorkItem instantiation (to filter `permittedOutcomes` to only
the applicable subset) or at completion/rejection time (to validate the submitted
outcome against current WorkItem state).

## Decision Drivers

* Condition expressions may reference completion-time context (`resolution`,
  `actorId`, `reason`) that is not known at instantiation time.
* Consistency with how `permittedOutcomes` is used — the actor submits a chosen
  outcome; the system validates it.
* Conditions are snapshotted at instantiation (consistent with schemas and outcome
  names) so the validation uses the template state at creation time, not at
  completion time.

## Considered Options

* **At instantiation** — evaluate conditions against the initial WorkItem state;
  snapshot only the outcomes whose conditions are satisfied.
* **At completion/rejection** — evaluate the condition for the submitted outcome
  at the moment of completion or rejection.

## Decision Outcome

Chosen option: **At completion/rejection**, because condition expressions are
designed to reference completion-time context (`resolution`, `actorId`, `reason`)
that does not exist at instantiation. Filtering at instantiation would produce a
static list based on initial state and lose all runtime context. Evaluation at
completion allows conditions like `actorId.startsWith('mgr-')` and
`resolution != null && resolution.contains('APPROVED')`.

### Positive Consequences

* Conditions can reference who is completing the WorkItem and what they resolved it to.
* The full `List<Outcome>` (with conditions) is snapshotted at instantiation —
  consistent with schema snapshotting; template changes don't affect in-flight items.
* `OutcomeValidator` is invoked once per completion/rejection — no pre-computation required.

### Negative Consequences / Tradeoffs

* `workItem.resolution` in the JEXL context is always null at evaluation time
  (the field is set after validation). Condition authors must use the top-level
  `resolution` variable, not `workItem.resolution`.
* System paths (`completeFromSystem`, `rejectFromSystem`) bypass condition
  evaluation intentionally — consistent with their bypass of schema validation.

## Links

* Refs casehubio/work#177
* See `docs/GOTCHAS.md` — JEXL Condition Context section
