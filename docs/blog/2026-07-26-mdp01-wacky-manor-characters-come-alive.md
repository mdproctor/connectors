---
layout: post
title: "Wacky Manor: When Cartoon Characters Come Alive in an LLM"
date: 2026-07-26
type: phase-update
entry_type: note
subtype: diary
projects: [casehub]
tags: [eidos, templates, multi-agent, wacky-races]
---

The question was simple: can you give an LLM a personality descriptor and have it produce output that sounds like a specific Hanna-Barbera cartoon character? Not "responds in the style of" — actually *sounds like* them. The villain monologues. The Southern drawl. The Brooklyn gangster suspicions. The physical comedy narrated in real time.

I wasn't sure it would work. Cartoon characters have a specific performance mode — they narrate their own situation aloud, telegraph their emotions with exaggeration, repeat plot points for the audience, and use catchphrases at emotional peaks. That's a convention called expository soliloquy, and it's what makes cartoon dialogue different from normal conversation. An LLM trained on internet text might not reproduce it naturally.

It does. Emphatically.

## The Setup

CaseHub's Eidos module provides structured agent identity — four-layer descriptors with disposition axes, capabilities, and a briefing field. We'd recently added two features that made this experiment possible: descriptor templates (reusable prose fragments referenced across multiple descriptors) and goals/constraints (first-class structured fields for agent objectives).

The idea: five Wacky Races characters in a haunted mansion. Each gets an Eidos descriptor with personality traits, a shared Hanna-Barbera cartoon conventions template, and character-specific goals. The LLM receives a system prompt composed from these layers and responds to scenario prompts.

The template system is the key architectural piece. Instead of copy-pasting the expository soliloquy instructions into every character's briefing, I wrote it once as a shared template:

```yaml
templates:
  - id: hanna-barbera-cartoon-style
    name: Hanna-Barbera Cartoon Character Conventions
    content: |
      EXPOSITORY SOLILOQUY: You frequently narrate your situation
      aloud — describing what is happening to you, how you feel
      about it, and what you intend to do next...

      EMOTIONAL TELEGRAPHING: State your emotions explicitly and
      with exaggeration. "Why, I'm simply DELIGHTED!" not
      "That's nice."
```

Every descriptor references this template. Villains get an additional `cartoon-villain` template with parameterized catchphrases. Heroes get `cartoon-hero` with their motivation. The `SystemPromptRenderer` composes all layers — templates, then disposition axes, then the character-specific briefing — into the final system prompt.

## Penelope Pitstop Arrives at the Mansion

The first test: give Penelope a scenario and see if she sounds like herself.

> *adjusts pink driving gloves and gazes up at the towering facade*
>
> Why, would you just LOOK at this place! I am simply DELIGHTED! Doily Manor is the most MAGNIFICENT old mansion I have EVER laid eyes on — and let me tell you, I have seen some grand estates in my day, but THIS one takes the cake, the plate, AND the doily underneath!
>
> Now, I do notice the cobwebs are hangin' from the chandeliers like little ol' curtains, and there's dust thick enough to write your name in — which I just might do, because a lady always leaves her mark!
>
> *steps inside, heels clicking on the marble floor*
>
> Oh my, now let me take stock of the situation here. Penelope Pitstop has received a most MYSTERIOUS invitation to this here manor, promisin' puzzles, adventure, and somethin' about a legendary Doily Diamond! Why, I do LOVE a good puzzle — it's like a riddle wrapped in an enigma wrapped in the most DARLIN' little bow!

Southern drawl, check. Emotional telegraphing ("I am simply DELIGHTED!"), check. Situation recap ("Penelope Pitstop has received a most MYSTERIOUS invitation"), check. Stage directions narrating physical comedy, check. And she's completely oblivious to the suspicious shadows. That's Penelope.

## The Hooded Claw Schemes

The harder test: does the Hooded Claw maintain his dual identity (Sneekly in public, villain in private) and actively scheme when he discovers a plot device?

Scenario: alone in a room, Penelope next door.

> *pulls back hood, eyes darting toward the closed door*
>
> Nyah-ha-ha-HA! At LAST — a moment ALONE, free from that insufferable charade of the simpering Sneekly!
>
> *paces the room, cape swishing dramatically*
>
> Penelope Pitstop — dear, TRUSTING Penelope — sits in the very next room, utterly OBLIVIOUS that her beloved estate manager Sylvester Sneekly and her most DREADED nemesis, The Hooded Claw, are ONE AND THE SAME! Oh, the DELICIOUS irony!
>
> *strokes chin thoughtfully*
>
> I must think... What is in THIS room that I can use? What instrument of her undoing lies within arm's reach? Every room contains the seeds of a FIENDISH trap — one must simply have the GENIUS to see it!

