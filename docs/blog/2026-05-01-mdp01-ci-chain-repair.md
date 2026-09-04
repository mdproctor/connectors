---
layout: post
title: "CI Chain Repair"
date: 2026-05-01
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-parent]
tags: [ci-cd, github-actions, cross-repo, debugging]
excerpt: "The cross-repo dispatch never fired because GITHUB_TOKEN is scoped to the repo where the workflow runs — six repos needed a PAT, and the CI UI was showing success for failed steps because continue-on-error hides step outcomes at the job level."
---

The CaseHub build chain has been broken since the repo renames. Not broken in an obvious way — each repo's CI was green on its own, but the cross-repo dispatch never fired. Work, qhorus, and claudony were red because ledger and connectors artifacts weren't being published when upstream changed.

The root cause was embarrassingly simple. Every publish.yml file used `GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}` for the cross-repo `repository_dispatch` call. That token is scoped to the repo where the workflow runs. GitHub returns 403 — "Resource not accessible by integration" — when you use it against another repo's API. The fix is a classic PAT stored as `GH_PAT`. One line across six repos.

This should have been caught immediately. It wasn't, partly because `gh run view` shows `conclusion: success` even for failed steps when `continue-on-error: true` is set. The actual result is in `steps.<id>.outcome`, which the API doesn't surface at the job level. The build summary was catching it; the UI wasn't showing it.

Fixing the dispatch involved pushing the workflow change to ledger, connectors, work, qhorus, and engine in one operation. That was a mistake. Each of those repos has its own Claude session. Claude committed the change and swept up 15 staged-but-uncommitted files from another session's in-progress feature work, all under a CI commit message. `git commit` without `-a` commits everything in the staging area, not just what you added. The shared index doesn't care which session staged what.

We've since established a rule: this Claude doesn't commit to peer repos. Cross-repo fixes go in as GitHub issues; the respective Claude picks them up.

With dispatch working, the cascade revealed three more failures.

Qhorus couldn't compile — `LedgerWriteService` imported `CapabilityTag` from ledger, but ledger's CI hadn't finished publishing the artifact containing it. Qhorus had triggered off an old dispatch before the commit landed. It resolved once ledger's CI completed and re-dispatched.

Work's `BusinessHoursIntegrationTest` fails on Friday evenings. The test asserts "2 business hours ≤ 1 calendar day" — true Monday through Thursday. After hours on a Friday, the next two business hours are Monday morning, three calendar days away. `isBefore(Instant.now().plus(1, ChronoUnit.DAYS))` fails every time CI runs late on a Friday. The fix is a 3-day bound with a 5-minute buffer, the same pattern already applied to the `expiresAt` variant in the same class.

Claudony is failing to compile because engine PR #224 merged `WorkerContextProvider.buildContext()` with a new `UUID caseId` parameter. Claudony's `ClaudonyWorkerContextProvider` still implements the old two-argument version.

Two housekeeping items also landed. The full-stack build now excludes quarkus-langchain4j by default — it's a slow fork that rarely changes, and an `include_langchain4j` checkbox costs nothing. The parent POM now configures Surefire with `rerunFailingTestsCount=2`, which retries each failing test twice. Flaky tests surface as flaky; consistently broken ones still fail.

Ledger and connectors are green. Work and claudony need their Claudes to apply the fixes above.
