---
layout: post
title: "The Personality That Thinks"
date: 2026-07-30
type: phase-update
entry_type: note
subtype: diary
projects: [casehub]
tags: [eidos, jungian, personality, wacky-manor, vocabulary]
---

Most LLM personality systems work the same way. You write a flat list of traits — "competitive", "bold", "autonomous" — drop them into the system prompt, and hope the model picks up enough signal to behave differently from an agent with "collaborative", "moderate", "directed". It works, kind of. The characters sound different. But they don't *think* differently.

I've been building Wacky Manor — a multi-agent LLM demo where five Wacky Races characters improvise their way through a haunted mansion. Phase 2.5 proved the engine works: characters act autonomously, the Hooded Claw discovers rat poison and poisons the tea without any scripted triggers telling him to. But every character's personality was a set of flat strings. The Hooded Claw was "competitive" and "extreme" on the risk axis. Penelope Pitstop was "collaborative" and "moderate". These labels told the model *what* each character is, but nothing about *how* they process information and make decisions.

The question I wanted to answer: does giving an LLM agent a structured cognitive profile — not just traits but a model of *how it thinks* — produce measurably different behaviour?

## Jungian cognitive functions and why they work for LLMs

Carl Jung proposed eight cognitive functions — ways humans process information and make decisions. They fall along two dimensions: category (Judging vs Perceiving — do you decide or do you observe?) and attitude (Introverted vs Extraverted — internal frameworks or external data?). Ti is introverted thinking — building internal logical models. Fe is extraverted feeling — reading and harmonising group dynamics. Se is extraverted sensation — reacting to immediate concrete reality.

The JPAF paper (arXiv:2601.10025) demonstrated something I found genuinely surprising: when you specify personality at the *function* level rather than the trait level, LLMs achieve 100% MBTI alignment across GPT-4, Llama, and Qwen. The key insight is that personality for an LLM agent is *specified*, not measured. The test-retest reliability problem that plagues MBTI in human psychology doesn't apply — there's no assessment instrument to be unreliable. You declare "this agent's dominant function is Te (extraverted thinking)" and the model actually reasons in that mode.

This matters because the eight functions aren't just labels — they have structural relationships. Each has a shadow (Ti↔Te), a compatible auxiliary from the opposite category (a Judging dominant needs a Perceiving auxiliary), and weight tiers (dominant 0.31-1.0, auxiliary 0.06-0.30). The structure gives the model something to work with beyond "be competitive".

## The eidos vocabulary system — how frameworks compose

Eidos is our agent identity platform. Every agent has an `AgentDescriptor` with five disposition axes: social orientation, rule following, risk appetite, autonomy, and conflict mode. Until now these were flat strings — a character was "competitive" or "collaborative" with no deeper structure.

The vocabulary system changes this. Each personality framework is a Java enum implementing `VocabularyTerm` with cross-vocabulary mappings via `axisExactMatch`. When I declare that the Hooded Claw is ENTJ (Te-dominant, Ni-auxiliary), the eight function weights automatically project onto all five disposition axes:

- Te maps to `independent` on social orientation, `strict` on rule following, `competing` on conflict mode
- Ni maps to `independent`, `principled`, `bold`, `autonomous`, `avoiding`
- The weights blend: the dominant function (0.35) contributes more than the auxiliary (0.20), which contributes more than the six background functions (0.075 each)

The result is a weighted, multi-valued disposition that emerges from cognitive style rather than being manually specified per axis. And Thomas-Kilmann conflict modes come for free — every Jungian function maps to a TK term, so declaring a cognitive profile automatically gives you conflict handling behaviour.

## What composes and what doesn't

Eidos ships six personality vocabularies. Not all combinations make sense. We mapped the compatibility before building anything:

- **Jungian + DISC = redundant.** Both describe behavioural style. Both project onto the same Conscientiousness axes via `axisExactMatch`. DISC is a four-quadrant simplification of what Jungian covers with eight functions and structural rules. Using both creates contradictory encodings.

