---
layout: post
title: "Channel Taxonomy: From FIPA to First Principles"
date: 2026-06-26
type: phase-update
entry_type: note
subtype: diary
projects: [casehub]
tags: [channels, qhorus, fipa, architecture]
---

When I originally designed qhorus's `MessageType` enum, I modelled it on FIPA ACL — the IEEE standard for agent communication that grew out of speech act theory. FIPA defines 22 communicative acts. Qhorus has 9. I'd never written down why.

Formalising the channel taxonomy forced that question. The answer turned out to be more interesting than "we simplified."

Fourteen FIPA acts merge into nine qhorus types:

| MessageType | FIPA acts absorbed |
|---|---|
| COMMAND | request, cfp |
| QUERY | query-if, query-ref |
| RESPONSE | inform, confirm, propose, inform-if, inform-ref |
| DECLINE | refuse, reject-proposal |
| DONE | accept-proposal |
| FAILURE | failure |
| HANDOFF | proxy |
| STATUS | *(carved from inform — new)* |
| EVENT | *(no FIPA equivalent — new)* |

The remaining eight weren't dropped for being unneeded. They were relocated:

| FIPA act | Why dropped | CaseHub layer |
|---|---|---|
| agree | Implicit — starting work signals acceptance | Engine |
| cancel | Case lifecycle, not agent discourse | Engine |
| disconfirm | Informational — RESPONSE covers disagreement | Not needed |
| not-understood | Error handling, not communication | Qhorus (exceptions) |
| propagate | Infrastructure fan-out | Claudony (fleet relay) |
| request-when | Reactive triggers | Engine (CDI events) |
| request-whenever | Reactive triggers | Engine (CDI events) |
| subscribe | Structural membership, not negotiated | Engine (`CaseChannelLayout`) |

The principle: FIPA tried to handle everything at the speech act layer. CaseHub separates transport semantics (qhorus), orchestration topology (engine), and application-domain concerns (drafthouse, future apps) into distinct layers that depend only downward. What FIPA treated as a flat vocabulary, we treat as a stack.

That stack turned out to be the organising insight for the entire taxonomy. Every channel pattern plugs in at one layer: coordination patterns at the engine layer, deliberation patterns at the application layer as `ChannelBackend` implementations, notification adapters at the connector-backend submodule. The stack answers the question third-party builders will ask: where do I plug in?

The taxonomy itself needed first-principles work. The existing CHANNELS.md listed six purpose categories including "governance" as distinct from "coordination." But governance IS coordination at the speech act level — the oversight channel uses the same COMMAND → RESPONSE obligation pattern as the work channel. The difference is who participates and which message types are excluded, not the fundamental communication shape. Folding governance into coordination cleaned up the discriminator table and let consensus — M-of-N approval gates — sit naturally as a coordination sub-pattern rather than floating between categories.

Consensus also surfaced an architectural question. My initial instinct was to pair it with `ChannelSemantic.BARRIER` — "releases when all declared contributors have written." But BARRIER means ALL contributors. Consensus is M-of-N. A review caught this and it triggered a cleaner decomposition: the transport semantic should be APPEND (votes accumulate in order), and the threshold logic belongs in the `ConsensusChannelBackend`. `ChannelSemantic` describes data flow. Deciding when M votes is enough is application logic, not transport behaviour.

What we ended up with: five purpose categories, twelve channel patterns, eight discriminator dimensions, and a purpose × semantic cross-reference matrix:

| Channel Pattern | APPEND | COLLECT | BARRIER | EPHEMERAL |
|---|---|---|---|---|
| Agent Mesh (work/observe/oversight) | **primary** | | | |
| Ad-hoc Engine | **primary** | | | |
| Consensus Gate | **primary** | | alternative | |
| Debate | **primary** | | | |
| Review | **primary** | | | |
| Negotiation | **primary** | alternative | | |
| Planning | **primary** | | | |
| Signal | | | | **primary** |
| Notification | **primary** | | | alternative |
| Infrastructure | **primary** | | | alternative |

APPEND dominates because most conversation patterns accumulate history. Threshold logic belongs in the application layer, not the transport semantic. BARRIER is only the N-of-N special case.

The open patterns — negotiation (Contract Net heritage), planning (goal decomposition), and consensus — each got API sketches showing the probable `ChannelBackend` shape and CDI event pattern. Not implementations — building blocks. The more blocks exist, the more third-party applications become possible.

The academic cross-reference was worth doing. Sander et al. (2026) predict the agent protocol ecosystem will converge toward federated, layered protocol stacks rather than monolithic standards. CaseHub's internal architecture already demonstrates that principle. The layers don't correspond — Sander's stack spans protocol standards across systems; ours is internal architectural separation — but both independently arrive at the same structural insight. Sometimes you're ahead of the literature without realising it.
