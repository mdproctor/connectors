---
layout: post
title: "The date in the field"
date: 2026-05-31
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-work]
tags: [exclusion-policy, cdi, quarkus]
---

The `ExclusionPolicy` javadoc had a forward reference to issue #185 where proper extension guidance should be. I wanted that resolved: what the activation pattern looks like, what the `excludedUsers` field means, and a concrete non-trivial example to copy from.

I sent the spec to review before any code touched the project. The spec proposed `ExpiringExclusionPolicy` — a policy where `excludedUsers` encodes `userId:YYYY-MM-DD` entries, denying access during a cooling-off period. Conflict-of-interest enforcement, SOX controls, that kind of thing.

Claude came back with eight concerns. The two that mattered most:

**The scenario wasn't a scenario.** The spec called for a REST demo that called `check()` directly on a plain instance — no CDI activation, no `WorkItemService`, no audit trail. Technically correct, but calling it a "scenario" alongside `EscalationScenario` (which drives WorkItems through the full service tier) was misleading. We renamed it `ExclusionPolicyDemoScenario`. "Demo" in the name signals what it actually is.

**`LocalDate.now()` with hardcoded test dates.** Write `"alice:2030-01-01"` as a "future date" today and you have a test that silently flips in four years. The fix is an injectable `Clock` — no-arg constructor uses `Clock.systemDefaultZone()`, package-private constructor takes a `Clock` for tests. All test dates fixed to `2026-06-01T00:00:00Z`.

The parse branch table also needed completing. What happens to `alice:not-a-date`? We settled on fail-safe: a `DateTimeParseException` denies permanently, with a reason string that says "invalid expiry format — treating as permanent." The other branches:

| Token form | Decision |
|---|---|
| `userId:YYYY-MM-DD`, today < expiry | DENY with cooling-off reason |
| `userId:YYYY-MM-DD`, today >= expiry | ALLOW |
| `userId` (no colon) | DENY permanently — backward-compatible with plain CSV |
| `userId:not-a-date` | DENY permanently — fail-safe |
| null or blank | ALLOW |

The demo covers all six cases. The `@QuarkusTest` asserts on the `denied` boolean and key reason strings per step — not on step description strings, which would be fragile.

Eleven unit tests, all deterministic. One `@QuarkusTest` for the REST endpoint.

Then the tests wouldn't start.

```
AmbiguousResolutionException: Ambiguous dependencies for type
GroupMembershipProvider and qualifiers [@Default]
  - CLASS bean [target=MockGroupMembershipProvider]
  - CLASS bean [target=NoOpGroupMembershipProvider]
```

`NoOpGroupMembershipProvider` has `@DefaultBean` — it should win when nothing else is present. But `@DefaultBean` suppression only fires when there's exactly one non-default candidate. `MockGroupMembershipProvider` from `casehub-platform` was on the classpath too, making two. ArC doesn't pick a winner; it reports ambiguity.

The cause was a scope change from May 22: `casehub-platform` moved from `test` to `runtime` scope in the examples module so `MockPreferenceProvider` would satisfy a CDI injection point during production build validation. That change also brought `MockGroupMembershipProvider` into the test CDI scan. Every `@QuarkusTest` in three example modules had been failing since.

One line in test `application.properties` per module:

```properties
quarkus.arc.exclude-types=io.casehub.platform.mock.MockGroupMembershipProvider
```

30/30 in `examples`, 37/37 in `queues-examples`, 2/2 in `flow-examples`.