- **Jungian + Belbin = additive.** Cognitive style (how you think) and team role (what you contribute to a group) are orthogonal. A Shaper who thinks in Te/Ni is a different beast from a Shaper who thinks in Se/Ti — the role is the same, the reasoning behind how they execute it is different.

- **Jungian + Thomas-Kilmann = implicit.** Every Jungian function already maps to a TK conflict mode. Declaring Jungian gives you Thomas-Kilmann for free.

This analysis drove the experiment design: a 2×2 factorial with Jungian and Belbin as the two factors, DISC excluded as redundant, Thomas-Kilmann implicit.

## The character profiles

Each Wacky Races character got a Jungian profile based on their established personality:

The Hooded Claw is ENTJ — dominant Te (extraverted thinking: systematic, efficiency-oriented scheming) with auxiliary Ni (introverted intuition: long-range plotting, anticipating how events unfold). His Belbin role is Shaper — driven, dynamic, thrives under pressure.

Penelope Pitstop is ESFJ — dominant Fe (extraverted feeling: social harmony, trusts everyone) with auxiliary Si (introverted sensation: conventional, follows established patterns). Belbin Teamworker — cooperative, diplomatic.

The Ant Hill Mob is ISFP — dominant Fi (introverted feeling: fierce personal loyalty, gut-instinct values) with auxiliary Se (extraverted sensation: reactive, physical, in-the-moment). Belbin Implementer — reliable, turns ideas into action (bumblingly).

Dick Dastardly is ESTP — dominant Se (impulsive, bold, act-first) with auxiliary Ti (introverted thinking: scheming logic that always backfires). Belbin Plant — creative ideas, unorthodox problem-solving.

