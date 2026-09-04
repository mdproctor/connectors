---
layout: post
title: "Keeping the docs honest"
date: 2026-05-28
type: phase-update
entry_type: note
subtype: diary
projects: [CaseHub Parent]
---

## Keeping the docs honest

Eight open documentation issues, all in the parent repo, none requiring changes elsewhere. The kind of session that sounds boring and is actually necessary.

The first job was figuring out which of the open issues were actionable here. A cluster of them touched peer repos — workspace conventions to clean up, signal bridge implementation work still in flight, connector rewiring. Those go elsewhere. What remained was a clean set of doc syncs: deep-dive files that hadn't caught up with what had been built.

The pattern is familiar. Implementation lands in the child repos — engine#349 closes, openclaw's Epic 3 ships, quarkmind's LAYER-LOG entries get written. The platform documentation in the parent repo captures those decisions, but it doesn't update itself. Without a deliberate effort to sync it, the docs drift. A new session reads the docs and works from a picture that no longer exists.

The changes were small but specific. The ChannelContextWindow ring buffer in casehub-openclaw was documented as "persisted for durability" when it's in-memory only. The ClaudonyChannelBackend was listed as fanning out over WebSocket when it uses SSE. The casehub-engine deep-dive still showed WorkBroker and WorkerSelectionStrategy as the routing mechanism, a branch or two after AgentRoutingStrategy replaced them. None are subtle — they're just wrong, and they'll mislead anyone reading them.

I brought Claude in to work through them in sequence. We made one commit per issue, each referencing the original issue number. That's more discipline than strictly necessary for doc-only changes, but it keeps the git history navigable and closes the issues properly.

The only interesting moment came at merge time. The GitHub PR showed as CONFLICTING despite a clean diff — six doc files, no overlapping changes. We waited, tried rebasing, eventually concluded GitHub hadn't finished computing its merge test. Merged locally; the DIRTY state was a transient artefact.

The pre-push squash hook fired on eight commits and we reviewed them. None were candidates — each had a distinct issue reference and touched a different file. The point of the review is to look before it ships, even when you're confident it's clean.
