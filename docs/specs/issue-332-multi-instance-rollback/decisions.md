# Design Decisions — #332 Multi-Instance Coordinated Rollback

## D1: Rollback target — timestamp and event-based

**Choice:** Support both timestamp-based and event-based subtree rollback targets
**Alternatives:**
- Timestamp only — simpler API, but no way to anchor rollback to a specific event in the tree's history
- Event only — precise but requires the caller to know a specific event ID
**Rationale:** Event-based resolves to a timestamp internally and delegates to the timestamp path. They serve different use cases: timestamp for "undo to 3pm," event for "undo to when the parent was at this state." Timestamp-based per-node rollback requires a new SPI method on `ProgressEventStore` — the existing methods (`findByProgressIdSince`, `findByRootProgressIdSince`) return events AFTER a timestamp, not the last event at-or-before a timestamp. New method: `findLastEventAtOrBefore(UUID progressId, Instant cutoff)` returning the most recent event for that node at or before the given instant (`<=` semantics), or `Optional.empty()` if none. At-or-before semantics are a correctness requirement: the event-to-timestamp delegation path resolves event E to `E.timestamp() = T`, then calls `findLastEventAtOrBefore(node, T)` for each node. With strict `<`, the node that event E belongs to would get the event *before* E — one step behind the intended target. With `<=`, it correctly returns E itself.
**Trade-offs:** Slightly larger API surface (one extra query param). New SPI method required across all store implementations (JPA, in-memory).
**Sources:** ProgressService.rollback(), ProgressService.rollbackToEvent() — existing single-instance precedent. These are event-based, not timestamp-based; timestamp-based rollback is a new capability.
**Exploration:** quick
**Status:** revised — acknowledged SPI gap; added findLastEventAtOrBefore with at-or-before (<=) semantics

## D2: Rollup suppression during coordinated rollback

**Choice:** Suppress rollup during the operation, then recompute once bottom-up
**Alternatives:**
- Let rollup cascade naturally — each rolled-back child triggers independent rollup recomputations. Intermediate states visible, OCC contention under fan-in (retry capped at 3 attempts).
**Rationale:** Coordinated rollback means the subtree moves atomically at the observable level. Piecemeal cascade contradicts the purpose. OCC storm under high fan-in (e.g., 20 children) is a practical failure mode.
**Trade-offs:** More complex than letting the existing cascade handle it. Requires a suppression mechanism on events and explicit bottom-up recomputation.
**Sources:** RollupObserver.java (lines 46-52, 55-66), progress-enhancements spec §3
**Exploration:** quick
**Status:** captured

## D3: Failure mode — best-effort with report

**Choice:** Best-effort with structured result report
**Alternatives:**
- All-or-nothing — one problematic node blocks the entire subtree rollback. Implies transactional semantics that don't match the observer model.
- Pre-validate then apply (two-phase) — adds a preview API the common case doesn't need. Caller can achieve the same by querying snapshots first.
**Rationale:** Progress model is an observer, not an orchestrator. All-or-nothing implies control semantics. Best-effort with a report gives full visibility — the caller (orchestrator) decides how to handle skipped nodes.
**Trade-offs:** Caller must inspect the result to know if all nodes were rolled back. No atomic guarantee.
**Sources:** Progress model design principles — "observation, not orchestration"
**Exploration:** quick
**Status:** captured

## D4: Nodes created after the target point — skip and exclude from rollup

**Choice:** Skip nodes created after the target rollback point. Report them as "skipped: created after target." Exclude post-target children from rollup recomputation (D12) — rollup reflects only pre-target children, faithfully representing the parent's state at time T. Document clearly that the orchestrator is responsible for deciding their fate.
**Alternatives:**
- Cancel/fail them — progress model doesn't own lifecycle control, that's the orchestrator's job
- Detach them (remove parentProgressId) — destructive, hard to undo, violates observer principle
- Include post-target children in rollup at their current state — produces mixed-era rollup that never existed at any point in time (e.g., rolled-back child at 40% averaged with post-target child at 80% = 60%, a state from no point in history)
- Include post-target children at initial state (zeros) — also a state that never existed; at time T the child didn't exist at all
**Rationale:** Consistent with observer principle. The progress model records state; it doesn't control which nodes exist. Orchestrator observes the result report and can explicitly fail/cancel post-target nodes via existing APIs. Excluding post-target children from rollup recomputation avoids a misleading mixed-era parent state — the parent reflects only children that existed at the target time.
**Trade-offs:** Post-target nodes remain in the tree in their current state after rollback but don't contribute to rolled-back rollup. Subsequent normal operations (child updates) will re-include all children via the standard async rollup observer. Documentation must be explicit about this.
**Sources:** Progress model design principles — "observation, not orchestration"
**Exploration:** quick
**Status:** revised — post-target children excluded from rollup recomputation (interaction with D12)

