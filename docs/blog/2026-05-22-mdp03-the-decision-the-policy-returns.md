---
layout: post
title: "The Decision the Policy Returns"
date: 2026-05-22
type: phase-update
entry_type: note
subtype: log
projects: [casehub-work]
tags: [sla, design-patterns, sealed-interface, transactional]
---

`EscalationPolicy.escalate()` was always a lie. It pretended to be a hook, but it was really fire-and-forget: the expiry service called it and moved on with no way to know what happened next. Escalation was void.

We replaced it with `SlaBreachPolicy.onBreach() → BreachDecision`, where the policy returns what should happen and casehub-work executes it. The runtime owns execution; the policy owns the decision.

`BreachDecision` is sealed — four permitted records, no subclassing. `Fail`. `EscalateTo`. `Extend`. `Chained`. The last one wraps a primary with a fallback and chains via `thenOnBreach()`:

```java
EscalateTo.to("senior-reviewers")
    .withDeadline(Duration.ofHours(4))
    .thenOnBreach(new Fail("no-escalation-target-configured"))
```

`EscalateTo` also carries an optional `deadline` field — but only for completion breaches. For claim deadline breaches, `ClaimSlaPolicy` governs the new window regardless of what the policy requests. That distinction matters and isn't obvious from the type signature alone.

The stateless two-tier escalation pattern was the design I was most pleased with. Instead of serialising "which tier are we at," the policy checks `ctx.task().candidateGroups()`. When a WorkItem escalates, its `candidateGroups` updates. When it expires again, the same policy runs — but now `candidateGroups` contains the escalation group, so it falls through to Fail. No state machine. No database lookup.

```java
public BreachDecision onBreach(SlaBreachContext ctx) {
    if (ctx.task().candidateGroups().contains(escalationGroup)) {
        return new Fail("sla-exhausted");
    }
    return EscalateTo.to(escalationGroup).withDeadline(deadline);
}
```

The entity carries the tier. The policy just reads it.

Getting there took three rounds of design review with DevTown.

Round one caught `Path.of()` throwing on zero arguments (no root path factory existed), a wrong method name on `PreferenceProvider`, and a missing `deadline` field on `EscalateTo`. Round two caught that `SlaBreachEvent` was documented to carry the `Chained` wrapper rather than the leaf that actually ran, and that the `SlaBreachContext` Javadoc was teaching developers to accept null for scope and preferences — which is backwards. The correct guidance is `Path.root()` and `MapPreferences.empty()`.

Round three caught the one that mattered most. If a policy returns bare `EscalateTo.to()` with empty groups — without wrapping it in `Chained` — `BreachExecutionFailed` propagates out of `checkExpired()`, which is `@Transactional`. The entire batch rolls back. The scheduler retries the same items on the next tick. Forever. No audit entry. No CDI event. Items stuck past `expiresAt` with no signal.

Two fixes: the factory throws `IllegalArgumentException` for empty groups, so policy authors fail fast at construction. The executor also converts empty `EscalateTo` to a logged Fail rather than throwing, so the transaction boundary is safe even if someone bypasses the factory.

One process failure worth recording honestly: I jumped to implementation after each review round before I'd shown the user the revised design. The right sequence is receive review → revise → show user → approval → implement. I compressed it to receive → implement, twice. The technical result is correct. The process wasn't.
