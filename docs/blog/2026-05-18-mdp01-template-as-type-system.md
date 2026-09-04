---
layout: post
title: "Template as type system — and a merge that lied"
date: 2026-05-18
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-work]
tags: [oht, schema-validation, excluded-users, cherry-pick, flyway]
---

The last OHT gaps are closed. `WorkItemTemplate` now carries the full type
contract: named outcomes, JSON Schema for payload and resolution, and an
explicit exclusion list for conflict-of-interest enforcement. Three issues, two
epics, one mess left behind by a merge gone wrong.

## Deleting an entity is easy. The argument for it is harder.

`WorkItemFormSchema` was a category-level entity — it defined what a WorkItem's
payload and resolution should look like, keyed by category, stored separately
from the template. When Phase 9 was written, templates were simple default-value
containers. The schema lived elsewhere because the schema *was* the type
definition, and "type" was "category."

That's no longer true. Templates now carry outcomes, assignment strategies,
business-hour deadlines, spawn configuration, and routing rules. A template
*is* the type. Having the schema in a separate entity means two things describe
the same unit of work, with unclear precedence between them.

I wanted to delete `WorkItemFormSchema` entirely rather than add a parallel
template-level schema alongside it. The argument Claude raised — that the UI
could still use the form schema as a hint for rendering — didn't hold up.
Whatever the UI needed to render, the template could provide: `GET
/workitem-templates/{id}` now returns `inputDataSchema` and `outputDataSchema`
directly. No information was lost. One endpoint replaced two.

The deletion was about 1,200 lines removed, including the entity, the CRUD
resource, and the category-level validation that had lived in `WorkItemResource`.
Validation moved to `WorkItemService` — where it should have been — and the
service now holds `FormSchemaValidationService` directly. The rest of the
codebase didn't miss `WorkItemFormSchema`.

## Conflict of interest as a policy point

For the exclusion feature (#171), there was a short argument about whether to
put `ExclusionPolicy` on the API as a proper SPI or just bake the comma-separated
field check directly into the service. I pushed for the SPI approach:
`ExclusionPolicy` is a policy concern, and the platform already has SPIs for all
its policy concerns — `EscalationPolicy`, `ClaimSlaPolicy`,
`WorkerSelectionStrategy`. A comma-separated field check as the `@DefaultBean`
implementation covers the OHT use case (the initiator can't approve their own
task). LDAP group membership, time-window exclusions, and trust-score-based gates
are all plausible without changing the core.

The enforcement spans five points: `claim()`, `create()` for the direct assignee,
`delegate()`, the auto-assignment candidate filter in `resolveCandidates()`, and
`SelectionContext` so external selection strategies have access to the exclusion
list. That last one matters. A custom `WorkerSelectionStrategy` that builds its
own candidate list from an external directory would bypass service-layer filtering.
Carrying `excludedUsers` in `SelectionContext` makes the constraint part of the
selection contract, not an implementation detail of the default path.

`clone()` deliberately carries `excludedUsers` forward. A WorkItem cloned from
one with conflict-of-interest rules should inherit those rules — a clean-slate
clone would be a bypass vector.

## The merge that silently dropped a feature

This is the part worth remembering.

A separate Claude session — working in the wrong project repository — ran an
epic hygiene scan on casehub-work and merged both epics to main using
`git cherry-pick -X ours` to resolve conflicts. The strategy takes "our side" for
any conflicting hunk, which meant any file that existed in both branches and
had a conflict kept the earlier branch's version. No warning. The commit appeared
in `git log`. `git status` was clean.

The result: the `excludedUsers` feature's service-layer enforcement survived
(those files weren't conflicting), but everything else — the REST surface,
response types, mapper, template resource, context builder — was silently reset
to the output-schema version. Running the feature's tests showed five failures
immediately. `WorkItemExcludedUsersTest.claim_byExcludedUser_returns409`
expected 409, got 200.

Tracing it took a few minutes. `WorkItemService.claim()` had the enforcement.
`WorkItemMapper.toResponse()` had `null` where `wi.excludedUsers` should have
been. The mapper had kept the output-schema version, which predated the
excluded-users parameter. Service validated; REST ignored; response always null.

We fixed it across 41 files. The lesson is specific: `git cherry-pick -X ours`
is not a safe way to merge concurrent feature branches. If two branches both
added capabilities to the same files — which they will, when the features are
related — the strategy will silently discard one of them. The only safe merge
for concurrent features is manual conflict resolution or sequential merge with
rebase.

## Flyway migration numbers in concurrent branches

The two epics both started from V22 on main. Both independently picked V23 and
V24. Flyway enforces uniqueness even on a fresh install — two V23 files in the
same application classpath fails at startup before a single migration runs.

The fix was straightforward: renumber one set. The epics landed as V23–V25
(schema validation) and V26–V27 (excluded users). The harder part is that there
was no early signal. Each branch ran its own tests cleanly. The conflict only
surfaces when both are on the classpath together at merge time.
