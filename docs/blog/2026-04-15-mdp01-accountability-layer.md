---
layout: post
title: "The accountability layer — complexity must not leak"
date: 2026-04-15
type: phase-update
entry_type: note
subtype: diary
projects: [quarkus-work]
tags: [ledger, eigentrust, quarkus-flow, tdd]
excerpt: "The ledger becomes a CDI observer on WorkItemLifecycleEvent — invisible when absent, zero overhead, zero tables — with hash chain and decision context on by default for GDPR Article 22 compliance."
---

The conversation that shaped everything in this session came before I wrote a line of code.

I asked whether to build the ledger as a standalone Quarkus module. My first instinct was yes — the design felt like a separate concern. Then I stopped and asked the harder question: if someone adds `quarkus-work` to their application and doesn't want the ledger, do they have to carry it? Does the `WorkItemService` get `if (ledgerConfig.enabled())` guards? Does `WorkItemsConfig` grow a ledger section?

The answer to all of those had to be no. The ledger had to be invisible when absent.

## The CDI observer as a clean seam

Phase 4 had already given us the answer. `WorkItemService` fires `WorkItemLifecycleEvent` on every transition. It doesn't know who's listening. If nobody is, events fire into the void.

`quarkus-work-ledger` is simply a `@Observes WorkItemLifecycleEvent` CDI bean. Nothing in the core changes except two nullable fields on the event record (`rationale`, `planRef`) — backward compatible, ignored by observers that don't use them. If the module isn't on the classpath: zero overhead, zero tables, zero config keys.

The boundary came out clean. The core fires; the ledger listens.

## Six capabilities, most off by default

We built all six: command/event separation, decision context snapshots, plan references, evidence capture, SHA-256 hash chain, and EigenTrust reputation. The defaults are deliberate:

- Hash chain and decision context default ON — low overhead, required for GDPR Article 22 compliance
- Evidence, trust scores, and trust routing default OFF — they require either caller cooperation or accumulated history before being useful

The EigenTrust computation is a nightly `@Scheduled` job: exponential decay, transitive trust through delegation chains, SOUND/ENDORSED attestations count as positive, FLAGGED/CHALLENGED as negative. Simplified, but real.

## TDD caught two real bugs

A separate Claude instance wrote the tests and a separate one wrote the production code — running in parallel. The production agent went off-script halfway through and started writing DESIGN.md update proposals instead of source files. I had to redirect it.

The tests caught two bugs worth noting.

One: `LedgerHashChain.verify()` returned false after a DB round-trip because `Instant.now()` has nanosecond precision but H2 TIMESTAMP stores milliseconds. The canonical content string differed. Fixed by truncating to millis before computing the digest:

```java
entry.occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
// ... then compute hash
```

Two: `@TestTransaction` + `@Transactional` CDI method + REST assertion. The trust score job writes to the database within the test transaction — but the HTTP `GET /trust` call runs in its own request transaction and can't see the uncommitted data. Symptom: 404 where 200 is expected. Fix: remove `@TestTransaction` from test classes that mix direct service calls with REST Assured assertions.

Neither would have been obvious without the test failing first.

## The health check was embarrassing

Running `/java-project-health` at session end found that `docs/DESIGN.md` had all eight phases marked ⬜ Pending — including the REST API, lifecycle engine, and CloudEvents that shipped sessions ago. The roadmap had drifted completely out of sync. Fixed.

317 tests, 0 failures across six modules. Next session: meaningful examples for people.
