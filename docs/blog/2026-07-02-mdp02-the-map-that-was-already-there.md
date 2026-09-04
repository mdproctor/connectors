---
layout: post
title: "The Map That Was Already There"
date: 2026-07-02
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-work]
tags: [normative, speech-acts, qhorus, alignment, deontic]
---

The Qhorus normative layer has had a clear speech act taxonomy for months — nine types, seven commitment states, a sealed CommitmentStore. casehub-work has had a complete WorkItem lifecycle for nearly as long — twelve statuses, twenty-six event types, the delegation chain, the SLA breach policy framework. What it didn't have was an explicit document mapping one to the other.

Issue #159 was filed before FAULTED, OBSOLETE, and progress tracking existed. Back then the mapping table in the issue body had ten statuses and no MANUALLY_ESCALATED. The lifecycle alignment work (#240) added the missing states and brought the WorkItem model into alignment with WS-HumanTask 1.1. But the *normative* alignment — how each WorkItem concept maps to Qhorus speech acts and deontic commitment states — remained undocumented.

The mapping itself turned out to be clean. Most of it writes itself: COMPLETED maps to FULFILLED, CREATED maps to COMMAND, EXPIRED maps to EXPIRED. The interesting cases are where the mapping breaks down deliberately. SUSPENDED has no commitment state equivalent — and correctly so. Machines don't pause; they execute or they stop. If a machine can't proceed, it emits DECLINE with a reason and the coordinator re-issues. Humans hold obligations discontinuously — that's the whole point of the human-agent layer.

DELEGATED is the dangerous one. It means three incompatible things across three systems: pre-acceptance hold in casehub-work (non-terminal), obligation transferred in Qhorus (terminal), control passed to external actor in casehub-engine (non-terminal). The javadoc cross-references are already in place, but the integration warning needed its own section: when the casehub-work-qhorus bridge module is built, a WorkItemStatus.DELEGATED event must *not* produce a CommitmentState.DELEGATED transition. Getting that wrong would silently discharge obligations that are still being held.

The other classification that emerged cleanly was the four-way split on event types: sixteen events are normative (they map to a speech act), four are operational (internal routing machinery — ASSIGNED, RELEASED, LABEL_ADDED, LABEL_REMOVED), three are human-layer extensions (SUSPENDED, RESUMED, OBSOLETE), and three are infrastructure-generated (EXPIRED, CLAIM_EXPIRED, ESCALATED). That distinction matters for the bridge — operational events don't translate to channel messages; infrastructure events might generate synthetic FAILURE messages or telemetry depending on the oversight channel's subscription policy.

NormativeResolution already existed — DONE, DECLINE, FAILURE mapping to COMPLETED, CANCELLED, REJECTED — but it was documented only in the issue-tracker context. The new doc (`docs/NORMATIVE-ALIGNMENT.md`) gives it a normative home alongside the status and event tables, the DELEGATED conflict, and a forward-looking section on what the bridge module will need to handle.

The Qhorus docs have their own version of the mapping — `work-and-workitems.md` — but its table predates FAULTED and OBSOLETE. A cross-repo briefing documents what needs updating there.
