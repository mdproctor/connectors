---
layout: post
title: "Shipping the AI Fusion landing page"
date: 2026-05-23
type: phase-update
entry_type: note
subtype: diary
projects: [CaseHub Parent]
---

I wanted something on casehubio.github.io that said what CaseHub actually is.
The GitHub org page lists repos and their descriptions — useful for people who
already know the project, useless for anyone arriving cold. The platform has a
specific story: it fuses two kinds of AI, and that fusion is the whole point.
Nothing in the repo names said that.

I brought Claude in to build it. We produced a single `index.html` — no Jekyll,
no build step, dark teal theme borrowed from the poc site. It's live now at
[casehubio.github.io](https://casehubio.github.io).

## Naming the Runtime tier

The architecture diagram needed four horizontal bands. Foundation, Orchestration,
and Applications were obvious. The fourth — Claudony, which runs remote Claude
sessions and surfaces the browser dashboard — was harder.

"Integration" was in the internal docs, borrowed from standard four-tier
taxonomy. But it carries ESB baggage. "Control Plane" felt accurate until I
thought about what Claudony actually is: the place where a developer goes to do
all their coding, manage sessions, and observe the ecosystem. "Developer Workspace"
was closer but still too narrow — implying a scratch space rather than a fully
operational environment.

We settled on **Runtime**. Honest, doesn't overclaim, lets the description carry
the specifics.

## No CMMN on the website

I'd initially used "CMMN Semantics" as a tag in the AI Fusion section and
"Blackboard · CMMN" in the SVG annotation. The term lives throughout the internal
design docs — it's precise shorthand for a process model that genuinely informed
the architecture.

For external content it's the wrong word. CMMN implies specification conformance.
CaseHub takes what's useful from the CMMN mental model and stops there — it
doesn't implement the spec and doesn't claim to. Writing "CMMN Semantics" on the
landing page sets up a comparison we can't win.

**Modernised Blackboard Architecture** is what we actually built. That's what's
on the page now, and we captured it as a standing protocol so it doesn't drift
back in.

## The architecture diagram

Four bands stacked bottom-to-top: Foundation at the bottom (teal accent border),
Orchestration, Runtime, Applications at the top. Two bracket annotations on the
right mark where Classical AI and LLM AI are served — Classical AI spanning
Foundation and Orchestration, LLM AI spanning Runtime and Foundation. The
Foundation band is where both overlap: the AI Fusion Core.

One small choice: upward-pointing arrows between bands rather than downward.
The arrows show service provision moving up — Foundation serves the layers above
— rather than dependency pointing down. The upward read felt more natural for a
platform page that wants to say "this is what we built for you."

## One file, Jekyll paths

The site is a single HTML file. I didn't add Jekyll — there's no blog, no docs
section, nothing to template yet. Paying the build-system tax for a page with one
button is the wrong trade.

The file paths match what Jekyll expects: `assets/css/main.css` is the default
asset path, `index.html` at repo root is where Jekyll looks for the landing page.
Migration is adding `_config.yml` and extracting the layout. When there's content
to publish, the scaffolding is already there.
