# Opt-in Delivery Tracking for BARRIER and COLLECT Channels

**Issue:** casehubio/qhorus#376
**Date:** 2026-07-23
**Status:** Approved

## Problem

Qhorus has three tracking layers for message lifecycle, but a gap between backend delivery and participant acknowledgement:

| Question | Answered by | Exists? |
|----------|-------------|---------|
| Did the backend accept the message? | `DeliveryCursor` (per backend, per channel) | Yes |
| Did participant X receive the message? | **Nothing** | **No** |
| Has participant X read up to message N? | `lastReadMessageId` (per member) | Yes |
| Did participant X acknowledge the obligation? | `CommitmentStore` (OPEN→ACKNOWLEDGED) | Yes |

`DeliveryCursor` is per-backend, not per-participant. If a message reaches the Slack backend but one participant's agent process has crashed, the cursor shows success while one participant never received it.

The commitment lifecycle covers obligations (COMMANDs), but non-COMMAND messages — BARRIER contributions, HANDOFF context, informational updates — have no commitment. Whether a participant received those matters for coordination, but nothing tracks it today.

The watchdog's `BARRIER_STUCK` condition can say "contributor X hasn't written" but cannot distinguish "never received the COMMAND" from "received it but hasn't responded." Same gap in `CONVERSATION_STALL`.

## Approach

Extend `channel_membership` with a `lastDeliveredMessageId` cursor — same shape as the existing `lastReadMessageId`. Forward-only, per-member, per-channel. Advanced when the transport layer confirms delivery to a specific participant.

Opt-in by channel semantic: BARRIER and COLLECT enable tracking automatically. All others default off. Explicit override via `channel.trackDelivery`.

### Why not platform's delivery SPI?

The platform's `DeliveryAttempt` / `EngagementEvent` infrastructure (post platform#192) is designed for notification delivery — multi-channel, multi-attempt, retry with backoff, per-attempt failure reasons. That's the right shape for "did this alert reach this person via email AND Slack AND push."

Qhorus message delivery is cursor-shaped: "transport confirmed, advance the high-water mark." Using the platform SPI would create:

1. **Shape mismatch** — cursor vs multi-attempt retry store
2. **Storage duplication** — `EngagementEvent.OPENED` duplicates `lastReadMessageId`
3. **Cross-store queries** — watchdog would query platform's store alongside qhorus's
4. **Retention overhead** — platform delivery tracking needs scheduled sweeps; a cursor column has none

Platform's delivery SPI serves notification delivery (#375). Qhorus messages are a different consumer.

## Design

### 1. Data model

**`ChannelMembership` record** — add `lastDeliveredMessageId`:

```java
public record ChannelMembership(
        Long id, UUID channelId, String memberId, MemberRole role,
        String tenancyId, Instant joinedAt,
        Long lastReadMessageId, Long lastDeliveredMessageId) {

    // Backward-compatible constructor — existing callers pass 7 args
    public ChannelMembership(Long id, UUID channelId, String memberId, MemberRole role,
                             String tenancyId, Instant joinedAt, Long lastReadMessageId) {
        this(id, channelId, memberId, role, tenancyId, joinedAt, lastReadMessageId, null);
    }
}
```

**`ChannelMembershipEntity`** — new column:

```sql
ALTER TABLE channel_membership ADD COLUMN last_delivered_message_id BIGINT;
```

Nullable, no FK. Same pattern as `lastReadMessageId`. Existing rows get null — tracking starts from next message after feature is enabled.

**`Channel` record** — add `trackDelivery` (Boolean, nullable):

- `null` = use semantic default (true for BARRIER/COLLECT, false for others)
- `true` = explicit opt-in regardless of semantic
- `false` = explicit opt-out regardless of semantic

**`ChannelEntity`** — new column:

```sql
ALTER TABLE channel ADD COLUMN track_delivery BOOLEAN;
```

Nullable. Null means "use semantic default."

**`ChannelCreateRequest`** — add `trackDelivery(Boolean)` to builder. Null default.

**`OutboundMessage` record** — add `Long sequenceId`:

```java
public record OutboundMessage(
        UUID messageId,
        Long sequenceId,        // database sequence ID for cursor advancement
        String sender, MessageType type, String content,
        String correlationId, Long inReplyTo, ActorType senderActorType,
        List<ArtefactRef> artefactRefs, String target) {}
```

