---
layout: post
title: "The sweep that wasn't done"
date: 2026-05-31
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-work]
tags: [exclusion-policy, sweep, cdi]
---

The branch was supposed to be closed. Handover said so. Seven items swept,
committed, pushed upstream.

Except it wasn't. When I resumed, `git status` showed eleven uncommitted files
— the whole `ExpiringExclusionPolicy` example sitting untracked in the examples
directory, the CDI fix missing from three test modules, the Javadoc improvements
staged but never committed. I'd apparently built everything in a previous session
and then just... not committed it.

## Dates in the field

`ExpiringExclusionPolicy` implements the `ExclusionPolicy` SPI by encoding
expiry dates directly in the `excludedUsers` field: `alice:2026-08-01,bob:2026-07-15`.
A user is denied if today is strictly before their expiry date; on the expiry date
itself, they're allowed. The encoding is backward-compatible with the default
`CommaSeparatedExclusionPolicy` — tokens without a colon are treated as permanent
exclusions, so switching policies on a WorkItem created with the old format doesn't
break anything. Existing plain CSV entries just become permanent.

There's a less obvious case: what happens when the date token is unparseable
(`alice:not-a-date`)? The choice was permanent exclusion as the fail-safe.
Silently allowing felt wrong in a security-adjacent field.

The `Clock` injection is the interesting design detail. The class carries no CDI
annotations (intentionally — it's an example, not a replacement for the default),
so there's no container to inject through in tests. Two constructors: a
package-private default that creates `Clock.systemDefaultZone()`, and a
package-private test constructor that takes a `Clock`. Eleven tests, all
deterministic, covering the boundary case where today exactly equals the expiry
date.

The CDI issue we fixed across three test modules came from `casehub-platform`
being declared `runtime` scope. That's needed because `MockPreferenceProvider`
is production-deployed, but it pulls `MockGroupMembershipProvider` into the CDI
scan at test time. That bean conflicts with `NoOpGroupMembershipProvider`
(`@DefaultBean`) — two candidates, no `@Alternative`, Quarkus ARC throws an
`AmbiguousResolutionException`. Fix: `quarkus.arc.exclude-types=io.casehub.platform.mock.MockGroupMembershipProvider`
in each test `application.properties`.

## When the label doesn't match the work

Issue #234 was labeled S · Low and included in the sweep. I brought Claude in to
investigate before writing any code.

Three blockers surfaced quickly. First: `casehub-connectors-core:0.2-SNAPSHOT`
isn't in the local Maven cache and the connectors#6 branch hasn't merged to main
— casehub-work can't compile against it. Second: the connectors#6 design spec
made an explicit decision (D3) that routing flows `InboundMessage → Qhorus →
WorkItem`. A direct observer in casehub-work would either duplicate that path or
contradict it, and the architectural choice was never made explicit. Third: the
issue acknowledged that not all inbound messages should create WorkItems but left
the classification mechanism undefined.

The S · Low label described the work as someone imagined it before looking
closely. The implementation might be simple once those questions are answered.
"Simple to write" and "ready to write" are different things.

We labeled it `blocked`, escalated complexity to High, documented the blockers in
a comment, and removed it from the sweep umbrella. The lesson formalised into a
protocol: sweep items with real blockers must be documented and removed, never
silently skipped.

Twelve tests passing, branch clean, #235 closed minus one.
