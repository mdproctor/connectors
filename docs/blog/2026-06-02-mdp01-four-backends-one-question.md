---
layout: post
title: "Four backends, one question — and the fault-tolerance bug hiding in the safety net"
date: 2026-06-02
type: phase-update
entry_type: note
subtype: diary
projects: [casehub]
---

The question was simple: what is this agent currently doing? Trust score, active WorkItems,
open Commitments, running cases. Four systems, four separate calls, manual join at the
caller. We filed it as parent#56 back in the coherence audit and left it open.

This session we closed it.

The design question was where to put the contributor SPI. The endpoint needed 4+ implementations
(ledger, work, qhorus, engine) that couldn't share a single domain api module — they're peers.
Options: `casehub-engine-common` (wrong direction — work and qhorus shouldn't depend on engine),
a new `casehub-actor-api` module (another JAR for two interfaces), or `casehub-platform-api`
which all four already import.

I went with platform-api. The interface uses only stdlib types — UUID, Instant, primitives.
No domain objects cross the boundary. It's the first *behaviour* SPI placed there (the others
are infrastructure: `CurrentPrincipal`, `PreferenceProvider`). That precedent felt worth an ADR,
so we filed one.

The visitor pattern made the threading clean. Instead of each contributor returning a partial
response object that something else had to merge, they call typed methods on an accumulator:
`acc.trustScore()`, `acc.workItem()`, `acc.commitment()`. The accumulator is `ConcurrentHashMap`
and `CopyOnWriteArrayList` underneath. Contributors run in parallel via `@Inject ManagedExecutor`
(raw executors drop CDI context — Panache-backed stores would have silently failed on a
virtual thread pool).

The final code review caught one critical bug. The aggregator's catch block stored the reason
in a `ConcurrentHashMap<String, String>`. For any exception with no message string —
`new NullPointerException()`, `new UnsupportedOperationException()` — `e.getMessage()` returns
null. `ConcurrentHashMap.put(key, null)` throws. The fault-tolerance code caused the exact
failure it was meant to prevent.

Fix: `e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()`.

The reactive aggregator had a different issue: `Infrastructure.getDefaultBlockingExecutor()`
doesn't exist. The method is `getDefaultExecutor()`. Makes sense once you know it — the
"blocking" part is handled by `runSubscriptionOn`, not the executor name. Wouldn't have
guessed the naming.

Five repos touched. Three new platform protocols — cross-backend aggregation with partial
results and a `sources` field, SPI default methods with contract test requirements, and a
consumer contract for modules that consume reactive-capable backends. The `sources` field
pattern is the one worth internalising: when you aggregate from N backends, the response
should always say which ones responded. Callers shouldn't have to inspect empty arrays and
guess.

DevTown gets the endpoint once its branch (issue-249) lands on main.
