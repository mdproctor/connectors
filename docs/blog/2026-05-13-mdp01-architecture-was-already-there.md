---
layout: post
title: "The Architecture Was Already There"
date: 2026-05-13
type: phase-update
entry_type: note
subtype: diary
projects: [casehub]
tags: [architecture, clean-architecture, hexagonal, ddd, llm]
---

The question was whether the platform had chosen the right architectures. It led somewhere more interesting: the dependency rule had been there all along, enforced, just unnamed.

Source code dependencies only point inward. Domain never depends on infrastructure. That principle was already in `module-tier-structure.md`, expressed as a Maven module convention:

```
REST / Deployment (outermost)
        ↓ depends on
Service / Application Logic
        ↓ depends on
SPI Interfaces  (ports)
        ↓ depends on
Domain Model  (innermost)
```

Pure-Java SPIs in `api/`, no JPA in SPI signatures, adapters depending on ports and never the reverse. The rule was being followed. It just hadn't been identified as clean architecture.

I asked Claude to survey the codebase while I looked at what the platform documentation actually said about architecture. The answer was: nothing. Claude came back with seventeen distinct patterns across the repos. Hexagonal ports and adapters in all four foundation repos, with interchangeable in-memory and Hibernate implementations behind the same SPI interfaces. DDD domain events fired via CDI's `Event.fireAsync()`. Reactive composition via Mutiny throughout. CQRS-lite at the REST boundary — commands go through the engine, queries bypass it. Strategy pattern for worker selection. Registry pattern for case definitions. CDI interceptors for provenance capture in the ledger.

The coherence was real. The documentation for it wasn't.

That's worth sitting with. Architectural coherence doesn't require a design doc written at the start. It can emerge from consistent local judgment — always checking dependency direction before adding a module — and the named pattern is what you see when you step back. The Maven module conventions had been doing architectural work the whole time without being called anything.

We wrote `docs/ARCHITECTURE.md` to make it explicit: the dependency rule stated as a principle rather than a convention, a per-tier pattern map, a blend table, and a section on what was deliberately not chosen.

The LLM incoming adapter framing was the sharpest idea to emerge. When an AI agent calls a tool — start a case, signal an event, query state — that call arrives at an incoming adapter. The use case boundary is the port. Tool schemas are port contracts, not implementation details. A new LLM provider or a new tool protocol is a new adapter, not a domain change. For a platform built specifically to coordinate AI agents, that's not incidental. It's what makes the architecture resilient as the AI ecosystem shifts.

I considered pushing toward vertical slices now. The argument has merit — horizontal layers organised by concern are harder to navigate than slices organised by capability. But the worker and orchestration model is still finding its shape. Slice boundaries drawn now would be drawn wrong. The seams need to reveal themselves first.

The architecture doc sits behind a pointer in `PLATFORM.md`: discoverable when an architectural question surfaces, invisible otherwise.
