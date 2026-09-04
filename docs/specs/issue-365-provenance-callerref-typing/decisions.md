## D1: CrossSystemRef type design

**Choice:** Non-sealed interface in work-api with minimal surface; engine-specific types stay in engine-adapter
**Alternatives:**
- Sealed interface with all variants in work-api — violates callerRef opacity boundary (ARC42STORIES §3); work-api would know engine internals
- Single record with string fields — loses typed access to domain fields
- Keep callerRef as String, add ledgerEntryId separately — doesn't solve #366 at all
**Rationale:** Non-sealed `CrossSystemRef` interface in work-api provides `system()` and `encode()` — minimal surface, opacity-preserving. `CallerRef` sealed hierarchy stays in engine-adapter (extends `CrossSystemRef`). `QhorusRef` stays in qhorus (implements `CrossSystemRef`). Each module retains its own parse logic.
**Trade-offs:** No centralized `decode()` — each module parses its own format. Future platform lineage work (parent#363) can introduce a registry; not needed now.
**Sources:** engine-adapter/src/main/java/io/casehub/work/engine/CallerRef.java, qhorus/src/main/java/io/casehub/work/qhorus/QhorusCallerRef.java, ARC42STORIES.MD §3 (opacity boundary)
**Exploration:** quick
**Status:** revised (post design review — finding #3 opacity violation, #2 phantom field)

## D2: ledgerEntryId — separate from CrossSystemRef

**Choice:** ledgerEntryId threaded via WorkItemLifecycleEvent, NOT on CrossSystemRef
**Alternatives:**
- ledgerEntryId as a field on CrossSystemRef — phantom field: callerRef strings don't encode the ID, so decode() can never populate it
- Generic sourceRef(system, id) pair — over-generalized
**Rationale:** The ledger entry ID comes from a separate channel (LedgerEventCapture writes the entry after the event is created). CrossSystemRef is decoded from the callerRef string which doesn't contain the ID. Separate threading is the honest architecture.
**Trade-offs:** Two independent data channels for related provenance data (callerRef string + ledgerEntryId field). Consumers must read both.
**Sources:** ledger/src/main/java/io/casehub/work/ledger/service/LedgerEventCapture.java, design review finding #2 (phantom field)
**Exploration:** quick
**Status:** revised (post design review — finding #2)

## D3: Ledger entry ID threading mechanism

**Choice:** Private field on WorkItemLifecycleEvent with LedgerEntryIdSetter SPI for controlled mutation
**Alternatives:**
- Public mutable setter — breaks immutability contract visible to all consumers
- Post-capture CDI event — dual-observation risk, ledger-absent path loses terminal events
- Query at adapter time — DB query on every terminal event, wrong dependency direction
**Rationale:** CDI guarantees @Observes (sync) completes before @ObservesAsync fires. LedgerEventCapture is the sole writer. LedgerEntryIdSetter SPI keeps the public API immutable while allowing the ledger module to set the ID. No other sync observer reads ledgerEntryId.
**Trade-offs:** SPI indirection for a single setter. Acceptable for preserving the immutability contract.
**Sources:** ledger/src/main/java/io/casehub/work/ledger/service/LedgerEventCapture.java:72 (@Observes), engine-adapter/src/main/java/io/casehub/work/engine/WorkItemLifecycleAdapter.java:68 (@ObservesAsync), design review finding #1 (immutability)
**Exploration:** quick
**Status:** revised (post design review — finding #1 immutability)

## D4: Migration path for CallerRef and QhorusCallerRef

**Choice:** Rename in place, extend CrossSystemRef — minimal disruption
**Alternatives:**
- Move all types to work-api (sealed) — violates opacity
- Delete and recreate — unnecessary churn
**Rationale:** Pre-release stage, no external consumers. `PlanItemRef` → `PlanItemRef`, `GateRef` → `GateRef` (stay in engine-adapter, now extend `CrossSystemRef`). `QhorusRef` → `QhorusRef` (stays in qhorus, implements `CrossSystemRef`). Types stay in their modules — only the name and interface lineage change.
**Trade-offs:** Engine-side CallerRefParser stays as-is (follow-on issue). Two parallel parsing implementations until engine migrates.
**Sources:** CLAUDE.md (Stage: pre-release), design review finding #3 (keep types in their modules)
**Exploration:** quick
**Status:** revised (post design review — finding #3)

---

## Review Decisions Log

### Accepted
- [coherence+structure+robustness/HIGH] Mutable setter contradicts immutability — replaced with LedgerEntryIdSetter SPI
- [structure/HIGH] ledgerEntryId phantom field on CrossSystemRef — removed from CrossSystemRef, threaded via event
- [coherence+structure/HIGH] CrossSystemRef violates opacity boundary — changed to non-sealed interface, types stay in their modules
- [coherence/HIGH] ActionGateCompletionApplier missing — added to change list
- [robustness/HIGH] Deleted types still referenced — added SpawnCallerRefTest, ActionGateCancelledHandler
- [robustness/MEDIUM] fromWire() drops ledgerEntryId — added parameter
- [coherence/MEDIUM] Gate path omitted — added to threading diagram
- [robustness/MEDIUM] No integration test — added LedgerEntryIdThreadingIT
- [coherence/MEDIUM] entityType() unspecified — removed from interface (unnecessary)
- [structure/MEDIUM] GenericRef undermines type safety — removed (no centralized decode)
- [robustness/MEDIUM] GenericRef edge cases underdefined — removed (no centralized decode)

### Deferred
- [robustness/MEDIUM] Error handling mismatch (parse throws vs returns null) — preserved existing contracts; unification is a separate concern
- [structure/MEDIUM] CallerRefParser competing authority — engine-side follow-on issue