## D5: rollbackPolicy during coordinated rollback — bypass

**Choice:** Bypass `rollbackPolicy = "denied"` during coordinated subtree rollback
**Alternatives:**
- Honour policy — nodes with denied policy are skipped. Contradicts the existing single-instance precedent where explicit rollback always bypasses policy.
**Rationale:** Consistent with established precedent. Single-instance `rollback()` and `rollbackToEvent()` already bypass policy. The spec rationale: policy protects against accidental backward movement via `PUT /state`; explicit rollback endpoints are deliberate undo actions. Subtree rollback is even more deliberate.
**Trade-offs:** No per-node opt-out from coordinated rollback. If "immutability/freeze" is needed, it's a separate concept not yet in the model.
**Sources:** progress-enhancements spec §3 — "Internal structure — policy bypass"
**Exploration:** quick
**Status:** captured

## D6: Service placement — new SubtreeRollbackService

**Choice:** New `SubtreeRollbackService` in `progress-runtime`
**Alternatives:**
- Add to ProgressService — keeps all progress operations together, but ProgressService is already 400 lines and the coordination concern (tree traversal, rollup suppression, bottom-up recompute, result aggregation) is distinct from single-instance state management.
**Rationale:** Clean separation: ProgressService = single-instance, SubtreeRollbackService = multi-instance coordination. Tree traversal (`collectDescendants`) moves from REST layer to a shared location. Rollup suppression mechanism stays isolated from per-instance logic. SubtreeRollbackService delegates per-node rollback to ProgressService via a new public method `rollbackToTimestamp(UUID id, Instant target, UUID operationId)` that accepts the target timestamp and operation ID for event tagging. This method resolves the target timestamp to historical state via `findLastEventAtOrBefore` (D1), runs the validate-update-emit pipeline of `applyRollbackState` (currently private, line 226), and tags the emitted event with the provided operationId.
**Trade-offs:** One more service class. ProgressService gains a new public method. Minor.
**Sources:** ProgressService.java (403 lines), ProgressResource.collectDescendants() (REST-only tree traversal), ProgressService.applyRollbackState() (private — line 226)
**Exploration:** quick
**Status:** revised — explicit integration point: ProgressService exposes rollbackToTimestamp for SubtreeRollbackService

## D7: Rollup suppression mechanism — operationId on events

**Choice:** Add `UUID operationId` field to `ProgressUpdatedEvent`. RollupObserver checks this and skips rollup when set. SubtreeRollbackService sets it on all events during the operation.
**Alternatives:**
- Thread-local/request-scoped flag — doesn't work because @ObservesAsync runs on a different thread
- Don't emit events during rollback, emit batch afterward — loses per-node event granularity that SSE consumers and audit trails expect
- `coordinatedRollbackId` (operation-specific name) — self-documenting for rollback but encodes the use case into the field name. Future coordinated operations (complete, fail, reactivate) would need additional fields or repurpose with a misleading name.
**Rationale:** The suppression mechanism is generic: any coordinated batch operation should suppress individual rollup observer reactions and do its own synchronous rollup. `operationId` names this correctly without embedding the operation type. Consumers combine `operationId` with `changeType` to determine both correlation and operation semantics. Pre-release project, so getting the name right now avoids a rename later.
**Trade-offs:** Breaking change to ProgressUpdatedEvent record (gains a field). All callers constructing the event must be updated. Pre-release — acceptable.
**Depends on:** D2 (rollup suppression strategy)
**Sources:** RollupObserver.onProgressUpdated() (line 46), ProgressUpdatedEvent record
**Exploration:** quick
**Status:** revised — renamed from `coordinatedRollbackId` to `operationId` for generic applicability

## D8: Scope clarification — not full saga compensation

**Choice:** #332 is coordinated subtree state rollback only. "Saga-style" in the issue scope refers to the coordination pattern (visiting each node), not the full BPMN compensation model. Full saga compensation remains in #238.
**Alternatives:** None — this is a scope clarification, not a design choice.
**Rationale:** #238 owns full saga compensation (compensating bindings, compensation handlers, COMPENSATING/COMPENSATED states). #332's ROLLED_BACK events with operationId already provide the notification surface for orchestrators to observe and act on — no additional compensation SPI needed.
**Trade-offs:** Issue #332 description should be updated to remove misleading "saga-style" wording.
**Sources:** Issue #238 (saga compensation), issue #332 scope
**Exploration:** quick
**Status:** captured

## D9: Tree traversal — add findDescendantsOf to ProgressInstanceStore SPI

