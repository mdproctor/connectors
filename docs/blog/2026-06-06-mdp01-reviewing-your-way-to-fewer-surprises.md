---
layout: post
title: "Reviewing your way to fewer surprises"
date: 2026-06-06
entry_type: note
subtype: diary
projects: [casehub-work]
tags: [design-review, jexl, conditional-outcomes, patch-api]
---

The last two issues on my S-scale backlog — conditional outcomes (#177) and
JSON Merge Patch for templates (#199) — went through four rounds of spec review
before a line of code was written. I went in thinking the review was mostly pro
forma, a sanity check on a well-understood problem. I came out with a different
opinion.

The features themselves aren't complicated. Outcomes on a `WorkItemTemplate` can
now declare a JEXL condition — `actorId.startsWith('mgr-')`, say, or
`resolution != null && resolution.contains('APPROVED')` — and that condition is
evaluated at completion time. If it returns false, the outcome is rejected. The
PATCH endpoint is RFC 7396 JSON Merge Patch: absent fields stay, null clears. Two
small S-scale features, one branch.

Where it got interesting was in the spec itself. My first draft had `WorkItemContextBuilder`
listed as `@ApplicationScoped` — injectable, in the CDI graph. The reviewer
flagged this as simply wrong. `WorkItemContextBuilder` is `public final class`
with a private constructor and static methods. It's in the `event` package and
called from CDI beans, which was enough to make me treat it as CDI without
actually checking. The fix was to extract outcome condition validation into a new
`OutcomeValidator @ApplicationScoped` bean — the actual CDI bean that only injects
`JexlConditionEvaluator` and calls `WorkItemContextBuilder.toMap()` statically.

The second round found five more things: `List<Outcome>.contains(String)` is always
false (correct Java, which I'd written around the wrong way in the spec), a compile
error at `WorkItemService:107` that the spec didn't mention, `WorkItemWithAuditResponse`
missing from the change surface, a missing null guard that would have produced an
unstructured 500 instead of an `IllegalStateException`, and — the PATCH section —
no implementation strategy at all. I'd said "25 fields" without specifying that
`asInt()` returns 0 on null nodes and `intValue()` returns null, which is exactly
the difference between "field cleared" and "field silently set to zero".

A third and fourth pass closed the remaining gaps. By the time we started
implementing, the spec was six pages of specific before/after diffs, a complete
JEXL context table (including the `workItem.resolution` null trap — the field isn't
set when the condition is evaluated, so conditions must use the top-level `resolution`
variable), and a test plan that already named the edge cases.

One design decision I'm glad I made explicit: condition evaluation happens at
completion time, not at instantiation. The alternative — evaluate at creation and
snapshot only the applicable outcomes — was genuinely tempting because it's simpler
to explain. But conditions like `actorId.startsWith('mgr-')` require knowing who
is completing the work. Snapshotting at instantiation would lose all of that. It
only works if conditions are static, and there's no reason to restrict them that way.

The other decision: JEXL, not a new DSL or JQ. `runtime.filter.JexlConditionEvaluator`
was already there, used by filter rules, with an established `silent(true).strict(false)`
profile. Reusing it meant zero new dependencies and one expression syntax for
template authors. The downside — `silent(true)` swallows syntax errors silently,
making a broken condition expression indistinguishable from a false one — is real,
and I logged it as a deferred problem (#251). Syntax validation at template
creation time will close it, but it's out of scope here.

The migration approach for stored JSON was the one technique worth capturing. 
`WorkItem.permittedOutcomes` used to store `["approved","rejected"]` — a flat
string array. The new format is full `Outcome` objects with `name`, `displayName`,
and `condition`. A Flyway migration to convert existing rows would have required
cross-database SQL JSON functions (PostgreSQL and H2 behave differently), coordinated
V-number reservation, and a migration file that blocks the branch from merging until
everything lines up. We avoided all of that by detecting the format on read:
`MAPPER.readTree(json)`, check `isArray()`, then `arr.get(0).isObject()` to
distinguish old from new. Old rows decode via a fallback that wraps string elements
into `Outcome(name, null, null)`. New writes use the full format. No migration, no
coordination, no cross-database SQL.

The pattern works because the old format is a strict semantic subset of the new one.
Every old name string maps to an equivalent `Outcome` with null displayName and null
condition. If that relationship didn't hold, you'd need the migration.
