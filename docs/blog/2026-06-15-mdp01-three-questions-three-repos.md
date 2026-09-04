---
layout: post
title: "Three questions, three repos unblocked"
date: 2026-06-15
type: phase-update
entry_type: note
subtype: diary
projects: [casehub]
tags: [architecture, streams, desiredstate, cloudevents]
---

Three questions, three repos unblocked.

The spec I've been avoiding is done. Not because the questions were hard to answer — they weren't, once I sat down with them — but because getting the answers *right* required reading enough code to know what constraints actually existed rather than what I imagined they did.

The three questions: where does `SensoryEvent` live, where do the platform stream modules go, and where does the deployment YAML compiler go? I'd parked them in the research doc two sessions ago as "P0 — blocks everything." They did. Nothing in casehub-ras, casehub-desiredstate, or casehub-ops could be implemented until I resolved them.

**Naming is architecture**

The first thing to go was the name. `SensoryEvent` made sense as the Reticular Activating System metaphor — the RAS observes sensory input. But if this type lives in `casehub-platform-api` as a general primitive used by casehub-iot, casehub-qhorus, casehub-connectors, and five stream modules, calling it `SensoryEvent` is naming it after one consumer. That's like naming `CurrentPrincipal` after the first feature that needed it.

CloudEvents already solved this. The CNCF spec (graduated January 2024) is exactly what I needed: a typed, source-agnostic event envelope with standard fields for type, source, subject, and payload. CasehHub is built on CNCF Serverless Workflow — CloudEvents is the sibling spec. Quarkus SmallRye messaging has native CloudEvents support. There's no wrapper type. The CDI event type is `io.cloudevents.CloudEvent` directly.

What surprised me: `subject` and `tenancyId` needed different treatment. `subject` in CloudEvents means the entity the event concerns (`device/thermostat-1`, `patient/1234`). Tenancy is a CloudEvents extension attribute (`tenancyid`) — caller-supplied for Kafka (trusted internal producer), operator-configured for webhook and poll (untrusted external caller). That distinction matters when webhook requests arrive from the public internet. I hadn't thought it through when I first wrote the spec.

**GoalCompiler already existed**

I assumed I'd need to define the `GoalCompiler` SPI. It already existed in `casehub-desiredstate-api` with a dungeon example proving the pattern. What it doesn't have yet is recognition of what it's becoming: the platform's universal intent-to-execution bridge. The same interface, parameterised differently, compiles dungeon blueprints into desired-state graphs and CasehHub deployment YAML into reconciliation graphs. Once `GoalCompiler<G, P>` gets a second type parameter for the plan output, it could compile case definitions into engine plan models too. Issue #233 tracks this; for now the SPI stays where it is.

**One provisioner, one problem**

The deployment architecture broke when I read `SimpleTransitionExecutor`. It takes a single `NodeProvisioner` via CDI constructor injection. One. A CasehHub deployment graph has channel nodes (qhorus), situation nodes (casehub-ras), agent nodes (claudony), and stream endpoint nodes (casehub-ops). Putting all of that into one mega-provisioner in casehub-ops would mean ops pulling in qhorus internals, RAS internals, and claudony — exactly what I was trying to avoid.

The fix was a pattern that already exists in the desiredstate codebase: `CaseTransitionExecutor` in `engine-adapter/` is `@ApplicationScoped` without `@DefaultBean`, displacing `SimpleTransitionExecutor @DefaultBean` by classpath presence. We need the same for provisioner dispatch — a `CompositeTransitionExecutor` in a new `casehub-desiredstate-composite` module that injects `@All Instance<NodeProvisioner>` and routes by `canHandle(DesiredNode)`. Each domain repo owns its provisioner. `ReconciliationLoop` handles the adapter side the same way.

**The circular dependency**

I had `StreamNodeTypes` and `StreamEndpointSpec` slated for `casehub-platform-api`. Claude caught why that breaks: `casehub-desiredstate-api` already depends on `casehub-platform-api`. Putting `NodeType` wrappers in platform-api would require platform-api to depend on desiredstate-api — a confirmed circular Maven dependency. The fix for P0 was to put stream endpoint constants in `casehub-ops/deployment` where they're actually used. The right long-term fix — moving `NodeType` and `NodeSpec` themselves to `casehub-platform-api`, which every domain API module already depends on — is tracked separately and worth doing, but it's a separate breaking change.

**What's ready**

Five child specs are filed across casehub-platform, casehub-ops, casehub-qhorus, casehub-iot, and casehub-connectors. The main spec in `docs/superpowers/specs/2026-06-13-p0-layering-decisions-design.md` links them all. Wave 1 can start immediately in parallel: platform#98 (CloudEvent foundation) and the casehub-desiredstate multi-provisioner issue are independent of each other. Wave 2 — ops, qhorus provisioner, ras, claudony, the adapters — unblocks once both land.

The recursive accountability story is intact: CasehHub governs its own deployment using the same primitives it gives its users. Every infrastructure change audited by its own ledger, gated by its own governance gates, executed by trust-weighted agents. That was worth the three questions.