**Choice:** Add `findDescendantsOf(UUID parentId)` to `ProgressInstanceStore` SPI. Returns all transitive descendants of the given node using recursive CTE in JPA, recursive iteration in memory store.
**Alternatives:**
- `findByRootProgressId(UUID rootProgressId)` — flat `WHERE root_progress_id = :id` query, no recursion. Returns ALL nodes in the tree. For root-level subtree rollback (the common case), this is simpler and equally efficient. For mid-tree rollback, returns extra nodes from sibling branches requiring Java-side filtering to extract the target subtree. Avoids recursive CTE implementation burden across store implementations.
- Add to ProgressService — recursive Java logic, store SPI unchanged. Less efficient (N+1 queries for deep trees).
- Utility on SubtreeRollbackService — duplicates logic already in REST layer's collectDescendants.
**Rationale:** Tree traversal is a query concern. `findDescendantsOf(UUID parentId)` works from any node in the tree (not just the root), returning precisely the target subtree. JPA implementations use recursive CTE (`WITH RECURSIVE`) for single-query efficiency. In-memory store does recursive iteration. The flat `findByRootProgressId` alternative returns the entire tree regardless of target node, which is wasteful for mid-tree operations on large trees. Eliminates duplication between REST getTree and SubtreeRollbackService.
**Trade-offs:** SPI gains a method — all store implementations (JPA, in-memory) must implement it. Recursive CTE is more complex than flat query but precise.
**Sources:** ProgressResource.collectDescendants() (lines 206-213), ProgressInstanceStore SPI
**Exploration:** quick
**Status:** revised — renamed from `findDescendants(UUID rootId)` to `findDescendantsOf(UUID parentId)` for clarity; flat query alternative now explicitly considered

## D10: Result report structure — SubtreeRollbackResult record

**Choice:** `SubtreeRollbackResult` record containing `operationId`, `rootId`, `targetTimestamp`, and `List<NodeRollbackOutcome>`. Each outcome has `progressId`, `Outcome` enum (ROLLED_BACK, SKIPPED, FAILED), `reason` (null for success), `previousState`, `restoredState`, `policyBypassed` (boolean — true if the node had `rollbackPolicy = "denied"` and was bypassed per D5).
**Alternatives:** None considered — direct reflection of D3 (best-effort with report). The structure is dictated by the information consumers need.
**Rationale:** Gives callers full visibility: what was rolled back, what was skipped and why, what failed and why. Previous and restored states enable audit and orchestrator decision-making. `policyBypassed` flag surfaces nodes that had explicit rollback-denied policy, so operators are aware they overrode domain-specific protections during the coordinated rollback.
**Trade-offs:** None significant. New types in progress-api.
**Depends on:** D3 (best-effort with report), D7 (operationId)
**Sources:** D3 decision
**Exploration:** quick
**Status:** revised — renamed coordinatedRollbackId to operationId (D7); added policyBypassed flag

## D11: REST API — POST /progress/{id}/rollback/subtree

**Choice:** `POST /progress/{id}/rollback/subtree` with mutually exclusive query params `?timestamp={instant}` and `?toEvent={eventId}`. At least one parameter is required — returns 400 if neither is provided. Returns `SubtreeRollbackResult`.
**Alternatives:**
- `POST /progress/{id}/subtree/rollback` — groups under a `/subtree/` namespace. More extensible but the namespace doesn't exist yet and YAGNI.
- Parameterless "undo last change" — undefined for subtrees because each node's "last change" happened at a different time. N independent rollbacks is not coordinated rollback.
**Rationale:** Groups rollback operations together: `/rollback` for single-instance, `/rollback/subtree` for multi-instance. Matches how consumers think about it. Subtree rollback fundamentally requires a shared target point (timestamp or event) — parameterless undo makes sense for single-instance but is undefined for multi-instance.
**Trade-offs:** If future subtree operations are added, they won't share a namespace. Minor — can be introduced later.
**Depends on:** D1 (timestamp + event-based targets)
**Sources:** ProgressResource.rollback() (line 135), existing single-instance API pattern
**Exploration:** quick
**Status:** revised — explicitly require at least one query parameter, 400 if neither

## D12: Bottom-up rollup recomputation ordering

