# Qhorus WorkItem Bridge — Design Spec

**Issue:** casehubio/work#97
**Date:** 2026-08-04
**Status:** Approved

## Problem

`WorkItemLifecycleEvent` and `WorkItemQueueEvent` are CDI events — they fire in-process only. In a distributed system, service B cannot observe WorkItem transitions in service A. The Qhorus blockers (casehubio/qhorus#131 Channel abstraction, casehubio/qhorus#132 delivery guarantees) are now closed.

## Approach

A new `casehub-work-qhorus` module providing a thin MCP-tool bridge between casehub-work and Qhorus. Three MCP tools give agents an explicit, discoverable interface for requesting and tracking human work. A `WorkItemObserver` SPI implementation posts terminal speech acts back to the originating Qhorus channel.

No automatic channel observation. No new entities or migrations. No new SPIs.

## Module Structure

**Location:** `casehub-work-qhorus/` in the work repo root (alongside `engine-adapter/`, `flow/`)
**Artifact:** `casehub-work-qhorus`
**Package:** `io.casehub.work.qhorus`
**Activation:** Classpath presence — consumer adds the dependency; CDI discovers the beans

### Dependencies

| Scope | Artifact | Why |
|---|---|---|
| compile | `casehub-work-api` | `WorkItemCreator`, `WorkItemObserver`, `WorkItemStatusEvent`, `WorkItemRef` |
| compile | `casehub-qhorus` | `MessageService.dispatch()` for posting to channels |
| compile | `quarkus-mcp-server-http` | `@Tool` annotation |
| compile | `quarkus-arc` | CDI |
| test | `casehub-work`, `casehub-work-deployment`, `casehub-work-persistence-memory` | Full runtime for integration tests |
| test | `casehub-qhorus`, `casehub-qhorus-persistence-memory` | Full runtime for integration tests |
| test | `quarkus-junit`, `quarkus-jdbc-h2`, `assertj-core`, `awaitility` | Test infrastructure |

Runtime requires both casehub-work and casehub-qhorus on the classpath.

## CallerRef Correlation

The existing `callerRef` field on WorkItem is the correlation mechanism, following the engine adapter's prefixed format (`case:{caseId}/pi:{planItemId}`).

### Format

```
qhorus:{channelId}/{messageId}/{correlationId}
```

- `channelId` — UUID of the Qhorus channel (required for `MessageService.dispatch()`)
- `messageId` — Long ID of the QUERY message posted by `request_human_work` (used as `inReplyTo` on the response)
- `correlationId` — UUID generated at creation time (threaded through QUERY and response)

Example: `qhorus:a1b2c3d4-e5f6-7890-abcd-ef1234567890/42/550e8400-e29b-41d4-a716-446655440000`

### QhorusCallerRef

Utility record in `io.casehub.work.qhorus`:

```java
public record QhorusCallerRef(UUID channelId, long messageId, String correlationId) {
    public static final String PREFIX = "qhorus:";

    public static boolean isQhorus(String callerRef) {
        return callerRef != null && callerRef.startsWith(PREFIX);
    }

    public static QhorusCallerRef parse(String callerRef) {
        // Strip prefix, split on '/' — exactly 3 segments: channelId/messageId/correlationId
        // No ambiguity: channelId is a UUID (no slashes), messageId is a long
    }

    public String encode() {
        return PREFIX + channelId + "/" + messageId + "/" + correlationId;
    }
}
```

The outbound adapter checks `isQhorus()` on every `WorkItemStatusEvent`. Non-Qhorus WorkItems are ignored.

## MCP Tools

`WorkQhorusMcpTools` — `@ApplicationScoped` bean with `@Tool`-annotated methods. Discovered by the same `quarkus-mcp-server-http` endpoint alongside `QhorusMcpTools`.

### request_human_work

Creates a WorkItem and posts an oversight/QUERY to the originating channel.

**Parameters:** `channel` (channel name), `title`, `description`, `candidate_groups` (optional), `priority` (optional), `payload` (optional JSON), `template_id` (optional), `sender` (requesting agent's instance ID).

**Flow:**
1. Resolve `channel` name to `channelId` (UUID) via `ChannelService.findByName()`. If not found → reject with error before creating anything.
2. Generate `correlationId` (UUID)
3. Post oversight/QUERY to the channel via `MessageService.dispatch()` with the correlationId. Capture the returned `messageId`.
4. Build `callerRef = QhorusCallerRef.encode(channelId, messageId, correlationId)`
5. Create WorkItem via `WorkItemCreator.create()` — `createdBy = "qhorus:" + sender`, `tenancyId` from `CurrentPrincipal`
6. Return `HumanWorkResponse(workItemId, callerRef, correlationId, status)`

**Transaction boundaries:** Steps 3 and 5 run in separate transactions (different datasources — qhorus and work). The channel post runs first so the QUERY message ID is available for the callerRef. If WorkItem creation fails after the QUERY is posted, the channel has a QUERY with no materialised WorkItem — the agent gets an error and can retry.

### check_work_status

Polls current WorkItem status.

**Parameters:** `caller_ref` (the callerRef returned from `request_human_work`).

**Flow:**
1. `WorkItemCreator.findByCallerRef(callerRef)`
2. Return `WorkStatusResponse(workItemId, status, assigneeId, outcome, resolution)` or "not found"

### wait_for_work

Polls until the WorkItem reaches a terminal state or times out.

**Parameters:** `caller_ref`, `timeout_seconds` (default 300), `poll_interval_seconds` (default 5).

**Flow:**
1. `WorkItemCreator.findByCallerRef(callerRef)` — if not found, return "not found"
2. If already terminal, return immediately with status + outcome
3. Poll in a loop: sleep `poll_interval_seconds`, then re-query via `findByCallerRef()`
4. On terminal status → return status + outcome
5. On timeout → return current status with `timedOut = true`

No in-memory registry needed. Polling via `findByCallerRef()` is cluster-safe (reads from the shared database), has no memory growth concerns, and avoids coordinating CompletableFuture completion across observer and MCP tool threads.

## Outbound Lifecycle Adapter

`QhorusWorkItemLifecycleAdapter` — `@ApplicationScoped`, implements `WorkItemObserver`.

### Behaviour

On every `WorkItemStatusEvent`:

1. Check `QhorusCallerRef.isQhorus(event.callerRef())` — if not Qhorus-originated, return
2. If status is not terminal, return
3. Parse `QhorusRef` from callerRef — extract `channelId`, `messageId`, `correlationId`
4. Map terminal status to speech act
5. Post to originating channel via `MessageService.dispatch()` using `channelId` (UUID), `correlationId`, and `inReplyTo = messageId`. **Wrapped in try-catch** — exceptions are logged at WARN and swallowed to prevent rolling back the WorkItem lifecycle transaction.

### Speech Act Mapping (terminal only)

| WorkItem terminal status | Qhorus speech act | Meaning |
|---|---|---|
| COMPLETED | DONE | Obligation fulfilled |
| REJECTED | FAILURE | Tried and could not complete |
| CANCELLED | DECLINE | Reasoned refusal |
| EXPIRED | DECLINE | Deadline passed without resolution |
| ESCALATED | FAILURE | Escalated beyond original scope |
| OBSOLETE | DECLINE | Superseded — no longer needed |

### Message Content

JSON payload with `workItemId`, `outcome`, `resolution`, `assigneeId`. The requesting agent gets everything it needs to act on the result.

### Sender Identity

Posts as `"workitems"` — a system identity. The human's identity is in the message content, not the sender field.

### Tenant Context

`WorkItemObserver.onStatusChange()` runs synchronously in the emitter's transaction. Tenant context is already active — no `TenantContextRunner` needed (unlike `@ObservesAsync` handlers per protocol PP-20260609-fb6563).

### Error Handling

The entire `onStatusChange()` body is wrapped in a try-catch. `WorkItemObserver` runs synchronously inside the `WorkItemLifecycleEmitter`'s transaction — an uncaught exception would mark the transaction for rollback, undoing the WorkItem lifecycle transition itself. The channel post is best-effort: catch all exceptions, log at WARN with `channelId` and `callerRef` for diagnosis, and return normally.

## What We Don't Touch

- `WorkCloudEventAdapter` — continues serving non-Qhorus external systems via CloudEvents. Both adapters coexist; they observe via different mechanisms (CDI async vs WorkItemObserver SPI).
- `WorkItemLifecycleEmitter` — unchanged. Already dispatches to `WorkItemObserver` implementations.
- `QhorusMcpTools` — not modified. `WorkQhorusMcpTools` is a separate bean discovered by the same MCP server.
- No Flyway migrations. No new entities. No changes to the WorkItem model.

## Testing

`@QuarkusTest` with both casehub-work and casehub-qhorus runtimes. In-memory stores (`casehub-work-persistence-memory`, `casehub-qhorus-persistence-memory`). H2 datasource.

### Test Cases

1. **request_human_work round-trip** — call tool, assert WorkItem created with correct callerRef (contains channelId, messageId, correlationId), assert QUERY posted to channel with matching correlationId
2. **request_human_work channel-not-found** — call tool with nonexistent channel name, assert error returned, assert no WorkItem created
3. **check_work_status** — create via tool, check PENDING; complete, check COMPLETED with outcome
4. **wait_for_work happy path** — request, complete after delay in separate thread, assert poll returns terminal status
5. **wait_for_work timeout** — request, wait with 1s timeout and 200ms poll interval, assert timedOut with current status
6. **wait_for_work already terminal** — create and complete, wait returns immediately without polling
7. **Outbound terminal posting** — Qhorus-originated WorkItem completed → DONE posted to originating channel with correct inReplyTo and correlationId
8. **Outbound adapter exception isolation** — dispatch throws → WorkItem lifecycle transaction still commits, exception logged
9. **Non-Qhorus WorkItem ignored** — non-Qhorus callerRef → no channel messages
10. **Speech act mapping** — REJECTED→FAILURE, CANCELLED→DECLINE, EXPIRED→DECLINE, ESCALATED→FAILURE, OBSOLETE→DECLINE
11. **Idempotency** — two `request_human_work` calls → two distinct WorkItems (unique correlationIds)
12. **CallerRef parsing** — unit tests for parse/encode/isQhorus with valid, malformed, edge-case inputs
