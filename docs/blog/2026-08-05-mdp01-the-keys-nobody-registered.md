---
layout: post
title: "The Keys Nobody Registered"
date: 2026-08-05
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-work]
tags: [preferences, multi-module, audit]
---

Every multi-module system has the same failure mode: you define a thing in one place, register it in another, and nobody enforces the 1:1 correspondence. The gap is invisible until someone asks "why can't the UI editor see this preference?"

CaseHub Work has a preference system — `PreferenceKey<T>` instances that define tenant-scoped, runtime-configurable settings. SLA default hours. Delegation decline target. Queue snapshot intervals. The platform provides a `PreferenceSchemaRegistry` where modules register these keys at startup, and a UI editor that discovers what's registered and renders input widgets for each one. The contract is simple: define a key, register its schema, the UI picks it up.

The problem is that "define" and "register" are separate acts, separated by package boundaries and Maven dependency direction. Nobody enforces that they happen together.

I ran a first-principles audit: instead of reading the registrar to see what was registered, I searched every `.java` file in the project for `PreferenceKey<` references. Five hits. Three were in the runtime module's `WorkPreferenceRegistrar` — decline target, default expiry hours, default claim hours. All registered, all visible to the UI. Two more were in the queues module — `QueueSnapshotInterval.KEY` and `QueueTrendRetention.KEY`. Both defined as proper `PreferenceKey<DurationPreference>` instances, both consumed by queue services at runtime. Neither registered with the schema registry. The UI had no idea they existed.

The fix seems obvious: add two `registry.register()` calls to `WorkPreferenceRegistrar`. But the module dependency graph says otherwise. The runtime module doesn't depend on the queues module — and it can't, because queues depends on runtime. Adding the dependency would create a cycle. The existing registrar can't see the queue keys.

The answer follows the module boundary: each module registers its own preferences. `QueuePreferenceRegistrar` in the queues module, same `@ApplicationScoped` / `@Observes StartupEvent` pattern. Two registrations, four tests, done. The queue preferences — snapshot interval (default PT1H) and trend retention (default PT168H, which is seven days in Java's normalised ISO-8601 form — `Duration.ofDays(7).toString()` returns hours, not days) — are now discoverable by the preference editor.

The issue description also mentioned "capability validation" as a third preference area. It isn't one. `casehub.work.capability-validation` is a `@ConfigProperty` — a static deployment-time setting (STRICT / WARN / PERMISSIVE), not a tenant-scoped runtime preference. The distinction matters: `@ConfigProperty` is baked into the deployment; `PreferenceKey` is configurable per scope at runtime via `PreferenceProvider`. Conflating them would mean tenants could accidentally disable capability validation on a per-scope basis, which is a deployment concern, not a tenant concern.

The broader pattern here is worth watching. As CaseHub Work grows modules — queues, AI, reports, progress — each module will define its own preference keys. The runtime module's `WorkPreferenceRegistrar` can only see what it can import. Without per-module registrars, every new module either creates a circular dependency or its preferences stay invisible. The per-module pattern scales cleanly, but there's no compile-time check that a new `PreferenceKey` gets a corresponding registrar. The gap between "defined" and "registered" doesn't close; it just gets distributed across more modules.
