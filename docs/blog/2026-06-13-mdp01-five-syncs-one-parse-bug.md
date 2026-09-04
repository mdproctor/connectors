---
layout: post
title: "Five Doc Syncs and One Parse Bug"
date: 2026-06-13
type: phase-update
entry_type: note
subtype: diary
projects: [casehub]
tags: [docs, platform, debugging]
---

The first thing ctx.py returned was `OWNER_REPO=**`. Not `casehubio/parent` — two asterisks.

Claude and I traced it back to the `## Work Tracking` section in CLAUDE.md. At some point the section had acquired bold markdown formatting: `**GitHub repo:** casehubio/parent`. The ctx.py regex `GitHub repo:\s*(\S+)` finds `GitHub repo:` correctly, then `\s*(\S+)` captures the first non-whitespace after the colon — in bold markdown, that's the closing `**`. The actual repo name sits after a space that the regex has already consumed.

Three-line fix: strip the asterisks, plain key-value pairs. The interesting part is the failure mode. `ISSUES_OK=no`, `OWNER_REPO=**`, everything downstream of ctx.py silently broken. No error, no warning. The symptom points at issue tracking configuration; the cause is markdown formatting. It's the kind of breakage that can persist across sessions if partial enough to not surface obviously.

The five doc syncs varied in weight.

`QhorusInboundCurrentPrincipal` moved from `@Alternative @Priority(1)` to `@DefaultBean`. In CDI terms these aren't equivalent: `@Alternative` requires explicit activation via `quarkus.arc.selected-alternatives`; `@DefaultBean` activates by default and yields to any `@Alternative` on the classpath. The accompanying test note matters — modules with both qhorus runtime and casehub-platform need `quarkus.arc.exclude-types=io.casehub.platform.mock.MockCurrentPrincipal`. Without it, two `@DefaultBean` implementations of the same SPI produce an `AmbiguousResolutionException` with no obvious diagnosis path.

`EndpointRegistry` is new platform infrastructure: an SPI for named endpoint registration by `(Path, tenancyId)` with `EndpointProtocol` (HTTP, GRPC, KAFKA, MCP, CAMEL, QHORUS), protocol-specific properties, and a `credentialRef` for secrets integration. `NoOpEndpointRegistry @DefaultBean` in casehub-platform, `InMemoryEndpointRegistry @Alternative @Priority(100)` in a new `endpoints-memory` module. The outbound auth section of PLATFORM.md already referenced it; the capability ownership table didn't. We closed that gap — the kind of inconsistency that sends developers hunting for something that's already implemented.

Devtown's ledger integration is now complete. The `POST /api/incident-feedback` endpoint records FLAGGED attestations when a production incident traces back to a missed review — closing the trust feedback loop the layer was built for.

Clinical's Layer 8 is partial. `ClinicalActionRiskClassifier` and `SusarCriteriaEvaluator` are wired; worker binding to `ae-escalation.yaml` is deferred. The tutorial table used to list Layer 8 as "Comparison vs ClinicalAgent." Now it has a real partial implementation where the placeholder was.

The parse bug was the most useful thing to come out of the session — three lines that make session startup reliable for every session that follows.
