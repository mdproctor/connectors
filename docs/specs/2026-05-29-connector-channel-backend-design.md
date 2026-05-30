# Design: ConnectorChannelBackend — Inbound Message Bridge via HumanParticipatingChannelBackend

**Date:** 2026-05-29 (revised after spec review)
**Issue:** casehubio/connectors#6
**Repos affected:** casehub-qhorus (primary), casehub-connectors (ICS change only — see D6)

---

## Problem

`InboundConnectorService` fires a CDI `Event<InboundMessage>` when any connector receives a
message. Nothing currently consumes that event — received messages go nowhere. connectors#6 wires
inbound connector traffic into Qhorus channels so agents can process it.

Two gaps were identified:

1. **No API-level entry point** — `ChannelGateway.receiveHumanMessage()` is the correct Qhorus
   entry point but lives in qhorus-runtime. A bridge depending only on qhorus-api cannot call it.
2. **No channel-to-connector mapping** — the `Channel` entity has no concept of which external
   connector it is bound to.

The `HumanParticipatingChannelBackend` SPI is the correct architectural home: it handles both
inbound delivery (via `receiveHumanMessage`) and outbound fan-out (via `post`), following the
pattern established by `ClaudonyChannelBackend` and `OpenClawChannelBackend`. (The similarity to
those backends is limited to the `@Observes ChannelInitialisedEvent` registration sequence —
`ConnectorChannelBackend` is stateful, conditional, and does DB lookups at registration time;
`ClaudonyChannelBackend` does none of these.)

---

## Key Decisions

### D1: Channel granularity — per-conversation, not per-endpoint

`InboundMessage.externalChannelRef` consistently identifies our own endpoint (our Twilio number,
our inbox, our Meta phone number ID). It does NOT identify the conversation partner. If channels
were per-endpoint, all messages from all senders would land in one channel, and the `post()`
callback would have no way to know which human to reply to without a database query inside a
virtual-thread fan-out callback. This is architecturally broken.

**Decision:** one Qhorus channel per conversation partner. For Slack the conversation is the Slack
channel (multi-participant); for SMS/WhatsApp/Email the conversation is with one specific sender.

**Constraint (D5):** a channel with a connector binding cannot also have any other
`HumanParticipatingChannelBackend` registered. `ChannelGateway.registerBackend()` enforces this
with `DuplicateParticipatingBackendException`. Operators must not register other participating
backends on connector-bound channels.

### D2: Pre-provisioned channels only in v1

Channels with connector bindings must be created by an operator before conversations begin. If an
`InboundMessage` arrives and no channel is found, a WARN is logged, a Micrometer counter is
incremented, and the message is silently discarded. Auto-creation on first contact is deferred to
v2 (requires governance decisions: semantic, ACL, rate limit defaults).

### D3: Bridge lives in qhorus repo, new module

The bridge must call `ChannelGateway.registerBackend()`, `ChannelGateway.receiveHumanMessage()`,
and `ChannelService.findByConnectorKey()` — all qhorus-runtime classes. Placing it in the
connectors repo would introduce a library-to-runtime cross-repo dependency. Instead: new module
`casehub-qhorus-connector-backend` in the qhorus repo, listed after `runtime` in the root pom.

The `cross-foundation-bridge-module-placement` protocol is updated to note that bidirectional SPI
bridges requiring runtime access are placed in the repo that owns the runtime.

### D4: No changes to existing inbound connectors

`externalChannelRef` and `externalSenderId` in `InboundMessage` remain as-is. The bridge derives
the lookup key per connector type internally (see Inbound Key Derivation).

### D5: Connector-bound channels cannot have other HumanParticipatingChannelBackends

Documented above as a hard platform constraint — not enforced by the bridge, but callers must
understand it. Tooling that creates connector-bound channels should expose this constraint.

### D6: InboundConnectorService switches to fireAsync()

`InboundConnectorService.receive()` currently calls `messageEvent.fire()` (synchronous). The
bridge uses `@ObservesAsync InboundMessage`, which CDI 2.0 specifies is only notified by
`Event.fireAsync()`. A synchronous `fire()` will never invoke `@ObservesAsync` observers.

**Fix:** change the CDI constructor in ICS to use `fireAsync()`:

