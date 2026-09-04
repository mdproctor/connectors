---
layout: post
title: "Build Drift"
date: 2026-05-24
type: phase-update
entry_type: note
subtype: diary
projects: [CaseHub Parent]
tags: [github-actions, incremental-build, ci]
---

The ecosystem had drifted. Build scripts, workflows, and the website dashboard were all tracking a different set of repos — some stale, some just missing.

I brought Claude in to audit everything: `build-all.sh`, `aggregator.xml`, five GitHub Actions workflows, and `docs/index.html`. The scope of drift was worse than expected.

## The stale dashboard

`docs/index.html` was the most visible problem. The JavaScript REPOS array still listed `quarkus-ledger`, `quarkus-work`, `quarkus-qhorus`, and `casehub-parent` — old names from a previous naming scheme the repos had long since dropped. The actual repos weren't in there. Neither was `platform`, `eidos`, or any of the application repos.

We replaced the array with the correct names, added an Applications section for devtown, aml, clinical, and quarkmind, and split the fetch into platform and app lists so the two concerns stay logically separate.

## The silent rebuild

`incremental-full-stack-build.yml` persists each module's current SHA to a cache file after each run. The next run loads those SHAs as "previous" and builds only what changed.

`platform` and `eidos` were both in the build — cloned, compiled, tested — but neither was ever written to the state file. So every run treated both as first-time: SHA comparison against `none`, decision always `BUILD`. They were rebuilding unconditionally on every CI run with no error, no warning, nothing to look at. The state file just didn't have their names.

This kind of thing compounds quietly. A module stays in unconditional-rebuild forever unless you actively inspect the state file between runs and notice a name is absent.

## The broken summary link

In `full-stack-build.yml`, the build summary table links each module to its GitHub repo. The logic iterates over a `MODULES` array and looks up each name in a separate `GH_REPO` associative map. `eidos` was in `MODULES` and `OUTCOMES` — it built, its result appeared in the table — but nobody had ever added `[eidos]="eidos"` to `GH_REPO`. Every summary generated `https://github.com/casehubio/` with an empty repo slug.

Bash associative arrays return empty string for missing keys. `set -euo pipefail` doesn't catch it — array subscript access isn't a bare variable reference, so `nounset` doesn't fire. You only notice when you look at the rendered output.

## The cross-org pattern

`quarkmind` lives at `mdproctor/quarkmind`, not `casehubio/quarkmind`. Adding it to the dashboard workflows meant every REPOS list and API call that assumed a single org had to change. We switched all of them to `org/repo` format — `casehubio/ledger`, `mdproctor/quarkmind` — so the org is always explicit and mixing orgs in a list is structurally possible.

`build-all.sh` and `aggregator.xml` got `platform` and `eidos` added while we were in there, plus an opt-in flag for the application repos.
