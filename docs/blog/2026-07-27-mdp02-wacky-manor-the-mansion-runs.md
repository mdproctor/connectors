---
layout: post
title: "Wacky Manor: The Mansion Runs"
date: 2026-07-27
type: phase-update
entry_type: note
subtype: diary
projects: [casehub]
tags: [eidos, qhorus, game-engine, multi-agent, wacky-races, ui]
---

The previous entry proved the characters could talk. This one proves they can act — navigate rooms, pick up objects, trigger plot beats, and produce an actual scene where the Ant Hill Mob accidentally saves Penelope from being poisoned. With a browser UI showing SVG character illustrations sliding between rooms while dialogue streams into per-room chat panels.

The whole stack: Eidos descriptors → SystemPromptRenderer → AgentProvider (Claude CLI) → structured JSON response → ActionResolver → TriggerEvaluator → SceneDirector → Qhorus channels → WebSocket → Lit frontend. Built in one session. 113 tests. The live scenario took five minutes of LLM calls and produced something genuinely funny.

## The Game Engine

The game engine is deliberately simple — a single-threaded game loop draining an action queue, with character agents running on virtual threads. Each character independently calls the LLM with an observation of the world (what room they're in, what objects are visible, who's nearby, what happened recently), gets back a structured JSON response with dialogue, an aside for the audience, and an action, then submits the action to the queue.

The game loop resolves one action at a time — deterministic, no race conditions on world state. After each action, triggers are evaluated (condition → effect pairs loaded from YAML), and if a trigger fires a scene start, the SceneDirector runs scripted beats on the game loop thread while non-participating characters continue acting.

The action vocabulary is classic adventure game: MOVE, INTERACT, TAKE, GIVE, USE, LOOK, WAIT. Proximity enforcement means characters have to walk to an object before they can interact with it. Item dependencies create chains — Dastardly's fake medal → give to Muttley → brass key → unlock cabinet → recipe cards. The Hooded Claw's poison chain: discover → take → move to ballroom → use on tea service.

All of this is testable without LLM calls. WorldState, ActionResolver, TriggerEvaluator, SceneDirector — 113 unit and integration tests covering happy paths, boundary values, error paths, and a full end-to-end scenario test that plays through the entire tea poisoning without touching the network.

## Qhorus: Not Just a Message Bus

The POC exercises Qhorus properly — four channels with correct speech acts and constraints:

```
manor/work      — dialogue + coordination (APPEND, open types)
manor/observe   — telemetry events (APPEND, EVENT only)
manor/audience  — narrator + villain asides (APPEND, STATUS only)
manor/oversight — governance gate (APPEND, denies EVENT)
```

The normative layout's `/observe` channel is EVENT-only, and EVENT messages can't carry content (the MessageDispatch builder throws if you try). Narrator commentary is prose — it needs STATUS on a separate `/audience` channel. This is a legitimate departure from the 3-channel normative layout, not a hack. The CHANNELS.md taxonomy supports it: the audience channel is a content-bearing broadcast channel, not a telemetry stream.

The scenario lifecycle uses proper speech acts: COMMAND to start (with correlationId), STATUS for dialogue progress, EVENT for position telemetry, DONE to close. The Qhorus integration test — 19 @QuarkusTest assertions — caught four real bugs: channel slug validation, cross-tenant dispatch from QhorusInboundCurrentPrincipal silently displacing MockCurrentPrincipal, missing ledger tables (Qhorus dispatch writes ledger entries), and an upstream Eidos API break where AgentConstraint gained a required `severity` field.

## The Tea Scene

The live scenario output is the verdict gate. The Hooded Claw enters the kitchen, sees poison in his observation (only he can — visibility filtering), and the trigger fires narrator commentary. He takes the poison, moves to the ballroom where Penelope is having tea, and the tea-poisoning scene triggers.

The scene runs four beats: offer tea, pour poison, foil, aftermath. Each beat's narration is pre-written (the canned text from the YAML), but the character dialogue is LLM-generated. The foil beat has alternatives — if the Ant Hill Mob are in the ballroom, they knock the cup; otherwise Blubber Bear wakes up and crashes into the table.

With the Mob present, Clyde's response:

> Boys! BOYS! Did you see that?! That no-good, sneaky, underhanded — oh, what's his name — SNEEKLY! He just put somethin' in Miss Pitstop's tea cup!
>
> *trips over Snoozy, who has fallen asleep on the floor*
>
> WHOOOA — CRASH — BANG —
>
> *stumbles forward, arms flailing wildly, knocking into the tea service table*
>
> CLATTER! SMASH! TINKLE!
>
> *the entire tea set goes flying*
>
> I'm SO sorry, Miss Pitstop, ma'am! I'm a clumsy, no-good, butterfingers bum!
>
> *turns to the boys, whispering urgently*
>
> Psst! Dum-Dum! Zippy! You see what happened there? The cup! THE CUP! It's gone now! Whatever Sneekly put in it is all over the floor! We SAVED her, boys! By ACCIDENT, but we saved her!