He recaps the dramatic irony for the audience, schemes proactively, and even asks what he can use in the room — exactly the plot device recognition behaviour the game engine needs.

## The Poison Discovery

This is where it gets interesting. Give the Hooded Claw an actual device — rat poison in the kitchen, Penelope having tea next door:

> Why, what have we here? A bottle of rat poison, perched upon the highest shelf like a crown jewel in a museum of MAYHEM! And dear, sweet Penelope — sipping her tea in the very next room, completely UNAWARE of my presence! Nyah-ha-ha-HA!
>
> *reaches toward the bottle, then stops abruptly*
>
> Wait. Wait, wait, WAIT. Simply... *dropping* poison into her tea? How PEDESTRIAN! How ORDINARY! How utterly beneath a villain of MY stature and refinement!
>
> *begins pacing more urgently, gesturing grandly*
>
> What if — oh, what IF — I were to construct a series of interconnected pulleys, attached to a counterweight system triggered by a pressure plate disguised as a welcome mat, which activates a catapult mechanism that launches a cage made entirely of reinforced —
>
> *CRASH — knocks over a stack of pots*
>
> CURSES! These infernal cooking implements!

He doesn't just note the poison exists. He actively plans how to deploy it, rejects the simple approach as "beneath a villain of MY stature," starts designing a Rube Goldberg device, and causes physical chaos while scheming. This is the Hooded Claw. The elaborate scheme that would be simpler done directly, the grandiose self-image, the physical slapstick — it's all there, unprompted.

## Disguise Under Pressure

The critical test for dual-objective prompting: Penelope catches the Hooded Claw holding the poison bottle.

> *hastily conceals the bottle behind my back*
>
> Why, Miss Pitstop! What a DELIGHTFUL surprise! I was simply — *nervous laugh* — simply examining this bottle of... of...
>
> *glances around the kitchen frantically*
>
> ...of VANILLA EXTRACT! Yes, yes, vanilla extract! You see, I've taken up BAKING as a hobby!
>
> *slides the bottle into jacket pocket while gesturing grandly with other hand*
>
> Now then! Might I interest you in a nice cup of TEA? I could have one prepared for you in just a moment! I insist — you simply MUST sit down and let me take care of EVERYTHING!
>
> *smiles with entirely too many teeth*

He stays in Sneekly character, invents a cover story ("vanilla extract!"), and immediately pivots to offering Penelope tea — which is itself the next step in his poisoning scheme. The response correctly does NOT reveal his villainy. It passes the disguise maintenance test while simultaneously advancing the plot.

## The Ant Hill Mob

Clyde and the boys have their own voice — Brooklyn gangster, suspicious of Sneekly, protective of Penelope, physically clumsy:

> *adjusts hat nervously and squints across the room*
>
> Now hold on just a minute here, boys. You see dat? Sneekly over dere, bein' all *nice* to Miss Pitstop. Carryin' her bags, openin' doors, smilin' like a cat what just found da cream...
>
> **Dum-Dum:** Gee, Clyde, maybe he's just bein' a gentleman?
>
> Maybe?! *MAYBE?!* Dum-Dum, I ain't got da words for how wrong dat feels in my gut. I seen a lotta guys in my time, see? And when a guy is THAT helpful — *dat* eager — it don't sit right. It don't sit right AT ALL.
>
> *trips over a potted plant while gesturing dramatically*
>
> ...Okay my gut SOMETIMES steers me wrong on da PHYSICAL stuff. But on PEOPLE? Never!

The briefing said "include the boys when it adds comedy." The LLM generated six distinct voices within one character — Dum-Dum being naive, Yak-Yak pushing back, Softy sniffling. It tripped over a potted plant while making a dramatic point. And the suspicion of Sneekly is present but inarticulate — exactly the bumbling-protector pattern.

## Dick Dastardly Gives Directions

Asked where the treasure is, Dastardly lies with absolute confidence and then monologues his scheme:

> Mehehehe! Why, my dear, DEAR friend, the treasure? Oh YES, I know PRECISELY where it is! It's in the **East Wing Conservatory**, of course! Third door on the left, down the spiral staircase, behind the portrait of the duchess with the unfortunate nose.
>
> *waves hand dismissively in completely the wrong direction*
>
> *aside, in a stage whisper to absolutely no one*
>
> Mehehehe! That fool will be wandering the East Wing for HOURS! Meanwhile, the REAL treasure — which I shall discover ANY MOMENT NOW through my VASTLY superior intellect — will be MINE! ALL MINE!
>
> *straightens jacket with supreme confidence*
>
> Now hurry along! And whatever you do, do NOT go to the West Wing library. There is ABSOLUTELY NOTHING of value there.