```java
InboundConnectorService(@All List<InboundConnector> pullConnectors,
                        Event<InboundMessage> messageEvent) {
    this(pullConnectors, msg -> messageEvent.fireAsync(msg)
            .exceptionally(ex -> {
                LOG.severe("Async InboundMessage dispatch failed: " + ex.getMessage());
                return null;
            }));
}
```

The package-private test constructor (`Consumer<InboundMessage>`) is unchanged — it bypasses CDI
and is unaffected. Both `InboundMessageCapture` test beans (webhook module, email-inbound module)
must change from `@Observes` to `@ObservesAsync`. Their `@QuarkusTest` assertion points must use
`CompletableFuture` capture with a timeout (see Testing).

The ICS Javadoc already states "Observers must not perform blocking I/O inline" — `fireAsync()` is
the correct contract. This change is safe: there are zero existing `@Observes InboundMessage`
production consumers.

---

## Data Model Changes (casehub-qhorus)

### ChannelConnectorBinding entity (new table, not columns on Channel)

Rather than adding four nullable columns to `Channel` (the "all-four-or-none" invariant enforced
only in application code), a separate `ChannelConnectorBinding` entity makes the invariant
structural: a row exists or it doesn't.

```java
@Entity
@Table(name = "channel_connector_binding",
       uniqueConstraints = {
           @UniqueConstraint(name = "uq_binding_key",
               columnNames = {"inbound_connector_id", "external_key"})
       })
public class ChannelConnectorBinding extends PanacheEntityBase {

    @Id
    public UUID channelId;          // FK to channel.id; presence IS the binding invariant

    @Column(name = "inbound_connector_id", nullable = false)
    public String inboundConnectorId;   // e.g. "slack-inbound", "twilio-sms-inbound"

    @Column(name = "external_key", nullable = false)
    public String externalKey;          // see Inbound Key Derivation table

    @Column(name = "outbound_connector_id", nullable = false)
    public String outboundConnectorId;  // e.g. "slack", "twilio-sms"

    @Column(name = "outbound_destination", nullable = false, length = 512)
    public String outboundDestination;  // Slack webhook URL, E.164 number, email address
}
```

`channelId` is the PK and also a FK to `channel.id`. The unique constraint on
`(inbound_connector_id, external_key)` enforces that no two channels can map to the same
connector + key pair (structural enforcement, not application-level).

### Flyway migration

```sql
CREATE TABLE channel_connector_binding (
    channel_id            UUID         NOT NULL,
    inbound_connector_id  VARCHAR(64)  NOT NULL,
    -- external_key holds the per-conversation identifier; 255 covers all email addresses
    -- per RFC 5321 (max local-part 64 + '@' + max domain 253 = 318; practical max ~254)
    external_key          VARCHAR(255) NOT NULL,
    outbound_connector_id VARCHAR(64)  NOT NULL,
    outbound_destination  VARCHAR(512) NOT NULL,

    CONSTRAINT pk_channel_connector_binding PRIMARY KEY (channel_id),
    CONSTRAINT fk_binding_channel FOREIGN KEY (channel_id) REFERENCES channel(id),
    CONSTRAINT uq_binding_key UNIQUE (inbound_connector_id, external_key)
);
```

### ChannelBindingStore (new interface + implementations)

```java
public interface ChannelBindingStore {
    Optional<ChannelConnectorBinding> findByChannelId(UUID channelId);
    Optional<ChannelConnectorBinding> findByKey(String inboundConnectorId, String externalKey);
    void put(ChannelConnectorBinding binding);
    void delete(UUID channelId);
}
```

JPA implementation (`JpaChannelBindingStore`) and in-memory implementation
(`InMemoryChannelBindingStore` in qhorus-testing) follow the existing store pattern.

### ChannelService additions

```java
// Lookup by connector key — used by the bridge on every inbound message
public Optional<Channel> findByConnectorKey(String inboundConnectorId, String externalKey) {
    return bindingStore.findByKey(inboundConnectorId, externalKey)
        .map(b -> channelStore.find(b.channelId).orElse(null));
}

// Create channel with optional connector binding
public Channel create(ChannelCreateRequest req) { ... }
```

