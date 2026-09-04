# 0004 — Capability validation mode as deployment config, not SPI method

Date: 2026-05-29
Status: Accepted

## Context and Problem Statement

`CapabilityValidator` needs to know whether to enforce vocabulary governance
(STRICT, WARN, or PERMISSIVE). Two locations were considered: as a method on the
`CapabilityRegistry` SPI, or as a deployment config property.

## Decision Drivers

* Vocabulary (what is known) and enforcement policy (what to do about unknowns) are separate concerns
* Teams should be able to change enforcement mode without implementing or subclassing a registry

## Considered Options

* **Option A** — `validationMode()` as a default method on `CapabilityRegistry`
* **Option B** — `casehub.work.capability-validation` config property read by `CapabilityValidator`

## Decision Outcome

Chosen option: **Option B — config property**, because policy is a deployment concern, not a
vocabulary concern. A team that wants STRICT validation but is happy with the built-in
`WorkCapabilitiesRegistry` should not need to subclass or wrap the registry to flip a policy switch.

### Positive Consequences

* `CapabilityRegistry` SPI is purely about vocabulary — clean single responsibility
* Enforcement mode changeable at deployment time without code changes
* Teams that use `PERMISSIVE` (default) see zero overhead — no registry is called

### Negative Consequences / Tradeoffs

* Per-registry policy (STRICT for legal capabilities, WARN for admin) is not expressible
  without a custom `CapabilityValidator` — deferred to future work if needed

## Pros and Cons of the Options

### Option A — validationMode() on CapabilityRegistry

* ✅ Per-registry policy expressible
* ❌ Conflates vocabulary with enforcement policy in the SPI
* ❌ Changing mode requires implementing or wrapping the registry class

### Option B — config property (chosen)

* ✅ Clean SPI — registry answers "is this known?", not "what should happen?"
* ✅ Mode changeable at deployment without code
* ❌ Single global mode — per-domain scoping not supported

## Links

* `casehub/work#220` — origin issue
* ADR-0003 — companion decision on Capability value type
