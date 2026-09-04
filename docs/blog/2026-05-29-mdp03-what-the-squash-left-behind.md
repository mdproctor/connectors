---
layout: post
title: "What the interrupted squash left behind"
date: 2026-05-29
type: phase-update
entry_type: note
subtype: diary
projects: [casehub]
---

The squash branch from this morning had been sitting at 1:53 AM — interrupted
before it could be pushed. It contained something worth rescuing: a terminology
shift that had been rewritten into `tutorial-strategy.md` and
`AGENTIC-HARNESS-GUIDE.md` during the squash, but never landed on main.

"Field tutorials" had been replaced with "reference architectures" across both
documents. The distinction matters. casehub-aml, casehub-clinical, and
casehub-devtown aren't tutorial applications — they're production-grade domain
implementations whose documentation is thorough enough to teach from. Calling
them tutorials undersells what they are and invites the wrong design instincts.
§2.0b and the §2.5 lookup table were rewritten entirely, not just renamed.

I brought Claude in to read both file versions. We extracted the changes
semantically — reading both sides and choosing which framing was the improvement
— rather than applying the diff mechanically.

That cleared the morning. The rest was a doc batch: five issues sitting in the
backlog, all small.

The one worth writing up is `persistence-backend-cdi-priority.md`. PLATFORM.md
had referenced this file in two places for weeks — in the PreferenceProvider
capability row ("CDI priority ladder: see persistence-backend-cdi-priority.md")
and in the Implementation Protocols table. The file didn't exist. We wrote it.

The pattern it documents is one of those things that's obvious once you've seen
it but easy to get wrong. Three tiers: `@DefaultBean` for the no-op or
in-memory fallback, `@ApplicationScoped` for the standard JPA implementation,
`@Alternative @Priority(1)` for the override that beats everything when its
module lands on the classpath. No consumer config required — the JAR is
sufficient. There's a reactive bridge variant too, which is how `CaseMemoryStore`
avoids forcing every consumer to provide a native async implementation.

Claude caught one thing during review before we pushed: the cross-repo
dependency map still had a `casehub-engine (runtime)` row for openclaw's
`casehub` module. The deep-dive had just been updated to say it uses
`casehub-engine-api` only — to avoid pulling in engine CDI beans with
unsatisfied persistence SPIs — but the table had the old entry. Stale rows in
dependency tables are the kind of thing that propagates wrong assumptions into
tooling. It's gone.