**`ChannelCreateRequest` record** (collapses the existing 5-overload chain — overloads remain but
delegate to this record for new callers; full consolidation is a follow-up refactor):

```java
public record ChannelCreateRequest(
    String name, String description, ChannelSemantic semantic,
    String barrierContributors, String allowedWriters, String adminInstances,
    Integer rateLimitPerChannel, Integer rateLimitPerInstance, String allowedTypes,
    // connector binding — all four must be non-null together, or all null
    String inboundConnectorId, String externalKey,
    String outboundConnectorId, String outboundDestination
) {
    public ChannelCreateRequest {
        boolean hasBinding = inboundConnectorId != null;
        boolean allSet = inboundConnectorId != null && externalKey != null
                && outboundConnectorId != null && outboundDestination != null;
        if (hasBinding && !allSet) {
            throw new IllegalArgumentException(
                "Connector binding requires all four fields: inboundConnectorId, " +
                "externalKey, outboundConnectorId, outboundDestination");
        }
    }

    public boolean hasConnectorBinding() { return inboundConnectorId != null; }
}
```

`ChannelService.create(ChannelCreateRequest)` validates uniqueness of the connector key before
persisting:

```java
if (req.hasConnectorBinding()) {
    bindingStore.findByKey(req.inboundConnectorId(), req.externalKey()).ifPresent(existing -> {
        throw new IllegalStateException(
            "Connector binding already exists: " + req.inboundConnectorId()
            + " / " + req.externalKey() + " → channel " + existing.channelId);
    });
    // persist binding after channel persisted
}
```

### Cache staleness on binding update

The in-memory cache (see ConnectorChannelBackend below) is populated when `ChannelInitialisedEvent`
fires. That event fires at startup and on `ChannelService.create()` — but NOT on binding update.
If an operator changes `outboundDestination` (e.g., a Slack webhook URL rotates), the cache entry
is stale until the next restart.

**v1 constraint:** changing binding fields on an existing channel requires a service restart to
take effect. This must be visible in operator tooling and documented in `ChannelService`.

**Planned fix (v2):** fire `ChannelInitialisedEvent` on every binding update so the cache
refreshes automatically. The event infrastructure already exists; this is additive.

### ChannelDetail API

`ChannelDetail` gains a nullable nested `ConnectorBinding` record:

```java
public record ConnectorBinding(
    String inboundConnectorId, String externalKey,
    String outboundConnectorId, String outboundDestination) {}

// ChannelDetail gains:
ConnectorBinding connectorBinding  // null if no binding
```

**Binary break note:** `ChannelDetail` is a Java record; adding a field is binary-incompatible
with the existing canonical constructor. Callers that must be updated: all `new ChannelDetail(...)`
call sites in MCP tools (`QhorusDashboardService`, `ChannelResource`), Panache projections, and any
downstream repo that deserialises `ChannelDetail` from JSON. Audit all usages before merging.

---

## Inbound Key Derivation

| `InboundMessage.connectorId` | Lookup key | Source field | Rationale |
|------------------------------|-----------|--------------|-----------|
| `"slack-inbound"` | Slack channel ID | `externalChannelRef` | The channel IS the conversation |
| `"twilio-sms-inbound"` | Sender's E.164 number | `externalSenderId` | `externalChannelRef` = our number |
| `"whatsapp-inbound"` | Sender's phone number | `externalSenderId` | `externalChannelRef` = our phone ID |
| `"email-inbound"` | Sender's email address | `externalSenderId` | `externalChannelRef` = our inbox |
| any other | — | `externalChannelRef` | Safe fallback for future connectors |

`ConnectorKeyStrategy` is a small static class with an explicit per-connector-ID dispatch, not
reflection or configuration. The fallback for unknown connector IDs uses `externalChannelRef`.

---

## ConnectorChannelBackend

Single `@ApplicationScoped` bean. Implements `HumanParticipatingChannelBackend`.

### State

```java
private final ConcurrentHashMap<UUID, CacheEntry> bindingCache = new ConcurrentHashMap<>();

record CacheEntry(String inboundConnectorId, String externalKey,
                  String outboundConnectorId, String outboundDestination) {}
```

The cache is the sole data source for `post()`. No database access during fan-out.

### `backendId()`