**Choice:** After rolling back all descendants with rollup suppressed: group descendants by depth (distance from root), recompute from deepest level upward, root last. Post-target children (D4 SKIPPED nodes) are excluded from the children list during each parent's recomputation — rollup reflects only pre-target children. SubtreeRollbackService reimplements the full recompute-store-emit pipeline from `RollupObserver.recompute()`, not just the engine call. The pipeline per parent node: (1) read parent instance, (2) read pre-target children, (3) call `RollupEngine.recompute(parent, children)`, (4) check `hasStateChanged()`, (5) construct updated `ProgressInstance`, (6) persist via `instanceStore.put()`, (7) create `ProgressUpdatedEvent` with `changeType = STATE_UPDATED` and `operationId` set, (8) append to event store and fire async. The `operationId` on the emitted event prevents `RollupObserver` from re-triggering — the observer skips events with non-null `operationId`.
**Alternatives:** None for ordering — this is the only correct ordering. Each level's recompute must use already-corrected children below it.
**Rationale:** Depth derived from parentProgressId chain during initial tree traversal. `RollupEngine.recompute()` returns only a `JsonNode` — the full store-emit pipeline must be reimplemented because the existing pipeline in `RollupObserver.recompute()` doesn't support `operationId` tagging or post-target child exclusion. Emitted events carry `operationId` so `RollupObserver.onProgressUpdated()` skips them (no infinite recursion).
**Trade-offs:** Partial duplication of `RollupObserver.recompute()` pipeline. Justified because the coordinated rollback path requires operationId tagging and post-target child filtering that the standard observer path doesn't support.
**Depends on:** D2 (rollup suppression), D4 (post-target exclusion), D7 (operationId suppresses observer)
**Sources:** RollupObserver.recompute() (lines 68-98), RollupEngine.recompute() (returns JsonNode only)
**Exploration:** quick
**Status:** revised — clarified full pipeline reimplementation; post-target children excluded from rollup per D4; operationId prevents recursion

## D13: Transaction scope — per-node transactions

**Choice:** Each node's rollback runs in its own transaction. If a node fails, it is reported as FAILED in the result; the operation continues with remaining nodes.
**Alternatives:**
- Single transaction wrapping the entire subtree rollback — atomic (all or nothing), but contradicts D3 best-effort semantics. Long-running: 20+ updates + rollup recomputes under one transaction risk timeout. Lock contention: holds row locks on all nodes simultaneously.
**Rationale:** Per-node transactions are the natural consequence of D3 (best-effort with report). Each node succeeds or fails independently. Partial rollback state is committed to the database and observable via queries between per-node commits — this is the expected behavior for an observer model. A crashed mid-rollback server leaves the tree in a partially-rolled-back state, which the operator can observe via the SubtreeRollbackResult (if the response was returned) or by querying individual node states.
**Trade-offs:** Partial rollback state is observable mid-operation. No atomic guarantee. Acceptable given best-effort design (D3).
**Depends on:** D3 (best-effort with report)
**Sources:** Implicit in D3 but surfaced by review
**Exploration:** quick — surfaced during review
**Status:** captured

## D14: OCC failure handling during subtree rollback

**Choice:** Optimistic concurrency control (OCC) failure on a node during subtree rollback is treated as a node failure: report as FAILED with reason "concurrent modification" in the NodeRollbackOutcome, continue with remaining nodes. No retry.
**Alternatives:**
- Retry the conflicting node — adds complexity, may still fail, delays the operation for other nodes. Note: `RollupObserver.recomputeWithRetry()` retries OCC (up to 3 attempts) but that's a fire-and-forget observer context where retry is cheap. Subtree rollback is an explicit operation with a result report — failure is reported, not hidden.
- Abort the entire operation — contradicts D3 best-effort semantics
**Rationale:** Consistent with D3 (best-effort with report) and D13 (per-node transactions). OCC failure is one possible failure mode; the response handles it like any other. The operator observes which nodes failed and can retry the subtree rollback or address individual nodes.
**Trade-offs:** No retry on OCC conflicts. The operator must re-invoke if they want to retry failed nodes.
**Depends on:** D3 (best-effort), D13 (per-node transactions)
**Sources:** ProgressInstanceStore.put() uses optimistic locking; RollupObserver.recomputeWithRetry() (lines 55-66)
**Exploration:** quick — surfaced during review
**Status:** captured

## D15: Rollup recompute event semantics during coordinated rollback

**Choice:** Events emitted during the rollup recomputation phase use `changeType = STATE_UPDATED` with `operationId` set. Not `ROLLED_BACK`.
**Alternatives:**
- `ROLLED_BACK` — because the event is part of a rollback operation. But the rollup engine computed a new value from children, not from historical state. The parent was never necessarily at this exact rollup state before.
**Rationale:** Rollup recomputation is a derivative computation — the engine aggregates children's current (post-rollback) states, it doesn't restore historical state. `STATE_UPDATED` accurately describes what happened. The `operationId` correlates it with the broader coordinated rollback for SSE consumers. Consumers can distinguish direct rollbacks (`ROLLED_BACK` + `operationId`) from their rollup consequences (`STATE_UPDATED` + `operationId`).
**Trade-offs:** None. Clear semantics.
**Depends on:** D7 (operationId), D12 (rollup recomputation)
**Sources:** RollupObserver.recompute() uses STATE_UPDATED for standard rollup (line 96)
**Exploration:** quick — surfaced during review
**Status:** captured
