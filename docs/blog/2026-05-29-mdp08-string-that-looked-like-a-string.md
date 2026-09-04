---
layout: post
title: "The string that looked like a string"
date: 2026-05-29
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-work]
tags: [capability-registry, value-type, quarkus]
---

The problem with `"legal_review"` is that it looks exactly like `"legal-review"`. One underscore. WorkBroker does exact string matching — if the engine puts `"legal-review"` in a case definition and the worker registers `"legal_review"`, nothing gets assigned, and the error says "no worker available for capability: legal-review" with no indication of why.

I wanted to fix this at the type level, not the validation level. The alternative — keep `String` everywhere and add a `CapabilityValidator` to check things at the write boundary — would have left every new consumer of capability data facing the same format risk. The next thing that reads from `WorkerCandidate.capabilities` would need its own guards. The type approach means you get it right once or you don't compile.

So `Capability` is a record with a compact constructor that validates `[a-z][a-z0-9]*(-[a-z0-9]+)*`. It throws on construction. `"legal-review"` works. `"legal_review"` throws before it reaches any routing logic.

The harder question was where the enforcement policy lives. My first instinct was to put a `validationMode()` method on `CapabilityRegistry` — each registry could declare whether it wanted STRICT, WARN, or PERMISSIVE. I talked myself out of it: vocabulary and policy are different concerns. A team that's happy with the built-in `WorkCapabilitiesRegistry` shouldn't have to subclass it just to flip a switch. The mode is a deployment decision, so it lives in config: `casehub.work.capability-validation=STRICT`.

We ran into a migration problem straight away. Existing DB rows have capability strings that predate the format constraint — some almost certainly have underscores or mixed case. If `CapabilityParser.parse()` is called when building `SelectionContext` for routing, those existing items break immediately on deployment. The answer is two parse modes: `parse()` is strict (throws) for new user input; `parseLenient()` skips bad tokens and logs WARN for existing DB rows. The write path enforces the contract. The read path is lenient until operators clean the data.

Integration testing found three things that weren't obvious from the design:

First, `WorkItemResource.create()` had a generic `catch (IllegalArgumentException e)` handler. `MalformedCapabilityException extends IllegalArgumentException`. The JAX-RS `@Provider` mapper for `MalformedCapabilityException` never fired — the generic catch got there first and produced a plain-text error instead of the structured JSON response. The fix is a specific `catch (MalformedCapabilityException | UnknownCapabilityException e) { throw e; }` placed before the generic handler. Claude flagged this while verifying the 400 response body shape.

Second, `WorkItemsConfig` uses `@ConfigMapping(prefix = "casehub.work")`. SmallRye's strict mapping validation claims ownership of that entire prefix — any `casehub.work.*` key not declared in the interface gets rejected at startup, including keys used by other CDI beans via `@ConfigProperty`. Setting `casehub.work.capability-validation` via a test profile triggered this. The fix is adding `capabilityValidation()` with `@WithDefault("PERMISSIVE")` to the config interface.

Third, `CapabilityParser` started as a package-private class, which broke `RoundRobinAssignmentStrategy` in a different package that also builds `SelectionContext`. We promoted it to `public`.

The `CapabilityRegistry` SPI has a permissive default (`@DefaultBean PermissiveCapabilityRegistry`) that returns an empty vocabulary set and never validates anything. Existing deployments see no behaviour change. Operators who want governance deploy their own registry as `@Alternative @Priority(1)` and set the validation mode in config.
