---
layout: post
title: "The build that kept giving"
date: 2026-06-01
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-work]
tags: [cdi, flyway, jandex, build]
---

The sweep was done. The branch was closed. Then I ran `mvn install`.

## #234 — when S · Low isn't

Before the build verification, one sweep item hadn't been implemented: #234,
routing `InboundMessage` to WorkItem creation. I brought Claude in before
writing a line.

Three blockers in quick succession. `casehub-connectors-core:0.2-SNAPSHOT`
isn't in the local Maven cache and the connectors#6 branch hasn't merged. The
connectors#6 spec (D3) explicitly decided routing flows
`InboundMessage → Qhorus → WorkItem`, which makes a direct observer in
casehub-work either a duplication or a contradiction — a decision that was
never made explicit. And the classification question (which messages create
WorkItems, which are replies) was left open in the issue.

S · Low describes the work as someone imagined it before looking closely. We
labeled it blocked, escalated complexity to High, documented the three
blockers in a comment, and removed it from the sweep. A new protocol covers
the pattern: sweep items with real blockers are documented and removed, never
silently skipped.

## The CDI ambiguity that spread

`TemplateExpander`, added in #184 (excludedGroups), injects
`GroupMembershipProvider`. `casehub-platform` is `runtime` scope in several
modules so that `MockPreferenceProvider` is available in production builds.
That brings `MockGroupMembershipProvider` into the CDI scan — two candidates,
`@DefaultBean` doesn't disambiguate, Quarkus ARC throws.

We'd fixed this in `examples`, `flow-examples`, and `queues-examples` when
#184 was committed. The full build revealed the same issue in eleven more
modules. And then the same fix was also needed in the main
`application.properties` (not just test) for the example modules, because
Quarkus augmentation — not just the test runner — picks up the conflicting
bean.

Sixteen files across three commits.

## The broadcaster tests had been broken since #164

`PostgresBroadcasterTestResource` starts a real PostgreSQL container and sets
`quarkus.flyway.migrate-at-start=true`. It doesn't set
`quarkus.flyway.locations`. Since #164 moved migrations from `db/migration`
to `db/work/migration`, Flyway has been finding zero migrations, running
nothing, and leaving the schema empty. The tests then fail with
`relation "filter_rule" does not exist` — which points away from the config.

The fix isn't `TestResource.start()` — that runs after augmentation and
`quarkus.flyway.locations` is build-time fixed. It goes in the test
`application.properties`. Two lines, two modules, two garden entries.

## The SNAPSHOT that was behind the types

The ledger module tests hit unsatisfied dependencies for `DIDResolver` and
`AgentCredentialValidator` after refreshing the casehub-ledger SNAPSHOT.
The ledger had recently moved its SPIs to `casehub-platform-api`.
`casehub-platform-identity` provides `@DefaultBean` no-ops for both — in the
JAR, correctly annotated, Jandex index present. Still not found.

I spent a while chasing a Jandex hypothesis: maybe `casehub-platform-api`
ships without an index. Another session on the ledger quickly disproved it —
the plugin is already there, CI ran clean, the index is in the published JAR.
The actual cause: the local SNAPSHOT cache for `casehub-platform` predated the
commit that added the new identity types. Refreshing casehub-ledger pulled in
a ledger that referenced types the locally cached platform JAR didn't yet
index.

I'd also reached for adding no-op implementations to casehub-work-ledger.
The user stopped me correctly — those belong in casehub-platform, not here.
The working fix is excluding the ledger's identity enricher package from the
casehub-work-ledger test CDI scan; those enrichers aren't exercised by
casehub-work-ledger tests anyway. When the platform SNAPSHOT refreshes, the
no-ops are discoverable and the exclude-types list can shrink.

The CLAUDE.md procedure already says to refresh casehub-ledger before
concluding a build is stable. It should also say: if ledger has added
dependencies on new platform types, refresh casehub-platform too.

Full build: 900+ tests, three issues filed and closed, two garden entries,
one protocol.