Returns `"connector-human"`. Constant — used by `deregisterBackend()` in the idempotent
re-registration pattern.

### `actorType()`

Returns `ActorType.HUMAN`.

### Registration — `@Observes ChannelInitialisedEvent`

```
onChannelInitialised(event):
  binding = bindingStore.findByChannelId(event.channelId())
  if binding is absent: return
  bindingCache.put(event.channelId(), new CacheEntry(
      binding.inboundConnectorId, binding.externalKey,
      binding.outboundConnectorId, binding.outboundDestination))
  gateway.deregisterBackend(event.channelId(), BACKEND_ID)   // idempotent
  gateway.registerBackend(event.channelId(), this, "human_participating")
```

The deregister-then-register pattern is idempotent and safe for concurrent restarts (established by
`ClaudonyChannelBackend`). The DB lookup is one read per channel at startup; for small N
(connector-bound channels) this is acceptable. Document this startup cost in the Javadoc if N
grows large.

### Inbound routing — `@ObservesAsync InboundMessage`

```
onInboundMessage(msg):
  key = ConnectorKeyStrategy.deriveKey(msg)
  channel = channelService.findByConnectorKey(msg.connectorId(), key)
  if channel absent:
    log.warn("No channel bound to {}/{} — discarding", msg.connectorId(), key)
    inboundDiscardedCounter.withTag("connector_id", msg.connectorId()).increment()
    return
  gateway.receiveHumanMessage(
      new ChannelRef(channel.id, channel.name),
      new InboundHumanMessage(
          msg.externalSenderId(),
          msg.content(),
          msg.receivedAt(),
          msg.metadata(),
          null,    // correlationId
          null))   // inReplyTo
```

`@ObservesAsync` fires off the webhook/IMAP thread (requires D6 — ICS fireAsync). The `WARN` log
must include enough context for an operator to identify the unrouted sender.

**Micrometer counter:** `inbound_messages_discarded_total` tagged with `connector_id`. Registered
at `@PostConstruct`. No counter for successfully routed messages in v1 (channel-level message
counts come from Qhorus itself).

### `normaliser()`

Returns `null` — uses `DefaultInboundNormaliser` (type=QUERY, sender=`"human:" + externalSenderId`).
Per-connector normaliser customisation is deferred to v2.

### Outbound — `post(ChannelRef, OutboundMessage)`

```
post(channelRef, message):
  entry = bindingCache.get(channelRef.id())
  if entry == null:
    log.error("No cache entry for channel {} — outbound dropped", channelRef.id())
    return     // must not throw; fanOut() is non-fatal for external backends
  title = OutboundTitle.forConnector(entry.outboundConnectorId(), channelRef)
  try:
    connectorService.send(
        entry.outboundConnectorId(),
        new ConnectorMessage(entry.outboundDestination(), title, message.content()))
  catch IllegalArgumentException ex:
    log.error("Unknown outbound connector '{}' for channel {} — outbound dropped",
              entry.outboundConnectorId(), channelRef.id())
    // do not rethrow
```

**`OutboundTitle.forConnector()`** — per-connector title strategy:

| Outbound connector ID | Title |
|-----------------------|-------|
| `"email"` | `"Re: " + channelRef.name()` |
| `"slack"`, `"teams"` | `null` (no title concept in webhook payload) |
| `"twilio-sms"`, `"whatsapp"` | `null` (SMS has no subject) |
| any other | `null` |

This is a small static method, not a SPI. A SPI is overkill until a use case for custom titles
exists.

// TODO(v2): email threading — proper reply subjects should carry the original In-Reply-To
// Message-ID header, not just the channel name. Implement when per-connector normaliser
// lands (v2): the normaliser will have access to original message headers stored in metadata.

### `open()` / `close()`

`open()` is a no-op. Registration lifecycle is driven by `ChannelInitialisedEvent`.

`close()` removes the channel's entry from `bindingCache`:
```
close(channelRef):
  bindingCache.remove(channelRef.id())
```

Called by `ChannelGateway.closeChannel()` when a channel is deleted. Keeps the cache consistent.
In v2, if IMAP IDLE is implemented, `open()` would start an IDLE session per channel and `close()`
would tear it down (in addition to cache removal).

---