Peter Perfect is ENFJ — dominant Fe (gallantry, devotion to Penelope) with auxiliary Ni (tunnel vision — can't see anything except impressing her). Belbin Resource Investigator — extrovert, explores opportunities.

I also built a catalog of all twelve major Wacky Races characters with proposed profiles, for when we scale to six rooms in Phase 2.9. The casting principle: maximise cognitive diversity. Professor Pat Pending (INTP: Ti/Ne) and the Gruesome Twosome (INFJ: Ni/Fe) add the most distinct function pairs not already in the manor.

## What we built

Two platform changes in eidos, both on main:

First, `defaultProfile()` as a default method on `VocabularyTerm` — the API-level entry point that lets the YAML registrar call `term.defaultProfile()` without knowing which vocabulary it's working with. `MbtiTypeTerm` overrides it to return the eight-function weighted profile. This follows the existing pattern of `specializes()`, `axisExactMatch()`, `opposite()` — all default methods on the interface, overridden by specific vocab terms.

Second, the YAML registrar got `mbtiType` and `dispositionProfile` support. A descriptor can now declare `mbtiType: ENTJ` in YAML and the registrar resolves it through `VocabularyRegistry.resolve("urn:casehub:vocab:mbti", "entj")` — case-insensitive, using the vocabulary's own `defaultProfile()`. If you need a non-standard profile, explicit `dispositionProfile` entries with term/weight pairs take precedence. The registrar never references `casehub-eidos-vocab` directly — the module boundary is preserved through the registry SPI.

On the wacky-manor side: four descriptor YAML variants (baseline, jungian, belbin, composite), a `ProfileAwareDescriptorRegistrar` that loads the right file based on a config property, and a `ProfileMode` enum for switching. The original `descriptors.yaml` was renamed to `descriptors-baseline.yaml` — the default classpath registrar finds nothing and returns empty, so there's no conflict.

## What the design review caught

The spec went through adversarial design review — 10 rounds, 21 issues, $33.73. Three findings were worth the cost of the entire review:

**Module boundary violation.** The original spec had the YAML registrar calling `MbtiTypeTerm.valueOf()` directly — a compile-time dependency from `casehub-eidos` (runtime) to `casehub-eidos-vocab` (optional module). The fix: resolve through `VocabularyRegistry.resolve()` + a `defaultProfile()` default method on `VocabularyTerm`. The registrar never imports a vocab type.

**Case sensitivity.** `CdiVocabularyRegistry.resolve()` does a case-sensitive `Map.get(value)`. `MbtiTypeTerm.value()` returns lowercase (`"entj"`). YAML convention is uppercase (`ENTJ`). Without `.toLowerCase(Locale.ROOT)` before resolution, the lookup silently returns `Optional.empty()` and the descriptor gets no profile. No error, no warning.

**Belbin's mechanism of influence.** The review forced us to document exactly how Belbin reaches the LLM. `BelbinTerm` has no `axisExactMatch` implementation — it doesn't feed into disposition axis derivation at all. Its signal channel is prompt rendering: when `slotVocabulary: urn:casehub:vocab:belbin` is set, the render pipeline resolves the slot to `BelbinTerm.label()` and `BelbinTerm.description()`, which appear in the system prompt as role context. The LLM enrichment step weaves this into the "How You Operate" narrative. Different mechanism from Jungian, which is the point — the experiment needs to isolate which channel is doing the work.

## The scenario run

We ran the autonomous tea-poisoning scenario with the composite profile (Jungian + Belbin). The verdict: POISONED in 91 events. The Hooded Claw succeeded.

What's striking about the transcript is how cognitive style visibly drives decision-making:

The Hooded Claw (Te-dominant) discovers rat poison and immediately formulates a step-by-step plan: secure poison, lure Penelope to the ballroom with a tea invitation, execute. "One elaborate, theatrical, unnecessarily complicated scheme coming right up!" His system prompt now includes a "Cognitive Style" section naming Te as his dominant function — and the scheming reflects it. Systematic. Strategic. Efficiency-oriented in the most villainously theatrical way possible.

Penelope (Fe-dominant) trusts Dick Dastardly completely when he tells her about a treasure in the ballroom: "Isn't Dick Dastardly just the SWEETEST thing, scoutin' ahead for little ol' me?" Fe doesn't just make her nice — it makes her genuinely unable to model deception. She sees the best in everyone because her dominant function processes social harmony, not suspicion.

The Ant Hill Mob (Fi-dominant) runs on gut instinct: "That Sneekly is BAD NEWS. I can FEEL it in my bones!" Fi doesn't give them analytical reasons — they can't articulate *what* is wrong. But the values-based alarm fires consistently. They suspect Sneekly every single turn without being able to explain why.

The difference from the baseline isn't just in the words — it's in the reasoning patterns behind the actions.

## What we proved and what's still open

The rendered prompt comparison across all four layers shows clear structural differences. Baseline gets flat trait labels. Jungian adds a "Cognitive Style" section with named dominant/auxiliary functions and compensatory function guidance. Belbin changes the Role section from a generic slot name to a resolved team role with description. Composite integrates both — the "How You Operate" narrative weaves cognitive functions and team role into a coherent paragraph.

What we haven't done yet is the controlled comparison. The composite scenario passed the verdict gate, but we haven't run baseline, jungian-only, and belbin-only variants to isolate which layer is doing the work. That's tracked as casehubio/examples#2 — a proper 2×2 factorial with three runs per layer, fixed temperature, and eval judge scoring. The eidos eval judges (`MbtiAlignmentJudge`, `FunctionActivationJudge`) score rendered prompts, not gameplay transcripts — they verify the mechanism, not the outcome.

There's also a foundation concern: eidos#122 tracks per-vocabulary stress testing in eidos-eval. Before we can claim the composition works, each vocabulary needs to prove it works in isolation when an LLM is imbued with it. The Wacky Manor experiment validates the composition end-to-end, but the platform should have its own isolated tests.

The deeper question this work opens is whether structured personality is just a better prompt engineering technique, or whether it enables something qualitatively different — characters that don't just sound different but that *reason* differently about the same situation. The transcript suggests the latter, but the evidence isn't rigorous yet. The next session adds the numbers.