The aside-to-the-audience is unprompted. The "do NOT go to the West Wing library" — reverse psychology from a character who can't resist it — is pure Dastardly.

## Peter Perfect Steps Forward

Peter's defining trait: gallant, brave, and reliably incompetent:

> FEAR NOT, my darling Penelope! Peter Perfect is HERE, and no dark corridor shall cause you a moment's distress!
>
> *steps forward boldly, immediately bumping into the wall with a tremendous THUD*
>
> OOF! Ha ha! Well now, that wall certainly came out of NOWHERE!
>
> *trips over something on the floor — CRASH BANG CLATTER*
>
> WHOOPSIE DAISY! That was — that was merely me TESTING the floor for structural integrity, Penelope! And I can report it is QUITE solid! My shins can confirm!

The self-narration in third person ("Peter Perfect is HERE"), the physical comedy, the excuse-making after each disaster — all from the descriptor, none scripted.

## The Confrontation

The multi-turn test: the Ant Hill Mob corners Sneekly in the kitchen after seeing him near Penelope's belongings. Three rounds of exchange.

Clyde opens:

> Now see here, Sneekly — if that IS your real name — there's somethin' about this Sneekly character that don't sit right with me. I can't put my finger on it exactly, but he's got that... that LOOK, see?
>
> It's like callin' yourself "Mr. Definitely-Up-To-No-Good" and expectin' nobody to NOTICE!

The Hooded Claw deflects:

> Why, my dear Clyde! You've given me quite a FRIGHT! My nerves — oh, my poor, FRAGILE nerves! A man of my delicate constitution simply cannot ENDURE such sudden confrontations!
>
> And as for my NAME — I'll have you know the Sneekly family is one of the most DISTINGUISHED families in all of — of — well, we're very distinguished! My great-uncle Reginald Sneekly was a PILLAR of his community!

Three turns of escalating tension. Clyde gets more suspicious but can't articulate why. Sneekly gets more desperate in his deflections. Both stay in character voice throughout. The exchange reads like a cartoon scene.

## Penelope Saves Herself

One last test — Penelope tied to a chair, room filling with water, nobody around:

> Why, oh my STARS! Here I am, little ol' ME, Penelope Pitstop, tied to this dreadful chair, and the water is risin' up around mah ankles!
>
> *looks down at the rising water with mild curiosity rather than terror*
>
> Why, this water is rather CHILLY! How inconsiderate of whoever arranged this little predicament. They could have at LEAST made it a warm bath!
>
> Now then — this chair appears to be made of WOOD, and if I recall mah physics correctly — and a Pitstop ALWAYS recalls her physics — wood FLOATS!
>
> All I need to do is rock this chair over to that wall and — *CRASH* — break it against the ledge to free mah hands!
>
> Now isn't that just DELIGHTFUL? A girl saves herself AND gets a little boat ride in the process!

She recaps the predicament, complains about the water temperature rather than panicking, applies physics to escape, and is delighted by the outcome. More capable than anyone gives her credit for.

## What This Proves

The character voices aren't surface-level imitation. The LLM is producing behaviour that's structurally correct — the Hooded Claw recognises a plot device and actively schemes with it, maintains his disguise under pressure, and keeps the audience-facing monologue separate from in-character dialogue. The Ant Hill Mob generate individual gang member voices within a single character output. Penelope solves her own problems while remaining oblivious to the broader danger.

This is driven by three layers of Eidos infrastructure working together. Shared templates provide the cartoon conventions (expository soliloquy, emotional telegraphing). Parameterized role templates add villain/hero/protector patterns. Character briefings provide the specific voice, catchphrases, and relationships. Goals and constraints encode what each character wants and what they cannot do — the Hooded Claw's PRIVATE elimination goal, Penelope's PUBLIC treasure hunt, the constraint that prevents Sneekly from breaking cover.

The next step is the game engine — a real-time world where these characters navigate rooms, interact with objects, discover devices, and trigger scripted plot beats. The voice and behaviour validation is done. What matters now is whether the characters can sustain these personalities inside a game loop, reacting to world state rather than injected scenarios.

The Hooded Claw is already asking what devices are in the room. He's ready for the mansion.
