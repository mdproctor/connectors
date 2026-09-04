## D1: Scope includes both PlanItem and ActionGate

**Choice:** Both HumanTask PlanItem dispatch/lifecycle and ActionGate approval/rejection are in scope for the distributed CloudEvent bridge.
**Alternatives:**
- PlanItem only, ActionGate deferred — simpler first pass but incomplete distributed story
**Rationale:** Both use the same callerRef pattern (`case:{caseId}/pi:{planItemId}` vs `case:{caseId}/gate:{gateId}`), the same CloudEvent transport, and the same module. Handling both in one module avoids a second pass through the same architecture.
**Trade-offs:** Larger scope for an already L-sized issue. Mitigated by the shared infrastructure — the ActionGate path is incremental once PlanItem works.
**Sources:** `WorkItemLifecycleAdapter` (decompiled — routes on CallerRef type), `ActionGateCompletionApplier` (decompiled), `EventBusAddresses.java` (action gate addresses)
**Exploration:** quick
**Status:** captured

## D2: Engine-only changes — no work repo modifications

**Choice:** All changes stay within the engine repo. The new engine module reimplements PlanItem/ActionGate completion logic using engine SPIs rather than extracting from `casehub-work-engine-adapter`.
**Alternatives:**
- Extract `PlanItemCompletionApplier` to engine, update work-engine-adapter to delegate — cleaner but requires cross-repo coordination
- Shared SPI in engine-common — adds abstraction for a single consumer
**Rationale:** Avoids cross-repo PR coordination. The completion logic is straightforward (~100 lines) and uses engine SPIs directly (`BlackboardRegistry`, `PlanItem`, `CrossTenantCaseInstanceRepository`, `BridgeResolver`). Two implementations are acceptable at pre-release — consolidation can follow (tracked in engine#974).
**Trade-offs:** Duplication of completion logic between work-engine-adapter and the new engine module. Both use the same underlying SPIs so they naturally stay aligned.
**Sources:** `PlanItemCompletionApplier.class` (decompiled), `HumanTaskScheduler.java:25`, `HumanTaskScheduleRequest.java:26`
**Exploration:** deep-analysis
**Status:** captured

## D3: Depend on casehub-work-api for CloudEvent type constants

**Choice:** The new engine module depends on `casehub-work-api` (Tier 1) for `WorkCloudEventTypes` and `WorkItemStatus`.
**Alternatives:**
- Hardcode CloudEvent type strings — zero coupling but risk of drift
**Rationale:** `casehub-work-api` is a pure Tier 1 artifact with no engine dependency. One-way dependency, same pattern as `engine-api → casehub-worker-api`. Doesn't worsen the existing engine ↔ work repo-level cycle (which is at the runtime/adapter level, tracked in engine#974).
**Trade-offs:** Adds one more edge from engine → work-api. Acceptable because the edge is one-way and the cycle is already present via other modules.
**Sources:** `WorkCloudEventTypes.class` (decompiled), engine root `pom.xml` (already declares `casehub-work-api` in dependencyManagement)
**Exploration:** deep-analysis
**Status:** captured

## D4: New engine module for distributed CloudEvent bridge

**Choice:** New module `casehub-engine-work-cloudevent` (or similar short name per maven-submodule-folder-naming protocol). Optional, activated by classpath presence.
**Alternatives:**
- Engine runtime — couples CloudEvent SDK to core even when not needed
- Engine common SPI + module impl — over-abstracted for a single implementation
**Rationale:** Follows the established pattern of optional engine modules (`engine-a2a`, `engine-mcp`, `engine-react`, `engine-flow`). Co-located mode (work-engine-adapter) and distributed mode (this module) are mutually exclusive deployment choices.
**Trade-offs:** One more module to maintain. Offset by clean separation — consumers that don't need distributed work don't pay for CloudEvent SDK on the classpath.
**Sources:** `a2a/pom.xml`, `mcp/pom.xml`, `react/pom.xml` (module patterns)
**Exploration:** quick
**Status:** captured

## D5: CDI Event<CloudEvent> transport

**Choice:** Emit and consume CloudEvents via CDI async events (`Event<CloudEvent>.fireAsync()` / `@ObservesAsync CloudEvent`). Transport-agnostic — a separate Quarkus messaging connector bridges CDI events to the wire (Kafka, AMQP, HTTP).
**Alternatives:**
- Quarkus Reactive Messaging (`@Outgoing`/`@Incoming`) — tighter transport integration but couples the module to a specific broker
- Direct HTTP client — simple point-to-point but no durability, no fan-out
**Rationale:** Same pattern as work's `WorkCloudEventAdapter` (outbound) and `WorkCloudEventInboundAdapter` (inbound). The engine module stays transport-neutral; deployment configuration selects the wire protocol.
**Trade-offs:** Requires a connector to be configured for production — CDI events alone are JVM-local. Acceptable because distributed deployment already implies infrastructure setup.
**Sources:** `WorkCloudEventAdapter` (work repo outbound pattern), `WorkCloudEventInboundAdapter` (work repo inbound pattern), issue-299 spec §Consumer
**Exploration:** quick
**Status:** captured

## D6: Classpath-based mode detection

**Choice:** Classpath detection using `Instance<HumanTaskScheduler>.isResolvable()`. If the work-engine-adapter provides a bean → co-located. If the CloudEvent module provides a bean → distributed. Mutually exclusive JARs.
**Alternatives:**
- Config property (`casehub.engine.work.mode=local|distributed`) — explicit but redundant with classpath, risk of misconfiguration
- Both with config override — unnecessary complexity
**Rationale:** The engine already uses this pattern — `publishHumanTaskSchedule()` checks `humanTaskScheduler.isResolvable()` and skips silently when absent. Adding a second `HumanTaskScheduler` implementation in the new module follows the same CDI discovery mechanism.
**Trade-offs:** Both modules on the classpath simultaneously would cause CDI ambiguity. Mitigated by `@Alternative @Priority` — the co-located adapter wins if both are present (it's more direct). Or documented as unsupported.
**Sources:** `CaseContextChangedEventHandler.java:692` (`humanTaskScheduler.isResolvable()`)
**Exploration:** quick
**Status:** captured

## D7: Implement existing HumanTaskScheduler SPI

**Choice:** The CloudEvent module implements `HumanTaskScheduler.schedule(HumanTaskScheduleRequest)`. The mapping from engine types to CloudEvent data is a module-internal concern.
**Alternatives:**
- New `HumanTaskCloudEventEmitter` SPI — cleaner separation but adds abstraction when the existing SPI works
- Extend `HumanTaskScheduleRequest` with distributed-specific fields — couples shared type to deployment mode
**Rationale:** `HumanTaskScheduleRequest` already carries all data needed to build the `io.casehub.work.workitem.create` CloudEvent: caseId, tenancyId, bindingName, target, inputData, candidateGroups/Users, scores, experiences, deadlines, title, scope, payloadTypeName, resolutionTypeName. The mapping is straightforward.
**Trade-offs:** The CloudEvent module depends on engine-common (for the SPI) — but all engine modules already do.
**Sources:** `HumanTaskScheduler.java:25`, `HumanTaskScheduleRequest.java:26-41`, issue-299 spec §CloudEvent Contract §Data Fields
**Exploration:** quick
**Status:** captured

## D8: ActionGateScheduler SPI — symmetric with HumanTaskScheduler

**Choice:** Create `ActionGateScheduler` SPI in `engine-common/spi/` and refactor `WorkflowExecutionCompletedHandler.handleGate()` to use it via `Instance<ActionGateScheduler>`. Same pattern as work#298's HumanTask refactoring.
**Alternatives:**
- Keep Vert.x event bus for ActionGate, have CloudEvent module consume it — preserves the event-as-request anti-pattern that work#298 is eliminating
- Defer ActionGate SPI to a follow-up — leaves distributed ActionGate broken until then
**Rationale:** The ActionGate outbound path (`ActionGateScheduleRequest` on Vert.x event bus → `ActionGateWorkItemHandler` via `@ConsumeEvent`) is the same event-as-request pattern that work#298 replaced for HumanTask. The CloudEvent module needs an SPI to implement — without it, the distributed ActionGate path has no seam. The refactoring is mechanical and follows the established pattern.
**Trade-offs:** Slightly larger scope — `WorkflowExecutionCompletedHandler.handleGate()` must be modified. Offset by consistency with the HumanTask path and elimination of the last event-as-request usage.
**Depends on:** D1 (scope includes ActionGate), D4 (new engine module)
**Sources:** `ActionGateWorkItemHandler.class` (decompiled — `@ConsumeEvent("casehub.action.gate.schedule")`), `WorkflowExecutionCompletedHandler.java:729` (publishes `ActionGateScheduleRequest`), `ActionGateScheduleEvent.java:24`
**Exploration:** quick
**Status:** captured
