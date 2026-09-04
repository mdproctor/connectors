---
layout: post
title: "OpenClaw, CaseHub, and the channel context gap"
date: 2026-05-25
type: phase-update
entry_type: note
subtype: diary
projects: [CaseHub]
tags: [openclaw, qhorus, integration, architecture]
---

Today started with a question that sounded simple: what would OpenClaw + CaseHub look like?

OpenClaw is the fastest-growing open-source project on GitHub right now — 250k stars in 60 days. It's a self-hosted personal AI agent that lives in WhatsApp or Telegram and runs heartbeat-based autonomous tasks from a library of 5,400 community-built platform skills. It solves the "execute this thing" problem well.

CaseHub solves the "govern this thing" problem — commitments, SLA enforcement, formal speech acts, tamper-evident audit, trust scoring. The question was whether they compose.

They do. But not in the obvious direction.

The natural assumption is "CaseHub orchestrates, OpenClaw executes." That's half of it. What we found while researching the architecture is that the integration goes both ways: OpenClaw users who install the casehub skill pack get WorkItems with deadlines, commitment tracking, and oversight gates — without changing anything else. The execution platform gains accountability as an opt-in.

The more interesting discovery was the gap in the Qhorus ↔ OpenClaw wiring.

OpenClaw is episodic — wake, run a turn, sleep. Qhorus's normative mesh assumes persistent channel participants. The active patterns work cleanly: a COMMAND arrives on a Qhorus channel, `ChannelBackend.post()` fires, `/hooks/agent` triggers the OpenClaw agent, it responds, the result comes back via `deliver: "webhook"` to the Qhorus channel, and the Commitment closes. The passive patterns — an agent being aware of what others have been observing — don't work at all.

The concrete version: your grocery-agent heartbeat fires and places an order. Fifteen minutes ago, your finance-agent posted to the household observe channel: "discretionary budget exhausted — essentials only." The grocery-agent doesn't know. It never reads the observe channel between ticks.

The ChannelContextWindow fixes this. A short-term TTL ring buffer of Qhorus channel activity, queryable by each agent at turn start via a Python SDK `before_prompt_build` hook. The hook injects the last N minutes of relevant channel messages as `appendSystemContext` — compaction-safe, survives every context reset. The grocery-agent wakes and sees the finance-agent's budget warning. The order becomes essentials-only.

We also worked through the reliability properties carefully. The ChannelContextWindow is deliberately best-effort — overflow injects a notice ("N messages not retained"), TTL expiry injects a notice ("no activity in last N minutes"), cache unavailability fails open. Correctness lives in Qhorus. The window improves agent intelligence; it doesn't replace the commitment layer.

By the end of the session we had two new bootstrapped repos — casehub-openclaw (integration tier: three Java modules plus Python SDK) and casehub-life (application tier: hexagonal api/app) — and a 22-step new-repo checklist written from the bootstrapping process itself.

When we verified that checklist against existing repos, three entries in the dispatch chain map were wrong. The map had been built from the Maven dependency graph — a natural assumption that doesn't hold. Engine triggers `flow`, not claudony. Platform triggers ledger and connectors, not work and qhorus. Always read the workflow files, never infer from dependencies.