## Module Structure (casehub-qhorus)

### New module: `casehub-qhorus-connector-backend`

```xml
<artifactId>casehub-qhorus-connector-backend</artifactId>
<description>Optional bridge — ConnectorChannelBackend routes InboundMessage events into Qhorus
channels via HumanParticipatingChannelBackend. Activates by classpath presence.</description>

<dependencies>
  <dependency>casehub-qhorus-api</dependency>
  <dependency>casehub-qhorus-runtime</dependency>   <!-- ChannelGateway, ChannelService, ChannelBindingStore -->
  <dependency>casehub-connectors-core</dependency>  <!-- InboundMessage, ConnectorService, ConnectorMessage -->
  <dependency>micrometer-core (provided)</dependency>
  <dependency>jakarta.enterprise.cdi-api (provided)</dependency>
  <dependency>jboss-logging (provided)</dependency>
  <dependency>quarkus-junit5 (test)</dependency>
  <dependency>assertj-core (test)</dependency>
  <dependency>casehub-qhorus-testing (test)</dependency>
</dependencies>
```

Root `pom.xml` module order — `connector-backend` must follow `runtime` (it depends on it):

```xml
<modules>
  <module>api</module>
  <module>connectors</module>          <!-- api-only: WatchdogAlertEvent → ConnectorService -->
  <module>runtime</module>
  <module>connector-backend</module>   <!-- runtime-aware: InboundMessage ↔ ChannelGateway -->
  <module>deployment</module>
  <module>testing</module>
  <module>examples</module>
</modules>
```

Per `cross-repo-optional-dep-table-registration` protocol: register
`casehub-qhorus-connector-backend → casehub-connectors-core` in both the qhorus build order and
the Cross-Repo Dependency Map in `parent/docs/PLATFORM.md`.

---

## Examples

### Twilio SMS — binding a per-sender conversation

```java
channelService.create(ChannelCreateRequest.builder()
    .name("sms-alice")
    .description("Alice's SMS conversation")
    .semantic(ChannelSemantic.STANDARD)
    .inboundConnectorId("twilio-sms-inbound")
    .externalKey("+15551110000")          // Alice's number (externalSenderId)
    .outboundConnectorId("twilio-sms")
    .outboundDestination("+15551110000")  // same number for replies
    .build());
```

Alice texts our Twilio number → `TwilioSmsInboundConnector` fires:
```
InboundMessage(connectorId="twilio-sms-inbound",
               externalSenderId="+15551110000",   // Alice — the lookup key
               externalChannelRef="+14155552671", // our Twilio number
               content="I need help with my case")
```

Bridge: key = `externalSenderId` = "+15551110000" → finds "sms-alice" → `receiveHumanMessage()`
→ dispatched as QUERY, sender `"human:+15551110000"`.

When qhorus responds: `post()` → `connectorService.send("twilio-sms", ConnectorMessage("+15551110000", null, "We can help"))`.

### Slack — binding a channel

```java
channelService.create(ChannelCreateRequest.builder()
    .name("slack-support")
    .inboundConnectorId("slack-inbound")
    .externalKey("C123456")              // Slack channel ID (externalChannelRef)
    .outboundConnectorId("slack")
    .outboundDestination("https://hooks.slack.com/services/...")
    .build());
```

Bridge: key = `externalChannelRef` = "C123456" (Slack rule) → finds "slack-support" → routes.
Outbound: POST to incoming webhook URL.

---

## Testing

### Test infrastructure changes (casehub-connectors)

Both `InboundMessageCapture` beans must change to `@ObservesAsync`. `@QuarkusTest` assertions using
them must capture the result before asserting:

```java
// Pattern for async observer tests
@Inject InboundMessageCapture capture;

@Test
void inbound_message_is_delivered() throws Exception {
    inboundConnectorService.receive(testMessage);
    InboundMessage received = capture.nextMessage(2, TimeUnit.SECONDS); // CompletableFuture.get
    assertThat(received.content()).isEqualTo("hello");
}
```

`InboundMessageCapture` becomes:
```java
void observe(@ObservesAsync InboundMessage msg) {
    pending.complete(msg);  // or offer to a BlockingQueue for multiple-message tests
}
```

