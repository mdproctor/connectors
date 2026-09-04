---
layout: post
title: "Distributed SSE and the infrastructure tax"
date: 2026-04-29
type: pivot
entry_type: note
subtype: diary
projects: [quarkus-work]
tags: [distributed, sse, spi, architecture, broadcaster]
excerpt: "Redis for distributed SSE fan-out is rejected because it would force two new infrastructure pieces on users who have neither Redis nor MongoDB — a pluggable WorkItemEventBroadcaster SPI defers the choice to the deployment."
---

The broadcaster issue had Redis in the solution description from the start. Makes sense — Redis pub/sub is the canonical answer to fan-out across nodes. But before writing a line of code I asked the question that should have come first: does quarkus-work currently use Redis for anything else?

It doesn't. And MongoDB is on the roadmap as an alternative persistence backend.

That combination — Redis for SSE fan-out, MongoDB for persistence — means two pieces of infrastructure a user has to stand up just to run quarkus-work in a cluster. For an extension that prides itself on being lightweight and optional-by-default, that's a real cost.

I pulled Claude in to work through the options:

- **PostgreSQL LISTEN/NOTIFY:** zero new infrastructure for anyone on the default JPA/PostgreSQL stack. Falls apart if you switch to MongoDB persistence.
- **Vert.x clustered event bus:** Quarkus clustering already uses Infinispan under the hood. If you're running a cluster, the event bus is already distributed. Falls apart if you're not.
- **Pluggable SPI:** `WorkItemEventBroadcaster` becomes an interface. The default stays in-process (the current Mutiny `BroadcastProcessor`). Optional modules provide Redis, PostgreSQL LISTEN/NOTIFY, or Vert.x variants. Users bring what they already have.

The SPI approach is the right one. It imposes zero infrastructure change on the majority of users — those running single-node or happy with current SSE behaviour — and gives distributed users a clean extension point for whatever fits their stack. The same concern applies to `WorkItemQueueEventBroadcaster` in the queues module; same fix there too.

I've deferred the implementation. The distributed broadcaster only matters when someone is actually running quarkus-work in a multi-node cluster — a fairly advanced deployment for a human task inbox. The architecture decision is captured; the code can wait for a concrete use case.
