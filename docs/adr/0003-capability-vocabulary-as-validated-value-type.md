# 0003 — Capability vocabulary as a validated value type

Date: 2026-05-29
Status: Accepted

## Context and Problem Statement

Engine `CaseDefinition` capability names and worker capability tags in `WorkerCandidate.capabilities`
were both raw `String`, matched by `WorkBroker` using exact case-sensitive equality. A mismatch
(e.g. `"legal-review"` vs `"legal_review"`) caused silent routing failure — no diagnostic pointed
at the vocabulary gap. As the platform scales, format drift becomes likely and hard to diagnose.

## Decision Drivers

* Format errors (underscore vs hyphen, mixed case) must be caught at the entry point, not at routing time
* Every new consumer of capabilities (filter expressions, queue routing, audit queries) faces the same format risk
* Breaking change is acceptable at 0.2-SNAPSHOT

## Considered Options

* **Option A** — `Capability` value record with kebab-case validation at construction time
* **Option B** — Keep `String`; add `CapabilityValidator` infrastructure to validate at write boundary
* **Option C** — `enum` of predefined capabilities (immutable, compile-time vocabulary)

## Decision Outcome

Chosen option: **Option A — `Capability` record**, because it eliminates the format-mismatch
bug class permanently at the type level rather than papering over it with validation
infrastructure. Every consumer that compiles with the new type is automatically protected.

### Positive Consequences

* Format violations caught at `Capability.of()` construction — 400 at the REST boundary, not at routing time
* `WorkBroker.filterByCapabilities` simplified — no string parsing
* All future consumers use `Capability` — format drift is impossible

### Negative Consequences / Tradeoffs

* Breaking change to `WorkerCandidate` and `SelectionContext` (acceptable at 0.2-SNAPSHOT)
* Existing DB rows with malformed strings require lenient parsing at the read path (strict-on-write / lenient-on-read)
* Wire format and DB column stay as comma-separated String — parse/serialize boundary in `CapabilityParser`

## Pros and Cons of the Options

### Option A — Capability value record

* ✅ Eliminates format-mismatch bug class at the type level
* ✅ All consumers protected automatically once they compile
* ❌ Breaking change to public SPI

### Option B — String + CapabilityValidator

* ✅ No breaking change
* ❌ Every new consumer repeats the same format risk
* ❌ Validation infrastructure is invisible to callers who bypass the write boundary

### Option C — enum

* ✅ Compile-time vocabulary enforcement
* ❌ Cannot add capabilities at runtime without redeployment
* ❌ External systems (engine case definitions) cannot declare new capabilities

## Links

* `casehub/work#220` — origin issue
* `api/src/main/java/io/casehub/work/api/Capability.java`
