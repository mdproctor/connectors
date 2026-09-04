# Scoped Worker Lifecycle Wiring — engine#823 + engine#824

**Date:** 2026-08-01
**Issues:** casehubio/engine#823, casehubio/engine#824
**Parent:** casehubio/engine#821 (lifecycle scopes epic)
**Status:** Design
**Supersedes:** Sections of `docs/specs/issue-237-lifecycle-scopes/2026-07-29-lifecycle-scopes-design.md` (§Runtime Infrastructure, §Dispatch and Lifecycle Integration) for implementation details — the parent spec remains authoritative for design decisions and rationale.

## Summary

Wire the scoped worker infrastructure into production code paths. The API types (`LifecycleScope`, `ExecutionMode`, `Participation`), session records (`ScopedWorkerSession`), registry (`ScopedWorkerRegistry`), YAML parsing, completion suppression, and termination handlers are all implemented. What's missing: production registration, re-invocation routing, accumulated state threading, output application, and PERSISTENT thread lifecycle.

This spec delivers the in-memory execution model for both REINVOKED and PERSISTENT scoped workers. Initial context delivery for PERSISTENT workers and durable state are follow-on (see Follow-On Issues).

## Prerequisites

- Lifecycle scope types, YAML parsing, binding validation — **delivered** (engine#237 Phase 1)
- Completion suppression in `QuartzWorkerExecutionJob.onSuccess()` — **delivered**
- Termination handlers (`ScopedWorkerTerminationHandler`, `CaseStatusChangedHandler`) — **delivered**
- `WorkerFunction.Persistent`, `PersistentScope`, `ScopeTerminatedException`, `WorkerOutcome.Completed` — **delivered** in `casehubio/worker`
- `WorkerScope.accumulatedState()` default method — **delivered** in `casehubio/worker`

## Root Problem: Dispatch Gate

`PlanningStrategyLoopControl.select()` uses `PlanItem.tryMarkRunning()` as a CAS gate for CapabilityTarget bindings. For scoped workers, the PlanItem transitions to RUNNING at first activation and stays RUNNING for the scope's lifetime. `tryMarkRunning()` returns `false` for an already-RUNNING PlanItem, blocking all subsequent dispatches.

Without fixing this gate, REINVOKED re-invocation is impossible regardless of registry registration.

## Design

### 1. Scope-Aware Dispatch Gate

**File:** `planning/.../PlanningStrategyLoopControl.java`

For CapabilityTarget bindings with `lifecycleScope != BINDING`, allow re-dispatch when the PlanItem is already RUNNING:

```java
if (binding.target() instanceof CapabilityTarget) {
    if (binding.lifecycleScope() != LifecycleScope.BINDING
            && pi.getStatus() == TaskStatus.RUNNING) {
        dispatched.add(binding);
    } else if (pi.tryMarkRunning()) {
        registry.indexForCompletion(caseId, pi.executorName(), pi.getPlanItemId());
        dispatched.add(binding);
    }
}
```

First activation (PENDING): `tryMarkRunning()` succeeds, index, dispatch.
Subsequent (RUNNING): scope check passes, dispatch without re-indexing.
Terminal: `isTerminal()` check earlier in the loop excludes it.

The registry check in `publishWorkerSchedule()` handles the actual dispatch decision — route to existing session vs. new dispatch.

### ScopedWorkerSession Record Revision

The parent spec defines `ScopedWorkerSession` records with a `planItemId` field. Code analysis shows `planItemId()` has zero references — termination handlers use binding name for PlanItem lookup (via `ScopedWorkerRegistry.terminateByScope(caseId, compoundId, ownedBindings)`), not planItemId.

Replace `planItemId` with `executorName` on both records. `executorName` IS needed — for REINVOKED re-dispatch, the handler must know which worker to re-invoke without repeating agent routing.

Additionally, `Reinvoked` gains `lastInputDataHash` for re-invocation cycle detection (see §2).

```java
public sealed interface ScopedWorkerSession
    permits ScopedWorkerSession.Persistent, ScopedWorkerSession.Reinvoked {

    String bindingName();
    UUID caseId();
    String executorName();
    LifecycleScope scope();
    Participation participation();

    record Persistent(
        String bindingName, UUID caseId, String executorName,
        LifecycleScope scope, Participation participation,
        BlockingQueue<ContextEvent> mailbox
    ) implements ScopedWorkerSession {}

    record Reinvoked(
        String bindingName, UUID caseId, String executorName,
        LifecycleScope scope, Participation participation,
        AtomicReference<Map<String, Object>> accumulatedState,
        AtomicReference<String> lastInputDataHash
    ) implements ScopedWorkerSession {}
}
```

`workerThread` is removed from the `Persistent` record. The termination protocol is mailbox-only (SHUTDOWN poison pill → `ScopeTerminatedException`), per the parent spec's guarantee that in-flight invocations "complete normally." No code path reads the thread reference — `terminateSession()` sends SHUTDOWN to the mailbox, never calls `Future.cancel()`. If forceful thread cancellation is needed as a future safety net (e.g., persistent worker stuck in computation that never polls `nextEvent()`), the field can be re-added alongside a shutdown-timeout protocol.

### 2. Scoped Session Registration and Re-Dispatch

**File:** `runtime/.../CaseContextChangedEventHandler.java`

Two integration points: (1) `publishWorkerSchedule()` checks the registry and routes to existing sessions; (2) after agent routing succeeds for first activation, `registerScopedSession()` registers the session before `scheduleWorker()`.

**Routing to existing sessions** in `publishWorkerSchedule()`:

```java
if (binding.lifecycleScope() != LifecycleScope.BINDING) {
    var existing = scopedWorkerRegistry.get(caseInstance.getUuid(), binding.getName());
    if (existing.isPresent()) {
        routeToExistingSession(existing.get(), caseInstance, binding,
            workers, capability, signalId, experiences);
        return;
    }
}
```

**`routeToExistingSession()`** — handles both session types:

```java
private void routeToExistingSession(ScopedWorkerSession session,
        CaseInstance caseInstance, Binding binding, List<Worker> workers,
        Capability capability, UUID signalId, List<RetrievedExperience> experiences) {
    switch (session) {
        case ScopedWorkerSession.Persistent p -> {
            JsonNode snapshot = caseInstance.getCaseContext()
                .layer(ContextLayer.WORKING).asJsonNode();
            p.mailbox().offer(new ContextEvent(snapshot, Map.of()));
        }
        case ScopedWorkerSession.Reinvoked r -> {
            // Cycle detection: skip re-invocation if projected input is unchanged
            String projection = binding.getInputProjectionOverride();
            String currentHash = computeInputHash(caseInstance, projection);
            if (currentHash != null && currentHash.equals(r.lastInputDataHash().get())) {
                LOG.debugf("Skipping re-invocation for '%s' — projected input unchanged",
                    binding.getName());
                return;
            }
            r.lastInputDataHash().set(currentHash);
            scheduleWorker(caseInstance, workers, binding, capability,
                r.executorName(), signalId, experiences);
        }
    }
}
```

**First-activation registration** — after agent routing succeeds in `publishWorkerSchedule()`:

```java
case RoutingResult.Selected s -> {
    final var a = s.single();
    if (binding.lifecycleScope() != LifecycleScope.BINDING) {
        registerScopedSession(caseInstance, binding, a.executorId());
    }
    scheduleWorker(caseInstance, workers, binding, capability,
        a.executorId(), signalId, experiences);
}
```

**`registerScopedSession()`** — creates and registers the session:

```java
private void registerScopedSession(CaseInstance caseInstance, Binding binding,
        String executorName) {
    var key = new ScopeKey(caseInstance.getUuid(), binding.getName());
    switch (binding.executionMode()) {
        case REINVOKED -> scopedWorkerRegistry.register(key,
            new ScopedWorkerSession.Reinvoked(
                binding.getName(), caseInstance.getUuid(), executorName,
                binding.lifecycleScope(), binding.participation(),
                new AtomicReference<>(Map.of()),
                new AtomicReference<>(null)));
        case PERSISTENT -> {
            var mailbox = new LinkedBlockingQueue<ContextEvent>();
            scopedWorkerRegistry.register(key,
                new ScopedWorkerSession.Persistent(
                    binding.getName(), caseInstance.getUuid(), executorName,
                    binding.lifecycleScope(), binding.participation(),
                    mailbox));
        }
        default -> {}
    }
}
```

**Registration placement rationale.** Registration happens after agent routing (not before) because `executorName` is only available from the routing result. This is safe: `PlanningStrategyLoopControl.filterAndIndexForDispatch()` uses `PlanItem.tryMarkRunning()` as a CAS gate — only one concurrent evaluation can win first activation for a given binding. Additionally, `CaseEvaluationSerializer` serializes all evaluations, so concurrent first-activation for the same binding cannot occur.

**PERSISTENT pre-registration** creates the mailbox at dispatch time. The `PersistentWorkerFunctionHandler` (§5) retrieves the pre-registered session from the registry to obtain the mailbox and sets the thread reference via `AtomicReference.set()`. This eliminates the context change loss window between dispatch and thread startup.

**Reinvoked re-invocation skips agent routing.** The `executorName` stored on the session at first activation is used directly for all subsequent re-dispatches. Input projection, scheduling, and Quartz execution still run on every invocation. This matches the parent spec: "Agent routing: first activation only."

**REINVOKED cycle detection.** `routeToExistingSession()` computes the input data hash from the current context and the binding's input projection, and compares it against `lastInputDataHash` stored on the session. If unchanged, re-invocation is suppressed — the output from the last invocation changed context keys outside the worker's input projection, so re-invoking with identical input would produce identical output. This breaks the feedback loop: output → `CONTEXT_CHANGED` → trigger match → re-dispatch → same output → ... Design constraint: REINVOKED workers whose output modifies keys within their own input projection must converge (produce eventually-unchanged output). Workers that oscillate infinitely are a binding design error, not an engine defect.

**REINVOKED idempotency dedup bypass.** `WorkerScheduleEventHandler.decideAction()` has its own dedup layer: it queries `EventLogRepository` for existing scheduling events within the idempotency window and skips scheduling if a SCHEDULED/STARTED/COMPLETED event has the same `inputDataHash`. This creates a cross-layer interaction: for input that oscillates (H1→H2→H1), the cycle detection in `routeToExistingSession()` correctly allows the re-invocation (H1≠H2), but `decideAction()` finds the earlier COMPLETED event with hash H1 in the idempotency window and blocks it. This suppresses a legitimate re-invocation because accumulated state has changed since the first H1 execution — the `inputDataHash` does not account for accumulated state.

Fix: `WorkerScheduleEventHandler.scheduleUnderLock()` bypasses `decideAction()` when `executionMode == REINVOKED`:

```java
ScheduleAction action;
if (executionMode == ExecutionMode.REINVOKED) {
    action = ScheduleAction.createNew();
} else {
    action = decideAction(existing, inputDataHash);
}
```

This is safe because:
1. Cycle detection in `routeToExistingSession()` is the primary guard for REINVOKED — it suppresses re-invocation when projected input is unchanged.
2. `CaseEvaluationSerializer` serializes evaluations, preventing concurrent duplicate scheduling.
3. The per-binding execution lock (§3) serializes actual execution, preventing accumulated state corruption.
4. The event-log idempotency check is designed for TRANSIENT workers where identical input ≡ identical execution. For REINVOKED, accumulated state makes same-input-hash invocations non-idempotent.

### 3. Accumulated State Threading

**ExecutionMetadata** (common module) gains `bindingName` and `executionMode`:

```java
public record ExecutionMetadata(
    String workerName,
    String inputDataHash,
    String bindingName,
    ExecutionMode executionMode
) {
    public ExecutionMetadata(String workerName, String inputDataHash) {
        this(workerName, inputDataHash, null, null);
    }
}
```

`QuartzWorkerExecutionJob` populates both from EventLog metadata (already serialized by `WorkerScheduleEventHandler`).

**SyncAgentWorkerFunctionHandler** injects `ScopedWorkerRegistry`:

**Per-binding execution lock.** REINVOKED Quartz jobs for the same binding can execute concurrently (evaluations are serialized, but dispatched Quartz jobs run asynchronously). Without serialization, two concurrent jobs both read the same accumulated state, and last writer wins — the first execution's output is lost.

`ScopedWorkerRegistry` gains `executionLock(ScopeKey)`. Lock entries are cleaned up by `terminateByScope` and `terminateByCase` alongside session removal:

```java
private final ConcurrentHashMap<ScopeKey, ReentrantLock> executionLocks = new ConcurrentHashMap<>();

public ReentrantLock executionLock(ScopeKey key) {
    return executionLocks.computeIfAbsent(key, k -> new ReentrantLock());
}
```

In `terminateByScope` and `terminateByCase`, after removing each session, also remove its lock:

```java
executionLocks.remove(new ScopeKey(caseId, bindingName));
```

Before creating `WorkerRuntime` — acquire the per-binding lock and read accumulated state:
```java
Map<String, Object> accState = Map.of();
ReentrantLock bindingLock = null;
if (metadata.executionMode() == ExecutionMode.REINVOKED && metadata.bindingName() != null) {
    var scopeKey = new ScopeKey(context.caseId(), metadata.bindingName());
    bindingLock = scopedWorkerRegistry.executionLock(scopeKey);
    bindingLock.lock();
    accState = scopedWorkerRegistry.get(context.caseId(), metadata.bindingName())
        .filter(ScopedWorkerSession.Reinvoked.class::isInstance)
        .map(s -> ((ScopedWorkerSession.Reinvoked) s).accumulatedState().get())
        .orElse(Map.of());
}
WorkerRuntime runtime = workerRuntimeFactory.create(
    context.caseId(), metadata.workerName(), context, accState);
```

After execution — write accumulated state and release the lock:
```java
try {
    if (metadata.executionMode() == ExecutionMode.REINVOKED && metadata.bindingName() != null) {
        Map<String, Object> output = toMap(result.output());
        if (output != null) {
            scopedWorkerRegistry.get(context.caseId(), metadata.bindingName())
                .filter(ScopedWorkerSession.Reinvoked.class::isInstance)
                .ifPresent(s -> ((ScopedWorkerSession.Reinvoked) s).accumulatedState().set(output));
        }
    }
} finally {
    if (bindingLock != null) bindingLock.unlock();
}
```

**Accumulated state semantics.** Accumulated state stores the worker's raw output from its last invocation — it is worker-private state across invocations, independent of conflict resolution applied to case context. When `ScopedWorkerOutputHandler` (§4) applies output to case context using `ConflictResolver.resolve()`, the resolved value may differ from the raw output (e.g., DEEP_MERGE combines existing and incoming values). Accumulated state always holds the raw output; case context holds the resolved value. Workers should use `accumulatedState()` to track their own progress across invocations and case context for reading the broader case state.

The lock serializes the entire read-execute-write sequence per binding. Concurrent invocations for the same binding queue behind the lock. The lock is scoped to the `ScopeKey` (caseId + bindingName), so different bindings and different cases execute independently.

**DefaultWorkerRuntime** gains an `accumulatedState` field and overrides:

```java
private final Map<String, Object> accumulatedState;

@Override
public Map<String, Object> accumulatedState() {
    return accumulatedState;
}
```

**WorkerRuntimeFactory** gains a 4-arg overload. The existing 3-arg method delegates with `Map.of()`.

### 4. Scoped Worker Output Application

**Problem:** `QuartzWorkerExecutionJob.onSuccess()` publishes to `"casehub.engine.scoped-worker-output"` (raw string, no consumer). Output is silently discarded.

**New event type** in `common/internal/event/`:

```java
public record ScopedWorkerOutputEvent(
    CaseInstance caseInstance,
    String bindingName,
    Map<String, Object> output,
    ExecutionMode executionMode
) {}
```

**New address** in `EventBusAddresses`:

```java
public static final String SCOPED_WORKER_OUTPUT = "casehub.engine.scoped-worker-output";
```

**QuartzWorkerExecutionJob.onSuccess()** — replace the raw string publish:

```java
if (output != null && !output.isEmpty()) {
    eventBus.publish(
        EventBusAddresses.SCOPED_WORKER_OUTPUT,
        new ScopedWorkerOutputEvent(instance, bindingName, output, executionMode));
}
```

Uses the Mutiny `EventBus` (already injected in this class), not the core Vert.x `vertx.eventBus()`. The Mutiny API handles codec registration automatically via Quarkus — core Vert.x requires explicit `MessageCodec` registration for POJO types and would throw `IllegalArgumentException` at runtime. This is consistent with the existing `WORKER_EXECUTION_FINISHED` publish path in the same class, which uses the Mutiny `eventBus`.

**New handler** in `runtime/internal/engine/handler/`:

```java
@ApplicationScoped
public class ScopedWorkerOutputHandler {

    @Inject CaseDefinitionRegistry definitionRegistry;
    @Inject ScopedWorkerRegistry scopedWorkerRegistry;
    @Inject EventBus eventBus;

    @ConsumeEvent(EventBusAddresses.SCOPED_WORKER_OUTPUT)
    @RunOnVirtualThread
    public void onScopedWorkerOutput(ScopedWorkerOutputEvent event) {
        CaseInstance instance = event.caseInstance();

        // Guard: discard output if session was terminated (scope ended while
        // Quartz job was in-flight). Per parent spec: "Any in-flight invocation
        // completes normally but output is discarded if scope has ended."
        if (scopedWorkerRegistry.get(instance.getUuid(), event.bindingName()).isEmpty()) {
            LOG.debugf("Discarding output for terminated scope binding '%s' case %s",
                event.bindingName(), instance.getUuid());
            return;
        }

        CaseDefinition definition = definitionRegistry.getCaseDefinition(
            instance.getCaseMetaModel());
        Binding binding = definition.findBindingByName(event.bindingName());
        String conflictStrategy = binding != null
            ? binding.getConflictResolverStrategy() : null;

        CaseContext caseContext = instance.getCaseContext();
        for (Map.Entry<String, Object> entry : event.output().entrySet()) {
            String key = entry.getKey();
            Object incoming = entry.getValue();
            Object existing = caseContext.get(key);
            Object resolved = ConflictResolver.resolve(
                conflictStrategy, key, existing, incoming);
            caseContext.set(key, resolved);
        }

        eventBus.publish(EventBusAddresses.CONTEXT_CHANGED,
            new CaseContextChangedEvent(instance, ContextLayer.WORKING.name(),
                null, null, null));
    }
}
```

This handler applies output using the same per-key `ConflictResolver.resolve(strategy, key, existing, incoming)` pattern as `WorkflowExecutionCompletedHandler.applyOutputWithConflictResolution()`, then publishes `CONTEXT_CHANGED` for downstream re-evaluation.

**Cross-cutting concern decisions for intermediate scoped output.** `WorkflowExecutionCompletedHandler` performs many concerns beyond output application. The following are deliberately excluded from intermediate scoped output — they are completion-specific:

| Concern | Included? | Rationale |
|---------|-----------|-----------|
| Output with conflict resolution | **Yes** | Core responsibility — same strategy as completion path |
| `CONTEXT_CHANGED` publish | **Yes** | Triggers downstream rule/goal re-evaluation |
| Event logging | **No** | Intermediate output is not a discrete work completion; volume concern for high-frequency emitters. Follow-on: engine#TBD |
| Context diff computation | **No** | Tied to event logging |
| Episodic layer updates | **No** | `EpisodicLayerUpdater.recordWorkerCompletion` records completion; intermediate output is not completion |
| Case resumption | **No** | Handled indirectly — `CONTEXT_CHANGED` triggers rule evaluation which covers natural resumption paths |
| Worker status listener | **No** | Reports completion events to external listeners |
| CDI lifecycle events | **No** | Completion-specific audit events |
| Personality recording | **No** | Records personality signal on completion outcome |
| Settlement tracking | **No** | Tracks signal-to-completion settlement |
| Action risk classifier | **No** | Risk classification applies at completion boundaries |
| Success outcome recording | **No** | Records routing outcome at completion |

### 5. PersistentWorkerFunctionHandler

**New handler** in `runtime/internal/executor/`:

```java
@ApplicationScoped
public class PersistentWorkerFunctionHandler implements WorkerFunctionHandler {

    @Inject @VirtualThreads ExecutorService virtualThreads;
    @Inject ScopedWorkerRegistry scopedWorkerRegistry;
    @Inject WorkerRuntimeFactory workerRuntimeFactory;
    @Inject CaseDefinitionRegistry definitionRegistry;
    @Inject WorkerExecutionRecoveryService recoveryService;
    @Inject EventBus eventBus;
    @Inject BridgeResolver bridgeResolver;

    @Override
    public boolean supports(WorkerFunction<?, ?> function) {
        return function instanceof WorkerFunction.Persistent;
    }

    @Override
    public WorkerResult<?> execute(WorkerFunction<?, ?> function, Object inputData,
            WorkerContext context, int timeoutMs, ExecutionMetadata metadata) {
        var persistent = (WorkerFunction.Persistent<?>) function;

        var session = scopedWorkerRegistry.get(context.caseId(), metadata.bindingName())
            .filter(ScopedWorkerSession.Persistent.class::isInstance)
            .map(ScopedWorkerSession.Persistent.class::cast)
            .orElseThrow(() -> new IllegalStateException(
                "Pre-registered persistent session not found for binding: "
                    + metadata.bindingName()));

        CaseDefinition definition = definitionRegistry.getCaseDefinition(/* from context */);
        Binding binding = definition.findBindingByName(metadata.bindingName());

        var scope = buildPersistentScope(persistent.inputType(), session.mailbox(),
            context, metadata, binding, definition);

        Future<?> thread = virtualThreads.submit(() -> {
            try {
                ((Consumer) persistent.handler()).accept(scope);
                publishCompletion(context, metadata, WorkerOutcome.completed());
            } catch (ScopeTerminatedException e) {
                // Engine-initiated shutdown — normal termination
            } catch (Exception e) {
                publishCompletion(context, metadata, WorkerOutcome.failed(e.getMessage()));
            }
        });

    // publishCompletion loads a FRESH CaseInstance via recoveryService before
    // publishing — the context captured at dispatch time may be stale or closed
    // by the time the persistent thread exits. This matches the pattern used by
    // QuartzWorkerExecutionJob.execute().
    private void publishCompletion(WorkerContext context, ExecutionMetadata metadata,
            WorkerOutcome<?> outcome) {
        CaseInstance freshInstance = recoveryService.loadOrRestoreCaseInstance(context.caseId());
        if (freshInstance == null) {
            LOG.warnf("Case %s evicted before persistent worker '%s' completion — skipping",
                context.caseId(), metadata.bindingName());
            return;
        }
        // ... publish WorkflowExecutionCompleted with freshInstance ...
    }

        return WorkerResult.of(Map.of());
    }
}
```

The handler retrieves the pre-registered session from the registry (registered in §2 by `publishWorkerSchedule()`), uses its mailbox, and submits the virtual thread. No session construction or registration happens here — that's entirely owned by the dispatch point.

The handler returns `WorkerResult.of(Map.of())` — Success with empty output. The Quartz job's `onSuccess()` sees `executionMode=PERSISTENT`, `outcome=Success`, suppresses `WORKER_EXECUTION_FINISHED`, publishes `ScopedWorkerOutputEvent` with empty output (no-op in the handler).

The virtual thread runs independently. When the persistent handler exits or faults, the thread's wrapper publishes `WORKER_EXECUTION_FINISHED` with the appropriate outcome (`Completed` or `Failed`), triggering normal PlanItem completion.

### 6. DefaultPersistentScope

**New type** in `runtime/internal/worker/scope/`:

Implements `PersistentScope<T>`:

- `nextEvent()`: takes from the mailbox. If `ContextEvent.SHUTDOWN`, throws `ScopeTerminatedException`. Otherwise, applies input projection via JQ evaluator, deserializes via bridge to type `T`, returns.
- `emit(Map<String, Object> output)`: applies output schema projection (if the capability defines one), then publishes `ScopedWorkerOutputEvent` via event bus (fire-and-forget). Output is applied to case context by `ScopedWorkerOutputHandler`. This ensures PERSISTENT `emit()` output receives the same schema treatment as REINVOKED output (which passes through `DefaultWorkerExecutor.applyOutputSchema()`).
- `caseId()`, `taskId()`: from constructor args.
- `execute()`: delegates to an inner `WorkerRuntime` (Tier 1 orchestration available to persistent workers).
- `accumulatedState()`: returns `Map.of()` (persistent workers don't use accumulated state — they have the mailbox).

Constructor parameters: `inputType`, `mailbox`, `caseId`, `taskId`, `context`, `eventBus`, `inputProjection`, `outputProjection`, `bridgeResolver`, `jqEvaluator`, `expressionEngineRegistry`, `workerRuntimeFactory`.

### 7. Schedule Trigger Integration

**Files:** `scheduler-quartz/.../ScheduledTriggerJob.java`, `scheduler-quartz/.../ConditionalScheduledTriggerJob.java`

The parent spec (§ScheduleTrigger interaction with scoped bindings) describes schedule-triggered scoped workers. The current `ScheduledTriggerJob` publishes a `WorkerScheduleEvent` directly — it does not check `ScopedWorkerRegistry`, and it omits lifecycle fields on the event.

**Changes to `ScheduledTriggerJob`:**

1. Inject `ScopedWorkerRegistry`
2. Before publishing `WorkerScheduleEvent`, check the registry for existing sessions:

```java
Binding binding = findBinding(definition, bindingName);
if (binding != null && binding.lifecycleScope() != LifecycleScope.BINDING) {
    var existing = scopedWorkerRegistry.get(caseId, bindingName);
    if (existing.isPresent()) {
        switch (existing.get()) {
            case ScopedWorkerSession.Persistent p -> {
                JsonNode snapshot = caseInstance.getCaseContext()
                    .layer(ContextLayer.WORKING).asJsonNode();
                p.mailbox().offer(new ContextEvent(snapshot, Map.of()));
                return;
            }
            case ScopedWorkerSession.Reinvoked r -> {
                // Use session's executorName, not trigger-resolved worker —
                // "Agent routing: first activation only" applies across all
                // dispatch origins (context change, schedule trigger).
                Worker sessionWorker = definition.getWorkers().stream()
                    .filter(w -> w.name().equals(r.executorName()))
                    .findFirst().orElse(null);
                if (sessionWorker == null) {
                    LOG.warnf("Executor '%s' no longer in definition for binding '%s'",
                        r.executorName(), bindingName);
                    return;
                }
                // Update lastInputDataHash for consistency with context-change path
                String currentHash = computeInputHash(caseInstance,
                    binding.getInputProjectionOverride());
                r.lastInputDataHash().set(currentHash);
                eventBus.publish(EventBusAddresses.WORKER_SCHEDULE,
                    new WorkerScheduleEvent(caseInstance, sessionWorker, capability,
                        bindingName, null, null, ExecutionOrigin.SCHEDULE_TRIGGER,
                        List.of(), binding.lifecycleScope(), binding.executionMode()));
                return;
            }
        }
    }
}
```

3. Pass lifecycle fields on `WorkerScheduleEvent` for first activation:

```java
eventBus.publish(EventBusAddresses.WORKER_SCHEDULE,
    new WorkerScheduleEvent(caseInstance, worker, capability,
        bindingName, null, null, ExecutionOrigin.SCHEDULE_TRIGGER, List.of(),
        binding != null ? binding.lifecycleScope() : null,
        binding != null ? binding.executionMode() : null));
```

`ConditionalScheduledTriggerJob` requires the same changes — it also publishes `WorkerScheduleEvent` directly.

**CDI dependency note:** `ScopedWorkerRegistry` is `@ApplicationScoped` in `runtime`. `ScheduledTriggerJob` is `@ApplicationScoped` in `scheduler-quartz`. Both are CDI beans in the same Quarkus deployment — injection works across module boundaries.

### 8. Protocol Compliance Fix

**File:** `runtime/.../ScopedWorkerTerminationHandler.java`

Currently uses `blocking = true`. Per protocol PP-20260723-c4c1cf, change to `@RunOnVirtualThread` + `void`:

```java
@ConsumeEvent(EventBusAddresses.COMPOUND_COMPLETED)
@RunOnVirtualThread
public void onCompoundCompleted(CompoundCompletedEvent event) { ... }
```

## Module Placement

| Type | Module | Package |
|------|--------|---------|
| `ExecutionMetadata` (expanded) | `common` | `io.casehub.engine.common.internal.executor` |
| `ScopedWorkerOutputEvent` | `common` | `io.casehub.engine.common.internal.event` |
| `EventBusAddresses.SCOPED_WORKER_OUTPUT` | `common` | `io.casehub.engine.common.internal.event` |
| `PersistentWorkerFunctionHandler` | `runtime` | `io.casehub.engine.internal.executor` |
| `DefaultPersistentScope<T>` | `runtime` | `io.casehub.engine.internal.worker.scope` |
| `ScopedWorkerOutputHandler` | `runtime` | `io.casehub.engine.internal.engine.handler` |

No new types in `engine-api`. No changes to `casehubio/worker` or `casehubio/platform`.

## Interaction with Existing Features

| Feature | Impact |
|---------|--------|
| Agent routing | First activation only — re-invocations use session's `executorName` |
| Input projection | Applied on every invocation (REINVOKED and PERSISTENT) |
| Output schema | REINVOKED: applied by `DefaultWorkerExecutor.applyOutputSchema()`. PERSISTENT: applied in `DefaultPersistentScope.emit()` before publishing. Symmetric treatment. |
| ConflictResolver | Used by `ScopedWorkerOutputHandler` — same per-key strategy as `WorkflowExecutionCompletedHandler` |
| Completion suppression | Unchanged — existing `onSuccess()` logic correct |
| Termination handlers | Unchanged — already call `terminateByScope/terminateByCase` |
| Personality recording | Not triggered by scoped output (separate event path) |
| Settlement tracking | Scoped workers don't participate (per parent spec) |
| Action risk classifier | **Design revision from parent spec.** Not applied to intermediate scoped output (only on completion). Parent spec said "per output application, same as current" — but intermediate scoped output flows through `ScopedWorkerOutputHandler`, not `WorkflowExecutionCompletedHandler`. Intermediate emissions are partial evolving state, not completion boundaries. Risk classification at completion captures the final worker decision; intermediate output that triggers downstream bindings is gated by those bindings' own risk classification. |
| Schedule triggers | Registry check added to `ScheduledTriggerJob` and `ConditionalScheduledTriggerJob` (§7). Subsequent cron/delay fires route to existing sessions. |

**Known coupling:** `SyncAgentWorkerFunctionHandler` gains accumulated state retrieval/storage for REINVOKED workers, conditioned on `metadata.executionMode()`. This is a second responsibility (scope session state management) added to the sync/agent dispatch handler. The pragmatic reason: `WorkerFunctionHandler.supports()` receives only `WorkerFunction<?, ?>`, not `ExecutionMetadata`, so a separate handler can't distinguish REINVOKED Sync functions from regular Sync functions. If more execution-mode-specific behavior accrues, the SPI should evolve — either `supports()` receives metadata, or accumulated state threading moves to `DefaultWorkerExecutor` as a cross-cutting concern.

## Follow-On Issues (Out of Scope)

- **Durable accumulated state** — REINVOKED state is in-memory. JVM restart loses it. Depends on engine#732 (CaseContextStoreFactory recovery path).
- **Persistent session recovery with mailbox replay** — On JVM restart, RUNNING PlanItems with scoped bindings need recovery. `WorkerRecoveryCoordinator` would re-create sessions and re-dispatch.
- **External worker (WorkerFunction.None) lifecycle scope** — Qhorus channel lifetime scoping for external workers.
- **YAML schema validation tooling** — Compile-time validation for scope/participation/trigger consistency.
- **REINVOKED oscillation guard** — The input data hash check (§2) breaks cycles where output changes keys outside the input projection. Workers whose output changes keys within their input projection must converge by design. A configurable `maxReinvocationsPerEvaluation` safety valve is deferred — the serialized evaluation model (`CaseEvaluationSerializer`) and hash check provide sufficient protection for v1. If pathological bindings emerge in practice, add the counter.
- **Event logging for intermediate scoped output** — `ScopedWorkerOutputHandler` deliberately omits event logging for intermediate output (see §4 cross-cutting concern table). If audit trail gaps prove problematic, add a lightweight `SCOPED_WORKER_OUTPUT_APPLIED` event type that records bindingName, output keys, and timestamp without the full completion-path metadata.

## Verification

```bash
mvn install -DskipTests -q
TESTCONTAINERS_RYUK_DISABLED=true mvn clean test -pl planning
TESTCONTAINERS_RYUK_DISABLED=true mvn clean test -pl runtime
TESTCONTAINERS_RYUK_DISABLED=true mvn clean test -pl scheduler-quartz
```

Integration tests needed:
1. REINVOKED: first dispatch registers session, second dispatch routes to session, accumulated state available
2. PERSISTENT: first dispatch starts thread, context changes arrive on mailbox, emit() applies output
3. Scope termination: compound completion terminates scoped sessions
4. Case termination: all scoped sessions terminated
5. REINVOKED cycle detection: output that changes context keys outside input projection → re-invocation suppressed (hash unchanged)
6. Schedule trigger: cron tick routes to existing scoped session instead of creating new dispatch
