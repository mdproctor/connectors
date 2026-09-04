## D1: WorkItem SPI boundary type

**Choice:** Immutable record in api/
**Alternatives:**
- Mutable POJO with public fields — lower migration effort assumed, but the assumption was wrong: entity stays mutable, only the SPI return type changes. Mutable SPI types reintroduce race conditions above the persistence boundary.
**Rationale:** All existing SPI boundary types in api/ are immutable (WorkItemRef, WorkItemSummary, WorkItemCreateRequest, WorkItemStatusEvent). ProgressInstance in progress-api/ proves this pattern at scale (~14 fields). The JPA entity stays mutable — WorkItemService mutation patterns don't change. The mapper produces an immutable snapshot on the way out.
**Trade-offs:** Record with ~40 fields requires a builder for construction ergonomics. One-time effort.
**Exploration:** quick → revised after decision review (R1-01)
**Status:** revised

## D2: JPA entity relationship to SPI type

**Choice:** Composition with standalone mapper class — `WorkItemMapper` in runtime/repository/ following `ProgressInstanceMapper` precedent
**Alternatives:**
- Mapper methods on the JPA entity itself — couples entity to SPI type constructor signature, fragile when fields change
- JPA entity extends SPI type (inheritance) — Hibernate handling of non-annotated superclasses is fragile, couples api/ to JPA semantics
**Rationale:** Progress module uses standalone `ProgressInstanceMapper` (static toDomain/toEntity/updateEntity methods). Isolates the field-mapping coupling from both the entity and the SPI type. Established pattern in this codebase.
**Trade-offs:** One standalone mapper class. Minor boilerplate.
**Exploration:** quick → refined after decision review (R1-03, R1-04)
**Depends on:** D1 (immutable record determines mapper construction pattern)
**Status:** revised

## D3: WorkItemRef coexistence

**Choice:** Keep both types — WorkItemRef as lightweight projection, WorkItem as full store SPI type
**Alternatives:**
- Expand WorkItemRef to full type and rename to WorkItem — simpler but forces every consumer (events, observers) to receive 40+ fields when they only need identity + status
**Rationale:** One concept, two views at different altitudes. WorkItemRef (11 fields) in events/observers. WorkItem (~40 fields) in store operations. Clear contract boundary.
**Trade-offs:** Two representations of the same concept in api/. Naming must be unambiguous.
**Exploration:** quick
**Status:** captured

## D4: Field scoping — what crosses the SPI boundary

**Choice:** All domain fields except version (OCC leak). JPA collection types (List<WorkItemLabel>, Set<WorkItemType>) become simple record equivalents in api/.
**Alternatives:**
- Expose version — leaks persistence optimistic locking semantics into the SPI contract
- Exclude computed fields (accumulatedUnclaimedSeconds) — these are domain data, not bookkeeping
**Rationale:** Follow ProgressInstance precedent — everything except OCC version. Computed tracking fields are domain-meaningful. JPA @ElementCollection types get clean api/ representations.
**Trade-offs:** Large field set (~40 fields) on the record. Mitigated by builder pattern.
**Exploration:** quick
**Status:** captured