The existing `messageId` (UUID) serves as a correlation/idempotency key. The new `sequenceId` carries the `Message.id()` Long value needed for cursor advancement. All `OutboundMessage` construction sites must pass the sequence ID: `MessageService.dispatch()` has it from `messageStore.put(message)`, `DeliveryBatchExecutor.toOutbound()` has it from the `Message` record, and `ChannelGateway.deliverRemote()` receives it as a parameter.

**Resolution helper:**

```java
boolean isDeliveryTrackingEnabled(Channel ch) {
    if (ch.trackDelivery() != null) return ch.trackDelivery();
    return ch.semantic() == ChannelSemantic.BARRIER
        || ch.semantic() == ChannelSemantic.COLLECT;
}
```

All five semantics have defined defaults:

| Semantic | Default | Rationale |
|----------|---------|-----------|
| BARRIER | On | Coordination depends on knowing who received the gate command |
| COLLECT | On | Fan-in aggregation depends on knowing contributors received the prompt |
| APPEND | Off | General conversation; delivery tracking is overhead without coordination benefit |
| EPHEMERAL | Off | Fire-and-forget by design |
| LAST_WRITE | Off | Single-value blackboard; no coordination semantics |

Any semantic can be overridden via explicit `channel.trackDelivery`.

**Three-way diagnostic from the two cursors:**

| Condition | Meaning | Remediation |
|-----------|---------|-------------|
| `latestMessageId > lastDeliveredMessageId` | Not delivered | Retry / check transport |
| `lastDeliveredMessageId > lastReadMessageId` | Delivered, not processed | Wait or escalate |
| `lastReadMessageId >= latestMessageId` | Fully caught up | No action |

For BARRIER and COLLECT channels where messages are deleted after delivery: the diagnostic is meaningful during the in-progress period (between message dispatch and agent pull). After a successful delivery cycle, messages are deleted and the channel is empty until the next round. `latestMessageId` refers to the highest message ID currently in the channel — after deletion this may be null or an EVENT message ID. The "fully caught up" state is implicitly satisfied when no undelivered messages remain.

Cursor advancement in `check_messages` happens **before** message deletion in the BARRIER/COLLECT paths, ensuring the delivery record is captured before messages are removed.

**Migrations:**

- **V40:** `ALTER TABLE channel ADD COLUMN track_delivery BOOLEAN`
- **V41:** `ALTER TABLE channel_membership ADD COLUMN last_delivered_message_id BIGINT`

### 2. Store layer

**`ChannelMembershipStore`** — new methods:

```java
void updateLastDeliveredMessageId(UUID channelId, String memberId, Long messageId);
void advanceDeliveredCursorForMembers(UUID channelId, Set<String> memberIds, Long messageId);
```

Both are forward-only — implementation advances only if `messageId > current`.

`updateLastDeliveredMessageId` is for per-participant transports (A2A SSE, WebSocket, `check_messages` pull).

`advanceDeliveredCursorForMembers` is for transports where one post reaches a known set of participants (e.g., external platform backends). The caller provides the specific member IDs to advance — not all channel members. Single UPDATE with WHERE clause instead of N individual updates.

**Reactive parity:** Deferred. The `issue-384-retire-reactive` branch is in progress locally. Adding new reactive API surface while the reactive stack is being evaluated for retirement is premature. If the reactive retirement does not proceed, reactive parity methods can be added as a follow-up.

**InMemory implementations** — blocking in `persistence-memory`.

**Contract tests** — new test methods in `ChannelMembershipStoreContractTest`:
- Forward-only advancement (second call with lower ID is a no-op)
- `advanceDeliveredCursorForMembers` advances specified members of a channel
- Null → first value advancement
- Idempotent repeated calls with same ID

### 3. Cursor advancement hooks

Advancement lives **inside each transport's delivery path**, not centralized. Only the transport knows whether delivery succeeded and which participant received it.

The hook location depends on the backend's `DeliveryGuarantee`:

**AT_LEAST_ONCE backends — via `DeliveryBatchExecutor.deliverBatch()`:**

Backends declaring `DeliveryGuarantee.AT_LEAST_ONCE` are skipped by `fanOut()` and delivered through the delivery pump (`DeliveryService` → `DeliveryBatchExecutor`). `deliverBatch()` is already `@Transactional`, reads messages from the `DeliveryCursor`, and has access to the full `Message` record (including `Long id`). This is the natural hook point for cursor advancement.

After `backend.post(ref, outbound)` succeeds in `deliverBatch()`, advance the delivery cursor:

| Backend | Guarantee | Advancement method | Rationale |
|---------|-----------|-------------------|-----------|
| `connector-human` | AT_LEAST_ONCE | `advanceDeliveredCursorForMembers(channelId, platformMemberIds, sequenceId)` | One post reaches all external platform members |
| `slack-bot` | AT_LEAST_ONCE | `advanceDeliveredCursorForMembers(channelId, platformMemberIds, sequenceId)` | One post reaches all Slack members |
| `openclaw` | AT_LEAST_ONCE | `updateLastDeliveredMessageId(channelId, consumerId, sequenceId)` | Per-consumer webhook delivery |

For `connector-human` and `slack-bot`, the caller must resolve `platformMemberIds` — the set of channel members whose delivery path is the external platform. This excludes agent members (whose delivery path is `check_messages`) and members on other backends (A2A, WebSocket).

**Member-to-backend resolution:** Each `ChannelBackend` declares its served actor type via `actorType()` (e.g., `ConnectorChannelBackend` returns `ActorType.HUMAN`, `A2AChannelBackend` returns `ActorType.AGENT`). Each member's actor type is resolvable via `ActorTypeResolver.resolve(memberId)` — a static utility already used in `DeliveryBatchExecutor.toOutbound()`. The resolution is:

```java
Set<String> platformMemberIds = channelMembershipStore.findByChannel(channelId)
    .stream()
    .filter(m -> ActorTypeResolver.resolve(m.memberId()) == backend.actorType())
    .map(ChannelMembership::memberId)
    .collect(toSet());
```

This assumes one backend per actor type per channel, which matches the current architecture (a channel has at most one `HumanParticipatingChannelBackend`). If multi-backend-per-actor-type channels become needed, a backend consumer registry would replace this resolution.

**BEST_EFFORT push backends — via `A2AResource.streamTask()`:**

The A2A backend is the only BEST_EFFORT push backend. `ChannelGateway.fanOut()` dispatches A2A via `Thread.ofVirtual().start()` → `A2AChannelBackend.post()`, but `post()` merely enqueues the `OutboundMessage` to a `LinkedBlockingQueue` via registered SSE consumers — it does not deliver to the end participant. True delivery happens in `A2AResource.streamTask()` when `sink.send()` writes the SSE frame to the client.

Cursor advancement hooks into `streamTask()`, not the fanOut virtual thread:

1. **Identity resolution at stream setup:** `streamTask()` already reads the task's message history in a `QuarkusTransaction.requiringNew()` block. At this point, resolve the consumer's `memberId` from the task context — the sender of the initial message in the correlation set is the SSE consumer (the external agent that initiated the task).

