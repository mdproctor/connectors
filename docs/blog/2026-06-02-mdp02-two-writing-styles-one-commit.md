---
layout: post
title: "Two Writing Styles, one commit — and a spec that forgot to check the skill"
date: 2026-06-02
type: phase-update
entry_type: note
subtype: diary
projects: [casehub]
---

The issue description was confident: parent#144, duplicate `## Writing Style` in `arc42stories-spec.md`, introduced by the PR#138 merge, both sections identical. Fix: delete the first.

We checked the diff before touching anything.

They weren't identical. The first occurrence had twenty lines of detailed structural rules for the `#### What it adds` section — the Before:/After: format, the "Not closed here" requirement, a hard length cap on prose. The second, appended at the file's end, had eleven mode-map rows instead of fourteen. No prescription, no §13 Glossary.

The issue was also wrong about which to delete. We read three mature ARC42STORIES.MD documents — AML, clinical, devtown — and all of them follow the Before:/After: format from the first occurrence's prescription. It's not aspirational; it's the template in use. The first occurrence was canonical. The second was spurious.

To find the root cause, we read `git show 94f0fa0`. PR#138 hadn't produced a merge conflict. The same session had added Writing Style content to two positions — once where old content was replaced, once at the file's end. Two additions that should have been one.

One other thing surfaced during the reconciliation. The spec listed all the `modes/` files as Generator pre-conditions but omitted `modes/_universal.md`. The write-content skill's own SKILL.md says "Always load `modes/_universal.md` first" (Step 4). The spec's "without the skill" path had drifted from the skill it was documenting.

The more complete mode map lived in `write-content/forms/technical-documentation.md` — the file the skill actually loads at runtime. When the spec's mode map and the skill file diverged (the §9.3 label was wrong, the §9.4 detail was missing), the skill file was right. The spec summarises for humans reading without the skill; it shouldn't be less accurate than what it summarises.

The doc sync work after that was routine. The qhorus deep-dive got a new Channel Read-model Projection section for `ChannelProjection<S>`, `MessageView`, and the rest from qhorus#230/#231. PLATFORM.md got the capability row. The eidos deep-dive got its eval module, the `delegation` field fix — the field had been renamed from `canDelegate` and the doc hadn't caught up — and the eidos#23 status. One catch: the qhorus Module Structure table was also stale. The issue hadn't called it out, but the `api` module row had no mention of the new types. We updated it.

The session produced one protocol: PP-20260602-e36824, formalising that new API surface goes in the repo deep-dive, not DESIGN.md. Issue #143 had referenced it as an existing convention but it wasn't written down anywhere. It is now.
