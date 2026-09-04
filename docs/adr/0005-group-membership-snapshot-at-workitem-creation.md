# 0005 — Group Membership Snapshot at WorkItem Creation

Date: 2026-06-02
Status: Accepted

## Context and Problem Statement

WorkItemTemplate supports `excludedGroups` — group names whose members should be excluded
from claiming a WorkItem (conflict-of-interest exclusion). Groups are dynamic: membership
changes over time. The question is whether to resolve group membership once at WorkItem
creation or dynamically at each claim attempt.

## Decision Drivers

* WorkItem exclusion must be deterministic — two claims against the same WorkItem must
  apply the same exclusion set
* `GroupMembershipProvider` may call external systems (LDAP, SCIM2); repeated calls
  at claim time multiply latency and failure surface
* The `excludedUsers` field on `work_item` is already persisted; expanding into it at
  creation time requires no schema changes
* Audit trail must explain why a specific user was excluded at creation time

## Considered Options

* **Option A — Snapshot at creation** — resolve group membership once via
  `GroupMembershipProvider.membersOf()` at WorkItem instantiation; merge into
  `excludedUsers`
* **Option B — Dynamic at claim time** — call `GroupMembershipProvider` on every
  claim/delegate attempt; never persist group-derived exclusions
* **Option C — Hybrid** — snapshot at creation; allow explicit refresh via an
  admin endpoint

## Decision Outcome

Chosen option: **Option A — Snapshot at creation**, because it is deterministic,
keeps the claim/delegate path fast and free of external I/O, and requires no new
schema. The tradeoff (group membership changes after creation are not reflected)
is acceptable: exclusion is a template-level policy and WorkItems are typically
short-lived relative to group roster changes.

### Positive Consequences

* Claim/delegate path has no external I/O dependency — `GroupMembershipProvider`
  only called once
* `excludedUsers` on `work_item` is the complete, auditable exclusion record
* `TemplateExpander` handles expansion across all three instantiation paths
  (standard, multi-instance, spawn) in one place

### Negative Consequences / Tradeoffs

* Group membership changes after WorkItem creation are not reflected — a user
  added to a conflict group after the WorkItem was created can still claim it
* Custom `GroupMembershipProvider` implementations backed by HTTP will fire N
  calls for multi-instance templates (one per child); caching can be added later
  if this becomes a problem

## Pros and Cons of the Options

### Option A — Snapshot at creation

* ✅ Deterministic — exclusion set is fixed at creation
* ✅ No external I/O at claim time
* ✅ Fits existing `excludedUsers` column with no schema change
* ❌ Stale if group roster changes after creation

### Option B — Dynamic at claim time

* ✅ Always reflects current group membership
* ❌ External I/O on every claim/delegate — latency and failure surface
* ❌ Non-deterministic — two claims at different times may produce different outcomes
* ❌ Audit trail does not explain why a specific user was excluded at creation

### Option C — Hybrid

* ✅ Can self-correct after roster changes
* ❌ Requires an admin endpoint and refresh trigger — added complexity for an
  edge case
* ❌ Refresh semantics are unclear for in-flight WorkItems

## Links

* Issue #184 — excludedGroups feature
* `WorkItemTemplateService`, `TemplateExpander`, `MultiInstanceSpawnService`
