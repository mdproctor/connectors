---
layout: post
title: "The Epic That Was Already Done"
date: 2026-08-18
entry_type: note
subtype: diary
projects: [casehub-work]
tags: [issue-triage, cdi, multi-tenancy, casehub-work]
---

The casehub-work repo had three open epics. Two of them — #79 (External System Integrations) and #92 (Distributed WorkItems) — were marked as blocked on CaseHub and Qhorus not having stable APIs. The blocker sounded reasonable. Nobody had checked whether it was still true.

It wasn't. Both integration modules — `casehub-work-engine-adapter` for the CaseHub engine bridge and `casehub-work-qhorus` for the Qhorus agent mesh — were already shipped. Full production code, tests, documented in CLAUDE.md. The epic's scope section just hadn't been updated when the work landed.

I verified both via IntelliJ. `WorkQhorusMcpTools` has three MCP tools (`requestHumanWork`, `checkWorkStatus`, `waitForWork`), a lifecycle adapter posting terminal events back to Qhorus channels, and test coverage. The engine adapter has a two-way bridge with `HumanTaskScheduleHandler` inbound and `WorkItemLifecycleAdapter` translating WorkItem events to PlanItem transitions. None of this was speculative — it was deployed and working.

That made #79 closeable. Four of five scope items done, the fifth (#39, ProvenanceLink) deliberately deferred until the integrations get production mileage. And with #79 closed, the "blocked on CaseHub/Qhorus" blocker on #92 evaporated too. Its remaining two issues — cross-service federation and coordinated rollback — turned out to be unblocked. I spun up slot 130 for those.

The session's actual fix was smaller but cut from the same cloth. `TenantHolder` in work-runtime initialised its `tenancyId` to a hardcoded UUID, ignoring the `casehub.tenancy.default-id` config property that platform's `MockCurrentPrincipal` reads. Because `TenantScopedPrincipal` — a plain `@RequestScoped` bean — beats `MockCurrentPrincipal` (`@DefaultBean`) in CDI resolution, any app with casehub-work on the classpath silently got the wrong default tenant. The fix was `@ConfigProperty` with `@PostConstruct` — one field, one annotation, one init method.

The other bug on the branch, #352 (assignment strategy persistence after the WorkItem record refactor), turned out to be already fixed. The strategies use `instances.set(i, ...)` with immutable records, and `MultiInstanceSpawnService` calls `workItemStore.put()` after `strategy.assign()`. The issue was filed when the refactor landed but the fix was applied before the issue was closed.

Eighteen files on main also had unresolved merge conflict markers — all the same `WorkItemLifecycleEvent` import that moved from `runtime.event` to `api`. The repo compiled around them because the conflicts were in import blocks, but any `@QuarkusTest` would have failed with CDI deployment errors. That kind of silent corruption is the worst kind — it doesn't announce itself until you try to test something unrelated.

The recurring theme: stale metadata. Epics marked blocked that aren't. Issues filed for bugs already fixed. Merge conflicts sitting on main unnoticed. The code was fine. The records about the code were wrong.