He trips over Snoozy (who wasn't mentioned in the prompt — the LLM invented that detail), destroys the tea service, saves Penelope without intending to, and whispers to the boys about their accidental heroism. The bumbling-but-loyal pattern from the descriptor is fully realised.

Sneekly's aftermath response is equally good — suppressed rage behind an oily smile, mourning his wasted poison ("Six WEEKS cultivating that toxin!"), and immediately pivoting to the next scheme ("I hear the mechanical topiary is simply... *to die for*").

## The UI

The frontend is Lit 3 web components served by Vite, proxied to the Quarkus backend via WebSocket. The mansion is an SVG cross-section with three rooms side by side. Each character has an SVG illustration — simple shapes, but distinctive enough to tell apart at a glance:

<div style="display: flex; gap: 24px; justify-content: center; flex-wrap: wrap; margin: 20px 0;">

<figure style="text-align: center; margin: 0;">
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 40" width="60" height="100">
<ellipse cx="12" cy="10" rx="9" ry="10" fill="#F5D76E"/>
<ellipse cx="4" cy="14" rx="4" ry="7" fill="#F5D76E"/>
<ellipse cx="20" cy="14" rx="4" ry="7" fill="#F5D76E"/>
<circle cx="12" cy="9" r="7" fill="#FDDCB5"/>
<ellipse cx="9.5" cy="8" rx="1.8" ry="2" fill="white"/>
<ellipse cx="14.5" cy="8" rx="1.8" ry="2" fill="white"/>
<circle cx="10" cy="8.3" r="1" fill="#4488CC"/>
<circle cx="15" cy="8.3" r="1" fill="#4488CC"/>
<path d="M7.5 6.5 L7 5" stroke="#333" stroke-width="0.6" fill="none"/>
<path d="M16.5 6.5 L17 5" stroke="#333" stroke-width="0.6" fill="none"/>
<path d="M10 12 Q12 14 14 12" stroke="#CC5555" stroke-width="0.7" fill="none"/>
<ellipse cx="12" cy="3" rx="9" ry="4" fill="#FF69B4"/>
<rect x="5" y="2" width="14" height="2.5" rx="1" fill="#FF1493"/>
<path d="M7 16 L5 35 L19 35 L17 16 Z" fill="#FF69B4"/>
<rect x="6" y="22" width="12" height="1.5" fill="#FF1493" rx="0.5"/>
<circle cx="12" cy="22.5" r="1" fill="#FFD700"/>
<path d="M7 18 Q3 24 4 28" stroke="#FDDCB5" stroke-width="2" fill="none" stroke-linecap="round"/>
<path d="M17 18 Q21 24 20 28" stroke="#FDDCB5" stroke-width="2" fill="none" stroke-linecap="round"/>
<rect x="6" y="35" width="4" height="3" rx="1" fill="#FF1493"/>
<rect x="14" y="35" width="4" height="3" rx="1" fill="#FF1493"/>
</svg>
<figcaption style="font-size: 11px; color: #888;">Penelope Pitstop</figcaption>
</figure>

<figure style="text-align: center; margin: 0;">
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 40" width="60" height="100">
<ellipse cx="12" cy="7" rx="7" ry="8" fill="#333"/>
<ellipse cx="12" cy="9" rx="6" ry="7" fill="#E8D5B7"/>
<ellipse cx="9.5" cy="8" rx="1.5" ry="1" fill="white"/>
<ellipse cx="14.5" cy="8" rx="1.5" ry="1" fill="white"/>
<circle cx="10" cy="8" r="0.7" fill="#2a4a2a"/>
<circle cx="15" cy="8" r="0.7" fill="#2a4a2a"/>
<path d="M8 6.5 Q9.5 5.5 11 6.5" stroke="#333" stroke-width="0.6" fill="none"/>
<path d="M13 6.5 Q14.5 5.5 16 6.5" stroke="#333" stroke-width="0.6" fill="none"/>
<path d="M9 12 Q12 13 15 12" stroke="#555" stroke-width="0.5" fill="none"/>
<path d="M6 16 L4 35 L20 35 L18 16 Z" fill="#2d5a27"/>
<path d="M10 16 L8 24 L12 24 Z" fill="#1a3a15"/>
<path d="M14 16 L16 24 L12 24 Z" fill="#1a3a15"/>
<path d="M11.5 16 L11 26 L13 26 L12.5 16 Z" fill="#8B0000"/>
<path d="M6 18 Q2 24 3 28" stroke="#2d5a27" stroke-width="2.5" fill="none" stroke-linecap="round"/>
<path d="M18 18 Q22 24 21 28" stroke="#2d5a27" stroke-width="2.5" fill="none" stroke-linecap="round"/>
<circle cx="3" cy="28" r="1.5" fill="#E8D5B7"/>
<circle cx="21" cy="28" r="1.5" fill="#E8D5B7"/>
<rect x="5" y="35" width="5" height="3" rx="1" fill="#222"/>
<rect x="14" y="35" width="5" height="3" rx="1" fill="#222"/>
</svg>
<figcaption style="font-size: 11px; color: #888;">The Hooded Claw</figcaption>
</figure>

<figure style="text-align: center; margin: 0;">
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 40" width="60" height="100">
<circle cx="4" cy="10" r="3.5" fill="#C4A882"/>
<rect x="0" y="6" width="8" height="3" rx="1.5" fill="#4a3728"/>
<circle cx="3" cy="10" r="0.7" fill="white"/>
<circle cx="5" cy="10" r="0.7" fill="white"/>
<path d="M1 13 L0 30 L8 30 L7 13 Z" fill="#5a3a1e"/>
<circle cx="20" cy="10" r="3.5" fill="#C4A882"/>
<rect x="16" y="6" width="8" height="3" rx="1.5" fill="#4a3728"/>
<circle cx="19" cy="10" r="0.7" fill="white"/>
<circle cx="21" cy="10" r="0.7" fill="white"/>
<path d="M17 13 L16 30 L24 30 L23 13 Z" fill="#5a3a1e"/>
<circle cx="12" cy="8" r="5" fill="#D2B48C"/>
<rect x="5" y="3" width="14" height="4" rx="2" fill="#4a3728"/>
<rect x="6" y="5" width="12" height="2" fill="#333"/>
<ellipse cx="10" cy="8" rx="1.2" ry="1.4" fill="white"/>
<ellipse cx="14" cy="8" rx="1.2" ry="1.4" fill="white"/>
<circle cx="10.3" cy="8.2" r="0.7" fill="#333"/>
<circle cx="14.3" cy="8.2" r="0.7" fill="#333"/>
<path d="M10 11 Q12 12.5 14 11" stroke="#555" stroke-width="0.5" fill="none"/>
<path d="M6 13 L4 35 L20 35 L18 13 Z" fill="#6B4226"/>
<rect x="7" y="18" width="10" height="1" fill="#444" rx="0.5"/>
</svg>
<figcaption style="font-size: 11px; color: #888;">The Ant Hill Mob</figcaption>
</figure>

<figure style="text-align: center; margin: 0;">
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 40" width="60" height="100">
<rect x="6" y="-2" width="12" height="8" rx="1" fill="#2a0a3a"/>
<ellipse cx="12" cy="6" rx="9" ry="2.5" fill="#2a0a3a"/>
<rect x="7" y="3" width="10" height="1" fill="#6a0dad"/>
<ellipse cx="12" cy="10" rx="6" ry="5.5" fill="#E8D5B7"/>
<ellipse cx="9.5" cy="9" rx="1.5" ry="1.2" fill="white"/>
<ellipse cx="14.5" cy="9" rx="1.5" ry="1.2" fill="white"/>
<circle cx="10" cy="9.2" r="0.8" fill="#333"/>
<circle cx="15" cy="9.2" r="0.8" fill="#333"/>
<path d="M8 12 Q6 11 4 12" stroke="#333" stroke-width="1" fill="none"/>
<path d="M16 12 Q18 11 20 12" stroke="#333" stroke-width="1" fill="none"/>
<path d="M8 12 L16 12" stroke="#333" stroke-width="0.5"/>
<path d="M10 13.5 Q12 14.5 14 13.5" stroke="#555" stroke-width="0.5" fill="none"/>
<path d="M6 16 L3 35 L21 35 L18 16 Z" fill="#6a0dad"/>
<path d="M6 16 L8 13 L12 16" fill="#5a0a9d"/>
<path d="M18 16 L16 13 L12 16" fill="#5a0a9d"/>
<path d="M6 18 Q1 24 2 28" stroke="#6a0dad" stroke-width="2.5" fill="none" stroke-linecap="round"/>
<path d="M18 18 Q23 24 22 28" stroke="#6a0dad" stroke-width="2.5" fill="none" stroke-linecap="round"/>
<circle cx="2" cy="28" r="1.5" fill="#444"/>
<circle cx="22" cy="28" r="1.5" fill="#444"/>
<rect x="5" y="35" width="5" height="3" rx="1" fill="#333"/>
<rect x="14" y="35" width="5" height="3" rx="1" fill="#333"/>
</svg>
<figcaption style="font-size: 11px; color: #888;">Dick Dastardly</figcaption>
</figure>

<figure style="text-align: center; margin: 0;">
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 40" width="60" height="100">
<ellipse cx="12" cy="6" rx="7" ry="7" fill="#C4944A"/>
<path d="M5 9 Q5 4 12 4 Q19 4 19 9 L18 14 Q12 16 6 14 Z" fill="#FDDCB5"/>
<ellipse cx="9" cy="8" rx="2" ry="2" fill="white"/>
<ellipse cx="15" cy="8" rx="2" ry="2" fill="white"/>
<circle cx="9.5" cy="8.3" r="1" fill="#4169E1"/>
<circle cx="15.5" cy="8.3" r="1" fill="#4169E1"/>
<path d="M9 12 Q12 14.5 15 12" stroke="#CC5555" stroke-width="0.7" fill="none"/>
<path d="M6 16 L4 35 L20 35 L18 16 Z" fill="#4169E1"/>
<rect x="11" y="16" width="2" height="19" fill="#FFD700"/>
<path d="M7 14 Q12 17 17 14" stroke="white" stroke-width="2" fill="none"/>
<path d="M17 14 Q20 18 18 22" stroke="white" stroke-width="1.5" fill="none"/>
<path d="M6 18 Q2 23 3 28" stroke="#4169E1" stroke-width="3" fill="none" stroke-linecap="round"/>
<path d="M18 18 Q22 23 21 28" stroke="#4169E1" stroke-width="3" fill="none" stroke-linecap="round"/>
<circle cx="3" cy="28" r="1.5" fill="#FDDCB5"/>
<circle cx="21" cy="28" r="1.5" fill="#FDDCB5"/>
<rect x="5" y="35" width="5" height="3" rx="1" fill="#333"/>
<rect x="14" y="35" width="5" height="3" rx="1" fill="#333"/>
</svg>
<figcaption style="font-size: 11px; color: #888;">Peter Perfect</figcaption>
</figure>

</div>

Characters slide smoothly between rooms using CSS transitions on SVG transforms. When multiple characters are in the same room, their x positions are nudged apart so they don't overlap; name labels stack vertically if still too close. A legend below the floor line shows each character's illustration next to their full name.

Below the mansion: three room chat columns showing character dialogue with colour-coded bubbles (pink for Penelope, green for Sneekly, brown for the Mob, purple for Dastardly, blue for Peter). A narrator panel on the right shows narrator commentary in gold serif font and villain asides in red monospace.

It works. Characters appear in the entrance hall, dialogue streams in, and when they move rooms the SVG figures slide across. The experience is rough — characters don't move much yet, the narrator only outputs canned text, and there's no NPC interaction — but the plumbing is end-to-end functional.

## The Design Shift

Here's what the POC actually surfaced: the original spec over-scripted the plot.

Triggers fire deterministically — when the Hooded Claw enters the kitchen, poison is revealed. When he has poison and Penelope is in the ballroom, the tea scene starts. The characters don't *decide* to do these things; the engine detects the conditions and forces the sequence. The characters are improvising dialogue inside a predetermined structure. That's actors performing a script, not autonomous agents.

The POC was supposed to demonstrate autonomous multi-agent behaviour. But if you remove the triggers and scenes, the characters just stand in the entrance hall chatting. The Hooded Claw doesn't explore the kitchen because nothing in his observation tells him he should. He doesn't know there might be weapons in other rooms. His goal says "eliminate Penelope" but his observation doesn't connect that goal to the act of exploring.

The fix isn't more scripting — it's the RPG framing. Player characters (Penelope, Hooded Claw, Peter Perfect) act autonomously based on goals surfaced in their observation. NPC characters (Dastardly, Slag Brothers, Lazy Luke) provide scripted quests and triggers. The Hooded Claw should discover poison because his observation says "Your objective: eliminate Penelope. You haven't explored the Kitchen or Ballroom yet. Your capability: discover hidden dangerous items." The trigger becomes a guardrail ("if HC has been in the kitchen 5 turns and hasn't noticed the poison, the narrator drops a hint") rather than the primary driver.

This is Phase 2.5 — same 3 rooms, same 5 characters, but remove the scripted triggers and see if the characters produce the same outcome autonomously. If the Hooded Claw ignores the poison, that's a descriptor and observation design problem to fix before adding more rooms. Phase 3 (6 rooms, NPCs, full 3-act plot, memory, trust, human-in-the-loop) only makes sense after Phase 2.5 validates.

The engine infrastructure supports both modes — the triggers and scenes still exist for NPC-driven quest lines. The question is whether player characters can drive the plot on their own. The Hooded Claw is already asking what devices are in the room. He's ready. The observation just needs to tell him where to look.
