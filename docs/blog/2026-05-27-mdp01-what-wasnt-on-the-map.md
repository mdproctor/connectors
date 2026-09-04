---
layout: post
title: "What Wasn't on the Map"
date: 2026-05-27
type: phase-update
entry_type: note
subtype: diary
projects: [CaseHub]
---

I asked about quarkmind issues. The response: "what's quarkmind?" That was the tell. The repo existed locally, built in the incremental CI, had its own CLAUDE.md. Entirely invisible to the peer repo list, the full-stack build, and the platform documentation.

That's the failure mode: a repo can be building and shipping while being blind to any session-level reasoning about the platform.

We audited five registries against sixteen repos: CLAUDE.md peer list, both CI build workflows, PLATFORM.md's repository map, and APPLICATIONS.md or AGENTIC-HARNESS-GUIDE where applicable. Seven repos had gaps: platform, eidos, openclaw, drafthouse, life, quarkmind, and flow. Most were absent from the peer list and documentation — repos added before the registration discipline was in place. quarkmind was genuinely absent from `full-stack-build.yml`, meaning it would silently skip in the aggregated build.

`flow` was the interesting case. It's a standalone Quarkus app wrapping the engine with REST endpoints, built outside the main platform development thread. Its CLAUDE.md already had the platform awareness boilerplate, which was a surprise. Its tier is genuinely unclear — not a domain application, more of an integration reference or deployment target. We registered it with a "tier TBD" note in PLATFORM.md and the CI, and opened a PR to its repo for the missing sections: repository role, build commands, work tracking, development workflow. GitHub Issues are disabled on that repo, so the commit carries no issue reference — a small footprint of the integration debt remaining.

There was a CI bug alongside all of this. The build summary table had `casehubio/` hardcoded in the URL template, with bare repo names in the GH_REPO map. Fine while everything lived in the same org — quarkmind is under `mdproctor`. We switched the map to full `org/repo` paths and dropped the prefix.

One line in AGENTIC-HARNESS-GUIDE was missing casehub-drafthouse from its scope list. Small, but an agentic session opening in that repo would have had the wrong context from the start.

The history going into the push had a characteristic accumulation: a protocol added and immediately reverted, netting to zero. 11 commits into 6 after the squash.