2. **Advancement on delivery:** After each successful `sink.send()` of a non-keepalive message, advance the cursor using `QuarkusTransaction.requiringNew()` (already proven in `streamTask()`'s existing transactional reads, running on `@RunOnVirtualThread` with full CDI context):

```java
if (isDeliveryTrackingEnabled(channel)) {
    QuarkusTransaction.requiringNew().run(() ->
        channelMembershipStore.updateLastDeliveredMessageId(
            channelId, consumerMemberId, msg.sequenceId()));
}
```

| Backend | Guarantee | Advancement method | Hook point | Rationale |
|---------|-----------|-------------------|------------|-----------|
| `a2a` | BEST_EFFORT | `updateLastDeliveredMessageId(channelId, consumerId, sequenceId)` | `A2AResource.streamTask()` after `sink.send()` | Advances at actual delivery (SSE frame sent), not at enqueue |
| `qhorus-internal` | N/A (skipped in fanOut) | N/A | N/A | Agents pull via MCP; cursor advances in `check_messages` |

This avoids `QuarkusTransaction.requiringNew()` in unmanaged virtual threads (`Thread.ofVirtual().start()` closures have no CDI context). `streamTask()` runs on `@RunOnVirtualThread` — a Quarkus-managed virtual thread with proper CDI context propagation — where `QuarkusTransaction.requiringNew()` is already used and proven.

**Guard:** Before any advancement, the delivery code checks `isDeliveryTrackingEnabled(channel)`. If false, no store call.

**Observer transports — `MessageObserver` implementations:**

Observers fire **before the enclosing transaction commits** (documented in `MessageObserver` Javadoc). Cursor advancement inside an observer's `onMessage()` path would run pre-commit — if the transaction rolls back, the cursor may be advanced for a message that was never persisted.

To handle this: cursor advancement for observers uses `TransactionSynchronization.afterCompletion()` — the observer records the delivery event (connection ID → member ID mapping, message sequence ID), and a post-commit callback advances the cursor only if the transaction committed. This pattern already exists in `MessageService.dispatch()` for delivery signalling.

- **WebSocket observer:** After frame sent, record the delivery for post-commit cursor advancement. **Requires adding member identity tracking to `WebSocketConnectionRegistry`** — the current registry maps `channelId → Set<WebSocketConnection>` with no member identity. `subscribe()` must gain a `memberId` parameter, and the registry must maintain a `connection → memberId` mapping. This is new infrastructure.
- **Webhook observer:** After HTTP 2xx received, record the delivery. If registration carries a member association, advance per-participant; otherwise skip (no member identity to advance).
- **Kafka observer:** Not applicable. `KafkaMessageObserver` is a LOCAL-scoped observer that publishes message events to a Kafka topic for external consumers (system integration). Kafka consumers are not channel members with membership records — delivery tracking does not apply.

**Pull path — `check_messages` and `wait_for_reply`:**

After the query returns messages, advance the delivery cursor for the calling agent. Details in Section 4.

### 4. `check_messages` and `wait_for_reply` delivery advancement

Both MCP pull paths gain a side effect when delivery tracking is enabled.

**`check_messages`:**

Each semantic variant (`checkMessagesAppend`, `checkMessagesBarrier`, `checkMessagesCollect`, `checkMessagesEphemeral`) returns a `CheckResult` with messages and a `lastId`. Before returning (and before any message deletion for BARRIER/COLLECT), if tracking is enabled, advance the cursor.

**Guard conditions:**
- `isDeliveryTrackingEnabled(ch)` = true
- `readerInstanceId` is non-null (anonymous checks don't advance)
- `lastId > 0` (empty result = nothing to advance)

**Semantic:** "You asked for the messages, that counts as delivery." No opt-out parameter. An agent calling `check_messages` has received the messages — they're in the response. If a "peek without advancing" operation is needed later, adding `mark_delivered=false` is backward-compatible.

**Transaction boundary:** `checkMessages()` is already `@Transactional`. The cursor update happens in the same transaction as the message query.

**Ordering for BARRIER/COLLECT:** Cursor advancement happens before `messageStore.deleteNonEvent()`. The sequence is: (1) query messages, (2) advance delivery cursor to `lastId`, (3) delete messages. This ensures the delivery record is captured before messages are removed.

**`wait_for_reply`:**

`wait_for_reply` is a separate code path from `check_messages` — it polls `CommitmentStore.findByCorrelationId()` and `MessageService.findResponseByCorrelationId()` / `findDoneByCorrelationId()` in a loop. It does not delegate to `check_messages`.

When `wait_for_reply` finds a matching RESPONSE or DONE message and returns it, advance the delivery cursor for the calling agent to that message's sequence ID. Guard conditions:
- `isDeliveryTrackingEnabled(ch)` = true
- Instance ID is available from the calling context
- The matched message has a valid sequence ID

**Transaction boundary:** `wait_for_reply` polls outside a long-running transaction. The cursor advancement should use a dedicated transaction for the update (same pattern as the poll's message lookups).

### 5. Watchdog enrichments

**`BARRIER_STUCK` — delivery-aware diagnostics:**

For each missing contributor, query `ChannelMembershipStore.find(channelId, contributorId)` and compare `lastDeliveredMessageId` against the channel's latest message ID:

- `lastDeliveredMessageId` is null or less than the latest COMMAND → contributor marked as "not delivered"
- `lastDeliveredMessageId` covers the COMMAND but no contribution → contributor marked as "delivered, not responded"

`BarrierStuckContext` gains two new fields:

```java
public record BarrierStuckContext(
        UUID channelId, String channelName,
        List<String> missingContributors,  // existing — union of both
        List<String> notDelivered,         // new — transport issue
        List<String> deliveredNoResponse,  // new — agent issue
        long elapsedSeconds) implements AlertContext { ... }
```

This is a **breaking change** to the record's canonical constructor — all call sites constructing `BarrierStuckContext` must be updated to pass the two new fields. `missingContributors` remains as the union of both lists for callers that don't need the split.

**`CONVERSATION_STALL` — delivery context:**

For each stalled correlation, check whether the obligor's `lastDeliveredMessageId` covers the COMMAND message. Add to `ConversationStallContext`:

```java
public record ConversationStallContext(
        UUID channelId, String channelName,
        int stalledCount, List<String> correlationIds,
        long stalledSeconds,
        Boolean deliveryConfirmed) implements AlertContext { ... }
```

`deliveryConfirmed` has three states:
- `true` — obligor received the COMMAND (delivery cursor past the COMMAND's message ID)
- `false` — obligor has NOT received the COMMAND (delivery cursor behind the COMMAND's message ID, tracking IS enabled)
- `null` — delivery status unknown (tracking not enabled for this channel, or no membership record)

The distinction matters for watchdog remediation: `false` (not delivered) suggests a transport issue worth retrying; `null` (unknown) means the watchdog cannot determine delivery status and should fall back to existing heuristics.

**`DELIVERY_LAG` — out of scope.** Separate follow-up issue depending on #376. New condition type, threshold semantics, evaluation cadence — distinct from enriching existing conditions.

### 6. MCP tool surface

**`create_channel`** — gains optional `track_delivery` parameter (Boolean). Null = semantic default. Passed through to `ChannelCreateRequest`.

**`get_channel` / `list_channels`** — `ChannelDetail` gains `trackDelivery` field showing effective state (resolved, not raw — so callers see `true` for BARRIER channels even when the column is null).

**`set_delivery_tracking`** — new tool to set or reset tracking on an existing channel:

```
set_delivery_tracking(channel, tracking)
```

Takes UUID-or-name channel reference and `Boolean` (nullable). Updates `channel.trackDelivery`. Follows the UUID-first service pattern per `mcp-tool-channel-resolution-boundary` protocol.

- `true` = explicit opt-in regardless of semantic
- `false` = explicit opt-out regardless of semantic
- `null` (or omitted) = revert to semantic default

**Cursor initialization on enablement:** When tracking transitions from disabled to enabled on a channel that already has messages, initialize all members' `lastDeliveredMessageId` to the channel's current latest message ID. This establishes a "start tracking from now" baseline — consistent with the V41 migration principle ("tracking starts from next message after feature is enabled"). Without initialization, the watchdog would immediately flag every member as having undelivered messages for the channel's entire message history.

The initialization uses `advanceDeliveredCursorForMembers(channelId, allMemberIds, latestMessageId)` — the same forward-only method used by delivery hooks. If a member already has a cursor value (from a previous enablement cycle), the forward-only guard ensures no regression.

**No other new tools.** Delivery tracking is transparent — a side effect of existing operations (`check_messages`, `wait_for_reply`, delivery pump, observer delivery), not a new API surface.

## Out of Scope

- **`DELIVERY_LAG` watchdog condition** — net-new condition type (#377). Depends on #376.
- **Platform delivery SPI integration** — qhorus messages are not the right consumer (see rationale above). Decision tracked in #378.
- **Per-message delivery status queries** — "which participants received message #42?" Could be derived from cursor comparison but no dedicated tool in this issue (#379). Depends on #376.
- **Retry logic for failed deliveries** — the existing `DeliveryService` retry/reconciliation infrastructure handles backend-level retries. Per-participant retry for push failures is future work (#380). Depends on #376.

## Testing Strategy

**Unit tests (CDI-free):**
- `isDeliveryTrackingEnabled()` for all five semantics × explicit-override combinations
- Forward-only cursor advancement semantics
- `advanceDeliveredCursorForMembers` batch advancement

**Store contract tests:**
- `updateLastDeliveredMessageId` forward-only
- `advanceDeliveredCursorForMembers` advances specified members
- Null → first value
- Idempotent repeated calls

**Integration tests (`@QuarkusTest`):**
- `check_messages` advances delivery cursor when tracking enabled, skips when disabled
- `check_messages` with null `reader_instance_id` does not advance
- `wait_for_reply` advances delivery cursor when returning a matched message
- `DeliveryBatchExecutor.deliverBatch()` advances cursor for AT_LEAST_ONCE backends on tracked channels
- `A2AResource.streamTask()` advances cursor after SSE frame sent on tracked channels
- WebSocket observer cursor advancement via `TransactionSynchronization.afterCompletion()` (post-commit callback, member identity lookup from registry)
- Webhook observer cursor advancement (HTTP 2xx → cursor advance, member association)
- Observer transaction rollback does NOT advance cursor (negative test)
- `BARRIER_STUCK` watchdog produces `notDelivered` / `deliveredNoResponse` split
- `CONVERSATION_STALL` watchdog populates `deliveryConfirmed`
- Channel creation with explicit `track_delivery` override
- `set_delivery_tracking` toggle on existing channel
- `set_delivery_tracking` with `null` reverts to semantic default
- `set_delivery_tracking` enabling on channel with existing messages initializes member cursors to latest message ID
- Cursor advancement before message deletion in BARRIER/COLLECT paths

**Migration test:**
- `FlywayMigrationSchemaTest` extended to verify V40 and V41 produce correct schema
