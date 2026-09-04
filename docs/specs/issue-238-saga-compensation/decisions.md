## D1: Scope

**Choice:** Full cross-platform design — spec covers all 5 repos (work, engine, ledger, qhorus, connectors), decomposed into sequenced child issues for implementation.
**Alternatives:**
- casehub-work only — implement work states first, defer engine/ledger/qhorus/connectors
- work + engine only — design the orchestration core, defer periphery
**Rationale:** Compensation is a cross-cutting concern. Designing each repo in isolation risks inconsistent models and rework. The spec defines the conceptual model; child issues scope the implementation.
**Trade-offs:** Larger spec, longer design phase. Worth it to prevent cross-repo incoherence.
**Sources:** casehub-work#238 issue body (scope section), lifecycle alignment spec (#240 §13)
**Exploration:** quick
**Status:** captured

## D2: Compensation model — separate compensating WorkItem

**Choice:** Original WorkItem stays COMPLETED (terminal invariant preserved). A new compensating WorkItem is created with `compensatesWorkItemId` linking back. Original gets a denormalized `compensationStatus` field (NONE/COMPENSATING/COMPENSATED).
**Alternatives:**
- Post-terminal status transition (COMPLETED → COMPENSATING) — breaks isTerminal() contract, requires auditing every consumer across the platform
- Metadata-only (ledger tracking) — no WorkItemStatus changes, loses lifecycle visibility (SSE events, filter rules, queue management)
**Rationale:** The lifecycle alignment spec (#240) just completed a massive audit of all isTerminal() consumers for FAULTED/OBSOLETE. Breaking the terminal invariant again would create the same class of bugs. Compensation is new work — a separate entity is the right model.
**Trade-offs:** Queries become slightly more complex ("is this WorkItem effectively compensated?" requires checking compensationStatus). Indirect causal relationship (linked WorkItem vs status on the same entity).
**Sources:** WorkItemStatus.java (12 values, isTerminal()), lifecycle alignment spec (#240 §9a-9f hardcoded status audit), LedgerEntry.causedByEntryId
**Exploration:** quick
**Status:** captured

## D3: Compensation trigger model

**Choice:** Engine-driven (primary) + explicit operator action (secondary). Engine fires compensate event on bindings (BPMN-style). Operators can trigger compensation on a single WorkItem via REST API. Both create compensating WorkItems.
**Alternatives:**
- Engine-driven only — prevents ad-hoc compensation, limits standalone casehub-work deployments
- Any caller via API — maximum flexibility but no coordination guarantee for multi-step sagas
**Rationale:** The engine owns saga coordination (reverse-ordering, scope tracking). Operator action is the escape valve for targeted fixes on individual WorkItems without case context.
**Trade-offs:** Two entry points to compensation (engine and operator) — must share the same underlying mechanism to avoid divergence.
**Sources:** casehub-work#238 issue body (choreography vs orchestration section)
**Exploration:** quick
**Status:** captured

## D4: Compensation ordering — topological reverse

**Choice:** Compensating actions execute in reverse topological order of the dependency graph. Dependent steps compensate in reverse dependency order (C→B→A for a chain A→B→C). Independent steps compensate concurrently. Strict reverse-completion order falls out as the degenerate case for a linear chain.
**Alternatives:**
- Strict reverse-completion order — simpler but unnecessarily sequential for independent steps; not more correct, only slower
- Parallel compensation — all compensating actions created simultaneously, faster but unordered; unsafe for dependent steps
- Configurable per binding — maximum flexibility, adds complexity to case definition model
**Rationale:** The engine already has dependency information via Binding trigger conditions, produced/consumed keys, and EventLog completion order. A topological sort of the completion graph gives correct ordering for dependent steps (reverse dependency) while allowing concurrency for independent steps. This is more correct than strict reverse for cases with parallel branches — a step that had no causal relationship with another step in forward execution has no ordering constraint in reverse.
**Trade-offs:** More complex compensation coordinator implementation. Worth it — topological ordering is architecturally correct, not just faster.
**Sources:** BPMN 2.0 §10.4.3 compensation semantics, casehub-work#238 issue body (orchestrated saga section), Binding.produces/consumes/contextWrite dependency graph
**Exploration:** quick
**Status:** revised (R1-04: topological ordering is more correct than strict reverse for independent steps)

## D5: Partial compensation — full scope only

**Choice:** Compensation always runs from the latest completed step backward. No cherry-picking individual steps at the engine level.
**Alternatives:**
- Partial allowed — engine can compensate a subset, requires tracking which steps were/weren't compensated
**Rationale:** Partial compensation creates complex state management (which steps are compensated, which aren't) and risks inconsistent domain state. Operator single-item compensation (D3) is the escape valve for targeted fixes.
**Trade-offs:** Cannot surgically compensate step 5 without compensating steps 1-4 at the case level. The operator API handles the surgical case.
**Sources:** Saga pattern literature, casehub-work#238 open questions section
**Exploration:** quick
**Status:** captured

## D6: Compensation failure — COMPENSATION_FAULTED as active intervention-required state

**Choice:** New non-terminal state COMPENSATION_FAULTED on CaseStatus. When a compensating action fails (FAULTED, REJECTED), the case enters COMPENSATION_FAULTED. `isTerminal()` returns false; `isActive()` returns true (analogous to SUSPENDED — paused, needs external action). Operators can retry compensation from the faulted step (COMPENSATION_FAULTED → COMPENSATING). No automatic retry.
**Alternatives:**
- COMPENSATION_FAULTED as terminal — contradicts D13's retry path (a terminal state cannot have outgoing transitions, which is exactly the invariant D2 protects for WorkItemStatus). Terminal means done; COMPENSATION_FAULTED is not done.
- Retry then fault — configurable retry count, adds complexity
- Propagate as FAULTED — simpler but loses the distinction between original faults and compensation faults
**Rationale:** Compensation failure is semantically distinct from execution failure. A case that faulted during execution is different from one that faulted during compensation — the latter is in a partially-compensated state requiring intervention. COMPENSATION_FAULTED is not "done" — the case needs operator attention and can be retried. Terminal means done, forever. This state is active: consumers keep watching, SSE subscribers stay connected, dashboards show it.
**Trade-offs:** COMPENSATION_FAULTED is active, so timeout monitors and progress trackers will see it. This is correct — it IS a case that needs attention, unlike a truly terminal case that is finished.
**Sources:** casehub-work#238 open questions ("Can compensation itself fail?"), WorkItemStatus.isTerminal()/isActive() pattern (SUSPENDED is isActive=true), LIFECYCLE.md terminal invariant
**Exploration:** quick
**Status:** revised (R2-01: reclassified from terminal to active; terminal states must not have outgoing transitions)

## D7: Case-level model — new CaseStatus values

**Choice:** CaseStatus gains COMPENSATING, COMPENSATED, and COMPENSATION_FAULTED. A COMPLETED case transitions to COMPENSATING when the engine fires a compensate event. COMPENSATED is terminal after all compensating bindings complete.
**Alternatives:**
- Compensation as a child case — mirrors WorkItem separate-entity decision, but cases are heavyweight
- Planner-internal only — hides compensation from case-level observers
**Rationale:** The case IS the thing being compensated. It makes sense for CaseStatus to reflect this. Unlike WorkItem (where compensation creates new work), case compensation is a state of the orchestration itself.
**Trade-offs:** COMPLETED → COMPENSATING is a post-terminal transition at the case level. CaseStatus consumers must be audited. This is acceptable because CaseStatus has fewer consumers than WorkItemStatus, and the semantic fit is strong.
**Prerequisite:** CaseStatus must be registered in LIFECYCLE.md with `isTerminal()` and `isActive()` methods before adding compensation states. CaseStatus is currently unregistered (7 values, no lifecycle methods). Per LIFECYCLE.md §4, new lifecycle enum registration requires: define isTerminal()/isActive(), register in LIFECYCLE.md, audit all consumers, file cross-repo issues. This must be completed as a predecessor task.
**Depends on:** D6 (COMPENSATION_FAULTED)
**Sources:** CaseStatus.java (7 values, no isTerminal/isActive), LIFECYCLE.md (CaseStatus absent from registered state machines), BPMN 2.0 compensation model
**Exploration:** quick
**Status:** revised (R1-02: added CaseStatus LIFECYCLE.md registration prerequisite)

## D8: Worker binding model — compensate: block on any Binding

**Choice:** Any Binding in the case definition can declare an optional `compensate:` block pointing to another Binding. The engine invokes the compensating binding when compensation is triggered. Worker-agnostic — a HumanTask can be compensated by a Workflow step and vice versa. HTN and Workflow workers can opt in.
**Alternatives:**
- Compensating binding per worker type — fragments the model across worker implementations
- Compensation handler SPI — clean separation but adds another SPI surface
**Rationale:** The engine is worker-agnostic by design. Compensation should follow the same pattern: the case definition declares what compensates what; workers execute. Any worker that supports the binding model gets compensation for free.
**Trade-offs:** Workers that don't support the binding's target type can't provide compensating actions. This is by design — not all workers need compensation.
**Sources:** casehub-engine Binding/BindingTarget model, user clarification (HTN and Workflow workers)
**Exploration:** quick
**Status:** captured

## D9: Ledger model — CompensationSupplement

**Choice:** New LedgerSupplement subclass (CompensationSupplement) alongside ComplianceSupplement and ProvenanceSupplement. Attaches to any LedgerEntry to mark it as a compensation record. Carries: originalEntryId, compensationReason, regulatoryBasis. Uses existing causedByEntryId for the causal chain.
**Alternatives:**
- New COMPENSATION LedgerEntryType — conflates entry type with compensation nature (a compensation entry is still an EVENT)
- Domain data only — loses type safety and queryability
**Rationale:** The supplement model is designed exactly for this: cross-cutting concerns attached to any entry without changing the core schema. CompensationSupplement follows the same pattern as ComplianceSupplement (GDPR) and ProvenanceSupplement (workflow source). causedByEntryId already provides the causal chain.
**Trade-offs:** New supplement table and migration. Acceptable — supplements are self-contained.
**Sources:** LedgerEntry.java (supplements, causedByEntryId), LedgerEntryType.java, ComplianceSupplement, ProvenanceSupplement
**Exploration:** quick
**Status:** captured

## D10: Qhorus integration — COMMAND with compensation context

**Choice:** Use existing COMMAND message type with compensation metadata (a `compensatesCommitmentId` field on the message content or artefactRefs). Agent is notified that their fulfilled commitment is being compensated via a standard COMMAND. CommitmentState stays FULFILLED; a new commitment is created for the compensating work (mirrors WorkItem separate-entity model).
**Alternatives:**
- New COMPENSATION_REQUESTED speech act — taxonomically inflating MessageType (currently 11 values) for a distinction that is contextual, not normatively distinct from COMMAND. Every new speech act propagates to MessageType consumers, MCP tools, channel projections, normative audit entries, and dashboards across all repos.
- CommitmentState gains COMPENSATED — breaks terminal-state invariant (same issue as WorkItem)
- Defer Qhorus integration — agents won't be notified of compensation
**Rationale:** A compensation request is semantically a directive — "undo your prior work." COMMAND already has the right normative semantics (creates obligation, requires correlationId). The compensation context is carried as metadata on the message, not as a new speech act type. The agent receives a COMMAND and discovers from context that it's compensating prior work. This avoids extending the speech-act taxonomy while conveying the same information.
**Trade-offs:** Compensation messages are less immediately distinguishable in audit views (they look like COMMANDs). But the CompensationSupplement on the ledger entry and the metadata on the message provide the context.
**Depends on:** D2 (separate compensating WorkItem)
**Sources:** MessageType.java (11 values: QUERY, COMMAND, RESPONSE, STATUS, DECLINE, HANDOFF, DONE, FAILURE, PROPOSE, JUDGMENT, EVENT), CommitmentState.java (7 values), QhorusWorkItemLifecycleAdapter
**Exploration:** quick
**Status:** revised (R1-05: COMMAND with metadata avoids taxonomy inflation; compensation is a directive, not a new speech act)

## D11: Visualization — included in spec

**Choice:** The spec includes a visualization section covering: (1) design-time case-definition view showing bindings + compensating bindings in YAML, (2) runtime saga execution timeline, (3) ledger causal chain graph. Implementation deferred to child issues.
**Alternatives:**
- Separate visualization issue — risks disconnecting visualization requirements from the data model
**Rationale:** Visualization requirements influence what data to expose and index. Designing them alongside the model ensures the right fields are available.
**Trade-offs:** Larger spec. Worth it — sagas are complex; visualization is the primary tool for understanding them.
**Sources:** User request, YAML frontends epic (#371)
**Exploration:** quick
**Status:** captured

## D12: YAML format — primary, with programmatic API

**Choice:** YAML is the primary format for declaring compensating bindings in case definitions. Also available programmatically via the Java API. Consistent with the YAML frontends epic (#371). Existing tooling supports creating visualizers from YAML definitions.
**Alternatives:**
- Programmatic only initially — fragments the case definition experience
**Rationale:** YAML is the established format for case definitions. Existing tooling (from #371) makes visualization creation straightforward. Compensating bindings should be first-class in the YAML schema.
**Trade-offs:** YAML schema must be extended. The yaml-core module from #379 handles variable resolution.
**Depends on:** D8 (compensate: block on Binding)
**Sources:** YAML frontends epic (#371), yaml-core migration (#379), user clarification
**Exploration:** quick
**Status:** captured

## D13: Compensation idempotency — state-guarded transitions

**Choice:** Compensation trigger is state-guarded and non-reentrant:
- COMPLETED → COMPENSATING: allowed (the normal trigger)
- COMPENSATING → reject with IllegalStateException (already compensating)
- COMPENSATED → reject (compensation complete, no re-compensation)
- COMPENSATION_FAULTED → allow manual retry (re-enters COMPENSATING from the faulted step)
- All other states → reject (only COMPLETED and COMPENSATION_FAULTED are valid entry points)
**Alternatives:**
- Allow re-compensation of COMPENSATED cases — creates recursive compensation chains; complex state management with no clear use case
- Reject COMPENSATION_FAULTED retry — leaves the case in a dead-end state with no recovery path
- Idempotent trigger (concurrent calls are no-ops) — hides race conditions rather than surfacing them
**Rationale:** COMPENSATION_FAULTED represents a partially-compensated state requiring human intervention. The operator's intervention is retrying from the faulted step. Rejecting retry from COMPENSATION_FAULTED would leave the case permanently stuck. Re-compensation of an already-COMPENSATED case has no semantic basis — the compensation is done.
**Trade-offs:** The retry path from COMPENSATION_FAULTED requires the engine to track which step faulted and resume from there.
**Sources:** R1-08 (reviewer-surfaced implicit decision)
**Exploration:** quick
**Status:** captured

## D14: Compensating WorkItems cannot be compensated

**Choice:** A compensating WorkItem (one with `compensatesWorkItemId != null`) cannot itself be compensated. `WorkItemService.compensate()` throws IllegalStateException if the target WorkItem has a non-null `compensatesWorkItemId`.
**Alternatives:**
- Allow chains (A → compensated by B → compensated by C) — creates recursive state management issues: what is A's compensationStatus when B is being compensated by C? Chain depth tracking and cycle prevention add complexity with no clear use case.
- Allow with depth limit — reduces but doesn't eliminate the state management complexity
**Rationale:** Compensation is a reversal of work, not a recursively composable operation. If compensating work itself needs reversal, that's a new forward operation (create a new WorkItem), not a meta-compensation. The `compensatesWorkItemId` link is a pair, not a chain.
**Trade-offs:** Operators who want to "undo a compensation" must create a new forward WorkItem manually. This is acceptable — the scenario is rare and the manual path is explicit.
**Sources:** R1-09 (reviewer-surfaced implicit decision)
**Exploration:** quick
**Status:** captured

## D15: Engine-work compensation interaction — skip already-compensated

**Choice:** When the engine's `JudgmentCompensationHandler` encounters a WorkItem whose `compensationStatus` is already COMPENSATED (from prior operator action), it skips creating a new compensating WorkItem and marks the compensating PlanItem as COMPLETED. The engine proceeds to the next step.
**Alternatives:**
- Fail (unexpected state) — undesirable; operator action is a supported path
- Create a second compensating WorkItem — creates duplicate compensation
- Require operator to use engine path only — removes the escape valve that D3 provides
**Rationale:** D3 explicitly provides operator single-WorkItem compensation as an escape valve. The engine must handle the case where an operator already compensated a WorkItem before a full-scope case compensation is triggered. Skip-and-proceed is the only semantically correct behavior.
**Trade-offs:** The engine must read WorkItem compensationStatus via the work adapter. This creates a cross-module query, but it's a simple field read on an entity the handler already references.
**Sources:** R1-03/R1-12 (reviewer-surfaced implicit decision), D3 operator escape valve, D5 full scope
**Exploration:** quick
**Status:** captured

## D16: WorkItem compensation events — extend existing bridge

**Choice:** Add `COMPENSATION_STARTED` and `COMPENSATION_COMPLETED` to `WorkItemSubscriptionBridge.EMITTED_EVENT_TYPES`. No new event class, no new bridge — the existing infrastructure already forwards all `WorkItemLifecycleEvent` CDI events to the notification DataSource. Only the EventTypeRegistry registration is missing.
**Alternatives:**
- New notification event class for work compensation — unnecessary; `WorkItemLifecycleEvent` already implements `SubscribableEvent` and carries all required fields
- New bridge module — unnecessary; `WorkItemSubscriptionBridge` already does the work, just with an incomplete event type set
**Rationale:** Exploration revealed that `WorkItemSubscriptionBridge.onWorkItemEvent()` observes ALL `WorkItemLifecycleEvent` CDI events without filtering — events for `COMPENSATION_STARTED` and `COMPENSATION_COMPLETED` already flow to the DataSource. The `EMITTED_EVENT_TYPES` set is only used for `EventTypeRegistry` registration (so the subscription engine knows these event types exist for matching). Adding 2 entries completes the wiring.
**Trade-offs:** None — this is a gap fix, not a design choice.
**Sources:** `WorkItemSubscriptionBridge.java` (work/runtime), `WorkEventType.java` (work/api)
**Exploration:** quick
**Status:** captured

## D17: Case compensation events — adapter bridge in engine-adapter

**Choice:** Create `CaseCompensationEvent` (a record implementing `SubscribableEvent`) and `CaseCompensationNotifier` (CDI observer for `CaseLifecycleEvent`) in `casehub-work-engine-adapter`. The notifier filters for compensation-related eventTypes and pushes `CaseCompensationEvent` into the notification DataSource.
**Alternatives:**
- Make `CaseLifecycleEvent` implement `SubscribableEvent` directly — requires adding `casehub-platform-api` dependency to `casehub-engine-common`, coupling engine internals to the notification system
- New module `casehub-engine-notification-bridge` — follows the qhorus pattern but the engine-adapter already has all required dependencies; a new module adds build complexity for no benefit
- Put the bridge in `casehub-connectors/notification-bridge` — wrong layer; connectors notification-bridge is the delivery side (registers `NotificationDeliverer`s), not the event source side
**Rationale:** The engine-adapter already depends on `casehub-engine` (provides `CaseLifecycleEvent`), `casehub-platform` (provides `DataSourceRegistry`), and `casehub-work-api` (provides `SubscribableEvent` transitively). It bridges engine↔work by design. A thin adapter event keeps engine and platform notification concerns decoupled.
**Trade-offs:** Engine-adapter gains notification responsibility alongside its existing WorkItem lifecycle bridging. This is acceptable — both are bridges between engine events and platform/work services.
**Depends on:** D7 (CaseStatus compensation states), D10 (Qhorus uses COMMAND, not a new speech act — consistent approach of reusing existing infrastructure)
**Sources:** `CaseLifecycleEvent.java` (engine-common), `CaseStatusChangedHandler.java` (engine/runtime), `engine-adapter/pom.xml` (dependency analysis), `QhorusObligationEvent.java` (pattern precedent), boundary-rules.md
**Exploration:** deep-analysis
**Status:** captured

## D18: Connectors — no changes needed

**Choice:** The `casehub-connectors` repo requires no code changes for compensation notifications. `ConnectorNotificationDeliverer` is fully generic — it receives `NotificationInput` from the subscription engine and delivers via `Connector.send()`. Once compensation events flow through the subscription engine (via D16 and D17), connectors deliver them automatically through existing channels.
**Alternatives:**
- Add compensation-specific templates or rendering — unnecessary; notification templates are configured via `SubscriptionInput.template()` at subscription registration time (D19), not in the deliverer
**Rationale:** Exploration of `ConnectorNotificationDeliverer.deliver()` confirmed it operates on `NotificationInput` with no event-type-specific logic. Title, body, category, and severity all come from the subscription template. The connectors repo's notification-bridge module (`NotificationBridgeStartup`) registers delivery channels generically.
**Trade-offs:** No compensation-specific email/Slack formatting. The subscription template provides title patterns and body patterns, which is sufficient for initial implementation. Custom digest formatters can be added later if needed.
**Sources:** `ConnectorNotificationDeliverer.java` (connectors/notification-bridge), `NotificationBridgeStartup.java`, notifications.md (platform docs)
**Exploration:** quick
**Status:** captured

## D19: Default subscriptions — bootstrap in engine-adapter

**Choice:** Create `CompensationSubscriptionBootstrap` in `casehub-work-engine-adapter` to register default system subscriptions for all 5 compensation event types at startup. Follows the `QhorusSubscriptionBootstrap` pattern: idempotent registration, `SubscriptionScope.SYSTEM`, `EVENT_FIELD` target resolution.
**Alternatives:**
- Separate bootstraps per module (one in work/runtime for work events, one in engine-adapter for case events) — fragments the registration; compensation subscriptions are a cohesive set that should be managed together
- No default subscriptions (admin-configured only) — defeats the purpose; compensation notifications must work out-of-box for compliance use cases
**Rationale:** The engine-adapter already hosts the case compensation bridge (D17). Placing all compensation subscription registrations together — both case-level and work-level — keeps the notification configuration cohesive. The `SubscriptionStore` dependency is available via `casehub-platform`.
**Trade-offs:** Work-level compensation subscriptions are registered from engine-adapter rather than work/runtime. This means compensation notifications only work when the engine-adapter is on the classpath. This is acceptable — compensation is an engine-driven feature; standalone casehub-work without the engine adapter doesn't have case-driven compensation.
**Depends on:** D16, D17
**Sources:** `QhorusSubscriptionBootstrap.java` (qhorus/notification-bridge), `SubscriptionInput` API, `SubscriptionConstants`
**Exploration:** quick
**Status:** captured

## D20: Design-time compensation graph — field on CaseDefinitionType

**Choice:** Add a `compensationGraph` computed field to the existing `CaseDefinitionType` GraphQL type. Returns `CompensationGraph` record containing: nodes (binding name, target type, isCompensation), edges (source binding → compensating binding), and gaps (bindings without compensateRef that are not compensation-only). Computed at query time from the definition's Binding list. Graph computation logic lives in a static utility in `engine-api` (pure function on `List<Binding>`).
**Alternatives:**
- Separate top-level `compensationGraph(namespace, name, version)` query — duplicates definition lookup; the graph is a property of the definition, not an independent entity
- Client-side computation — bindings already serialized via REST, but gap detection and edge extraction would be duplicated in every consumer
**Rationale:** GraphQL's field-level selection means the graph is only computed when claudony requests it. Placing it on `CaseDefinitionType` is idiomatic — the graph IS a projection of the definition.
**Trade-offs:** Adds complexity to `CaseDefinitionType`. Minimal — the graph computation is a pure function with no I/O.
**Depends on:** D11 (visualization included in spec), D8 (compensate: block on Binding)
**Sources:** `CaseDefinitionType.java` (engine-graphql), `Binding.java` (engine-api), `CaseQueryResolver.java`
**Exploration:** quick
**Status:** captured

## D21: Runtime compensation timeline — new GraphQL query

**Choice:** New `compensationTimeline(caseId: UUID!)` query in `CaseQueryResolver` returning `CompensationTimeline`. The timeline has two phases: `forwardSteps` (PlanItems from forward execution, ordered by creation time) and `compensationSteps` (compensating PlanItems, ordered by creation time). Each step carries: bindingName, targetType, status, timestamps, and for compensation steps: the original binding name. Case-level status (COMPENSATING/COMPENSATED/COMPENSATION_FAULTED) is included as the timeline's overall status. Data source: PlanItemStore for live state, EventLog for compensation events and metadata (triggeredBy, reason).
**Alternatives:**
- Extend existing `caseEvents` query with `format: TIMELINE` — overloads the query with a fundamentally different response shape; events are flat, timelines are structured
- SSE stream via ExecutionStateResource — already available for live updates; the timeline query provides the initial snapshot
**Rationale:** The timeline is a distinct view combining PlanItem state with EventLog metadata. It joins data that `caseEvents` (events only) and the plan API (items only) provide separately. A dedicated query is cleaner than a reformatted event list.
**Trade-offs:** New query + DTO. Acceptable — the response shape is specific to compensation visualization and doesn't fit existing types.
**Depends on:** D7 (CaseStatus compensation states), D4 (topological reverse ordering)
**Sources:** `CaseCompensationServiceImpl.java` (event metadata), `PlanItem.java` (compensation/compensatesItemId), `CaseQueryResolver.java`
**Exploration:** quick
**Status:** captured

## D22: Ledger compensation chain — new GraphQL query

**Choice:** New `compensationChain(caseId: UUID!)` query in `CaseQueryResolver` returning `CompensationChain`. The chain contains ledger entries that have a `CompensationSupplement`, structured as a list with causal links (each entry has `causedByEntryId` pointing to the original action). Each entry carries: entryId, timestamp, entryType, causedByEntryId, and the CompensationSupplement fields (originalEntryId, compensationReason, regulatoryBasis, compensationMode). Query delegates to ledger repository filtered by case and supplement type.
**Alternatives:**
- Generic `causalChain(entryId)` traversal query — more general-purpose but out of scope for #390; can generalize later
- Inline on `CaseInstanceType` — the chain is heavy and not always needed; a top-level query with explicit `caseId` is more intentional
**Rationale:** The compensation chain is a case-level concern. Filtering ledger entries by case + CompensationSupplement gives exactly the data the visualization needs. A case-scoped query is more efficient than a generic graph traversal.
**Trade-offs:** Limited to compensation entries — not a general causal chain viewer. This is fine for #390; a general `causalChain` query can follow later.
**Depends on:** D9 (CompensationSupplement), D11 (visualization)
**Sources:** `LedgerEntry.java` (causedByEntryId), `CompensationSupplement.java`, `CaseQueryResolver.caseEvents()` (pattern)
**Exploration:** quick
**Status:** captured
