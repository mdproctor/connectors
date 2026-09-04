# 0001 — Extract ledger infrastructure to a shared quarkus-ledger library

Date: 2026-04-15
Status: Accepted

## Context and Problem Statement

`quarkus-work-ledger` originally contained all ledger infrastructure —
`LedgerEntry`, `LedgerAttestation`, `ActorTrustScore`, `TrustScoreComputer`,
`LedgerHashChain`, `LedgerConfig`, and their repositories — as WorkItems-specific
classes. CaseHub and Qhorus will need the same accountability infrastructure.
Keeping it inside WorkItems would force both to depend on WorkItems to get it.

## Decision Drivers

* CaseHub and Qhorus need command/event ledgers, hash chains, and trust scores
  without depending on WorkItems
* Each ecosystem extension should own only what is specific to it
* The `WorkItemEntity` concept is WorkItems-specific; the ledger pattern is not

## Considered Options

* **Option A** — Extract generic ledger infrastructure to `io.casehub:casehub-ledger`; WorkItems provides `WorkItemLedgerEntry` as a subtype
* **Option B** — Copy the ledger classes into each downstream project (CaseHub, Qhorus) independently
* **Option C** — Keep everything in `quarkus-work-ledger`; CaseHub and Qhorus depend on WorkItems to get the ledger

## Decision Outcome

Chosen option: **Option A**, because it gives CaseHub and Qhorus a shared
accountability layer without coupling them to WorkItems, and avoids duplicating
the hash chain and trust score logic across projects.

### Positive Consequences

* `quarkus-ledger` can be adopted by any Quarkiverse extension independently
* `WorkItemLedgerEntry` (extends `LedgerEntry`) uses JPA JOINED inheritance —
  the WorkItems subtype adds only `commandType` and `eventType`; all common fields
  live in `ledger_entry`
* `TrustScoreComputer`, `LedgerHashChain`, and `ActorTrustScoreRepository`
  are reusable across the ecosystem without modification

### Negative Consequences / Tradeoffs

* `quarkus-ledger` is a separate local project (`~/claude/casehub/ledger/`)
  that must be installed into the local Maven repo before building
  `quarkus-work-ledger` — documented in CLAUDE.md
* Two repositories to maintain until `quarkus-ledger` is published to Maven Central
* JPA JOINED inheritance adds a join on every ledger query

## Pros and Cons of the Options

### Option A — Shared quarkus-ledger library

* ✅ Single source of truth for ledger infrastructure
* ✅ CaseHub and Qhorus can adopt it without depending on WorkItems
* ✅ WorkItems-specific fields isolated in the subtype
* ❌ Extra build step (install quarkus-ledger first)
* ❌ JPA JOINED inheritance join cost

### Option B — Copy into each project

* ✅ No cross-project dependency
* ❌ Divergence over time — three copies of hash chain and trust score logic
* ❌ Bug fixes need applying to every copy

### Option C — Keep in quarkus-work-ledger; downstream depends on WorkItems

* ✅ No new library
* ❌ CaseHub and Qhorus take a WorkItems dependency they don't conceptually need
* ❌ Creates a circular dependency risk when WorkItems integrates with CaseHub

## Links

* Issue: #49 — Migrate quarkus-work-ledger to use quarkus-ledger as shared base library
* `quarkus-ledger` source: `~/claude/casehub/ledger/`
* `WorkItemLedgerEntry`: `quarkus-work-ledger/src/main/java/io/casehub/work/ledger/model/WorkItemLedgerEntry.java`