### Unit tests — `ConnectorChannelBackendTest` (mock-based, no Quarkus)

- `onChannelInitialised_registersBackend_whenBindingPresent` — binding in store → backend registered, cache populated
- `onChannelInitialised_skips_whenNoBinding` — no binding row → no registration, no cache entry
- `onChannelInitialised_isIdempotent` — second event for same channel: deregister+register, cache entry overwritten (not duplicated)
- `onChannelInitialised_capturesStaleBinding` — binding updated externally → cache holds old value until next restart (documents expected stale behaviour; prevents accidental "fix")
- `@Disabled("v2: cache not invalidated on binding update — enable when ChannelInitialisedEvent fires on update") cacheRefreshesAfterBindingUpdate` — assert that after `outboundDestination` changes on a bound channel, the next `post()` uses the new value without a restart; disabled until v2 fix lands; serves as regression harness
- `onInboundMessage_routesToReceiveHumanMessage_whenChannelFound`
- `onInboundMessage_logsWarn_andIncrementsCounter_whenNoChannelFound` — no exception thrown; assert counter incremented
- `post_sendsViaConnectorService_fromCache`
- `post_logsError_whenNoCacheEntry` — no exception thrown
- `post_logsError_whenConnectorServiceThrows` — `ConnectorService.send()` throws `IllegalArgumentException`; no exception propagated
- `close_removesCacheEntry`
- `create_withDuplicateBinding_throws` — `ChannelService.create()` with conflicting `(inboundConnectorId, externalKey)` throws

### Unit tests — `ConnectorKeyStrategyTest`

One test per connector type asserting the correct field is selected. Fallback test for unknown
connector ID uses `externalChannelRef`.

### Unit tests — `OutboundTitleTest`

- `email_returns_re_channelName`
- `slack_returns_null`
- `twilio_returns_null`
- `unknown_connector_returns_null`

### Integration test — `ConnectorChannelBackendIntegrationTest` (`@QuarkusTest`, in-memory stores)

- Full round-trip: create channel with binding → `ChannelInitialisedEvent` fires → send
  `InboundMessage` → assert `receiveHumanMessage()` called (with `CompletableFuture` synchronization)
  → simulate `fanOut()` → assert `ConnectorService.send()` called with correct destination and title
- Multi-channel routing: two channels with different bindings → each routes to correct backend
- Unknown sender: `InboundMessage` with no matching channel → no exception; counter incremented; WARN logged
- Duplicate binding: attempt to create second channel with same `(inboundConnectorId, externalKey)` → `IllegalStateException`

---

## Scope

### In v1 (this spec)

**casehub-connectors:**
- `InboundConnectorService`: change CDI constructor to `fireAsync()`; update Javadoc
- Both `InboundMessageCapture` test beans: `@ObservesAsync`; `CompletableFuture` capture pattern
- Existing `@QuarkusTest` assertions updated for async timing

**casehub-qhorus:**
- `ChannelConnectorBinding` entity + Flyway migration (new table + unique constraint)
- `ChannelBindingStore` interface + JPA + InMemory implementations
- `ChannelService.findByConnectorKey()`; `create(ChannelCreateRequest)`; binding uniqueness check; staleness Javadoc
- `ChannelCreateRequest` record with compact constructor validation
- `ChannelDetail` updated with nested `ConnectorBinding` record; all call sites updated
- `casehub-qhorus-connector-backend` module (pom, jandex)
- `ConnectorKeyStrategy` (static per-connector-type table)
- `OutboundTitle` (static per-connector-type title strategy)
- `ConnectorChannelBackend` (registration, inbound, outbound, close)
- Micrometer counter for discarded messages
- All unit and integration tests above
- Protocol update: `cross-foundation-bridge-module-placement`
- Cross-Repo Dependency Map update

### Deferred to v2

- Auto-channel creation on first contact (governance config required)
- Per-connector `InboundNormaliser` override (e.g., email subject → `correlationId`)
- `ChannelInitialisedEvent` on binding update (cache refresh without restart)
- IMAP IDLE integration (#9 — `open()`/`close()` lifecycle per channel)
- MCP tools for creating/updating connector bindings
- Full consolidation of `ChannelService.create()` overload chain into `ChannelCreateRequest`
