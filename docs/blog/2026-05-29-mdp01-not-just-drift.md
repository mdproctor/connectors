---
layout: post
title: "Not just drift"
date: 2026-05-29
type: phase-update
entry_type: note
subtype: diary
projects: [CaseHub Parent]
---

## Not just drift

There's drift, and then there's just wrong.

Yesterday's batch fixed docs that had fallen behind — correct at one point, then aged while the code moved. Today had a different character. I brought Claude in to work through five more sync issues, and a review pass caught something beyond drift: the `CaseMemoryStore` SPI methods were documented with the wrong names.

The issue description had used `add`, `recall`, `erase`. The actual implementation is `store`, `query`, `erase`, `eraseById`. The distinction between drift and error matters. Stale docs are a delay problem — you catch them when you try to use them. Incorrect docs are an active hazard. An adapter implementor who reads `add` writes `add`, gets a compile error at best.

Claude caught a second error in the same section: `ReactiveCaseMemoryStore` listed in `casehub-platform-api` alongside the blocking SPI. It lives in `casehub-platform`. The zero-dep constraint on `platform-api` is explicit — Mutiny is a Quarkus dep — and we'd violated it in the section I'd just written.

Writing the section also surfaced a design distinction worth documenting explicitly. `PreferenceProvider` and `CurrentPrincipal` get configurable mock `@DefaultBean` implementations — the system makes routing and auth decisions based on their values, so a silent no-op would produce wrong behaviour. `CaseMemoryStore` gets a silent no-op. The system functions correctly without memory, just without recall. That's the right default for an opt-in feature with no infrastructure requirement.

The agent routing split in PLATFORM.md was the other meaningful change. There was a single stale row: "Worker routing / selection strategies — casehub-work-core." That was accurate two branches ago. Since engine#337, human task routing (via `WorkBroker` in casehub-work-core) and agent routing (via `AgentRoutingStrategy` SPI in casehub-engine-api) are entirely separate. The replacement is a three-entry priority ladder: `LeastLoadedAgentStrategy` at `@Priority(0)` always runs, `TrustWeightedAgentStrategy` at `@Priority(1)` displaces it when the ledger module is on the classpath, `SemanticAgentRoutingStrategy` at `@Priority(2)` displaces both when casehub-engine-ai is present. Three independent classpath decisions, zero configuration.

A doc-sync sweep after committing found casehub-work.md still describing itself as a direct engine routing dependency, and casehub-life's status still reading "Scaffold" in APPLICATIONS.md. We fixed both.

One issue I held back: the signal bridge PLATFORM.md update. The open issue says wait until two implementation items land. The docs should reflect what exists, not what's coming.
