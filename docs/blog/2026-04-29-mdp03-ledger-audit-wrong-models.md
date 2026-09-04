---
layout: post
title: "Three audit findings and a wrong mental model"
date: 2026-04-29
type: phase-update
entry_type: note
subtype: diary
projects: [quarkus-work]
tags: [ledger, audit, bayesian, jackson, java]
excerpt: "Three audit findings include a String.format JSON builder that silently corrupts output on non-trivial actor IDs, a bug the auditor described with the wrong mechanism, and a Bayesian mental model error about what a Beta distribution actually computes."
---

An external audit of `quarkus-work-ledger` came back with three findings. I worked through them with Claude.

## String.format JSON: the quiet injection risk

`buildDecisionContext` was building a JSON snapshot using `String.format`:

```java
return String.format(
    "{\"status\":\"%s\",\"priority\":\"%s\",\"assigneeId\":%s,\"expiresAt\":%s}",
    wi.status, wi.priority,
    wi.assigneeId != null ? "\"" + wi.assigneeId + "\"" : "null",
    wi.expiresAt != null ? "\"" + wi.expiresAt + "\"" : "null");
```

A quote or backslash in any actor ID silently produces malformed JSON. It passes every test and breaks quietly in production when someone uses a non-trivial identifier. We replaced it with Jackson `ObjectNode`, which is already on the classpath via `quarkus-rest-jackson` and handles escaping correctly.

## The bug the auditor described incorrectly

The second finding: `eventSuffix()` can return null, and the downstream `EVENT_META.get(null)` — where `EVENT_META` uses `Map.ofEntries()` — "returns null, and the subsequent array index throws NPE."

Except `Map.ofEntries().get(null)` doesn't return null. It throws `NullPointerException` directly. `Map.of()` and `Map.ofEntries()` reject null in all operations including reads — unlike `HashMap`, where `get(null)` silently returns null. The auditor had the same `HashMap` mental model as most Java developers.

The fix was straightforward — guard against null before calling `get()`. What's worth noting is that the audit's own description of the bug was wrong. That's how deep the `HashMap` assumption runs.

## Eight tests that didn't know their own algorithm

`TrustScoreComputerTest` had 8 failing expectations. Tests were asserting `score ≈ 1.0` for actors with clean decision records and no attestations. The actual result was 0.5.

The algorithm uses a Bayesian Beta model with prior Beta(1, 1). Score = α/(α+β). With no attestations: α=1.0, β=1.0 → score=0.5. Unattested decisions contribute nothing — only attestations move the needle. "No evidence of wrongdoing" and "trustworthy" are not the same thing to a Bayesian model.

The same team who wrote the algorithm had assumed the intuitive model: clean history implies high trust. The math says: no evidence implies maximum uncertainty.

Two of those tests also had a subtler problem. The recency fixture set `decision.occurredAt` but left `attestation.occurredAt` null. The decay function uses the attestation timestamp. With null `occurredAt`, every attestation defaults to age=0 and gets identical weight — the test was asserting recency weighting without actually exercising it.

We fixed all 8. 76 tests pass in `quarkus-work-ledger`.
