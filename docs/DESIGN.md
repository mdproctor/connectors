# casehub-connectors — Design

## Purpose

Outbound and inbound message connector library for the casehubio platform.
Provides a `Connector` CDI SPI (outbound) and `InboundConnector` /
`WebhookInboundConnector` SPIs (inbound) with built-in implementations for
Slack, Teams, Twilio SMS, WhatsApp, and email. Callers decide when and what to
send; this library handles delivery in both directions.

---

## Design Principles

**CDI-native.** Connectors are plain `@ApplicationScoped` CDI beans. Discovery,
lifecycle, and injection use standard CDI — no registry, no factory, no custom
wiring.

**Standard library HTTP.** All channel implementations use `java.net.http.HttpClient`
from the JDK. This keeps the core module's dependency footprint to zero — nothing
beyond the JVM is required to embed it.

**Direct REST implementations.** Each channel is implemented against its own REST
API. This makes each connector self-contained and auditable — the full delivery
path is visible in a single class.

**Minimal scope.** This library does delivery only. No routing, scheduling,
templating, or retry orchestration. Callers own those concerns.

**CDI event bus for inbound.** Inbound transport is decoupled from processing via
synchronous `Event<InboundMessage>`. Connectors deliver a message and return immediately
— they have no knowledge of what happens next. Observers own their dispatch strategy
and must not block the CDI fire for longer than Slack's 3-second retry deadline.

---

## Module Structure

| Module | Artifact | Purpose |
|--------|----------|---------|
| `core` | `casehub-connectors-core` | Outbound SPI + Slack, Teams, Twilio SMS, WhatsApp; inbound SPIs + `InboundConnectorService` |
| `webhook` | `casehub-connectors-webhook` | Webhook inbound connectors (Slack, Teams, WhatsApp, Twilio SMS) + `WebhookRouter` JAX-RS |
| `email` | `casehub-connectors-email` | Email outbound via `quarkus-mailer` |
| `email-inbound` | `casehub-connectors-email-inbound` | Email inbound via IMAP polling (`EmailInboundConnector`) + `EmailInboundAccountProvider` SPI |

Each module carries only the dependencies it needs. `email` and `email-inbound` are
separate because `quarkus-mailer` (SMTP) and `angus-mail` (IMAP) have no shared
infrastructure — bundling them would force each dependency on users who need only one.
`webhook` is separate because `quarkus-rest` is unnecessary for outbound-only deployments.

---

## SPI

```java
public interface Connector {
    String id();
    void send(ConnectorMessage message);
}
```

`id()` returns the connector's type string (e.g. `"slack"`, `"twilio-sms"`).
Callers use this to select the right connector at runtime.

`send()` delivers the message. **Contract:**
- Must not throw unchecked exceptions — log failures and return.
- May block briefly (one HTTP call) but must complete within its configured timeout.
- Must be thread-safe — it may be called from multiple threads concurrently.

**Custom connectors:** implement `Connector` as an `@ApplicationScoped` CDI bean.
It will be discovered automatically alongside the built-in implementations.

---

## Inbound SPI

Two distinct types handle inbound transports — not a unified interface — because their
lifecycle semantics differ. Pull-based connectors (IMAP, polling) have an active
lifecycle; webhook-based connectors are passive (their lifecycle is the JAX-RS endpoint).

### `InboundConnector` — pull-based transports

```java
public interface InboundConnector {
    String id();
    void start(InboundMessageSink sink);
    void stop();
}
```

`start()` is called at Quarkus startup; the connector begins polling and calls `sink.receive()`
when messages arrive. `stop()` is called at shutdown.

### `WebhookInboundConnector` — webhook-based transports

```java
public abstract class WebhookInboundConnector {
    public abstract String id();
    public abstract WebhookResult handle(WebhookRequest request);
}
```

Does **not** implement `InboundConnector`. No lifecycle methods — the JAX-RS
`WebhookRouter` dispatches `GET|POST /connectors/{id}/webhook` to `handle()`. The
`WebhookResult` sealed type (`Delivered`, `Challenged`, `Ignored`, `Unauthorized`)
forces exhaustive handling in the router.

Both types deliver via `InboundConnectorService.receive()` — the single CDI
`Event<InboundMessage>` bus. Events are fired via `Event.fireAsync()` (not `fire()`);
observers **must** use `@ObservesAsync InboundMessage`. Synchronous `@Observes` observers
will not receive events.

**Built-in webhook implementations (`webhook` module):**

| ID | Platform | Signature | `metadata` keys |
|----|----------|-----------|-----------------|
| `slack-inbound` | Slack Events API | HMAC-SHA256; url_verification; replay prevention | `workspace-id` (from `team_id`) |
| `teams-inbound` | Teams Outgoing Webhooks | HMAC-SHA256 with base64-decoded key | _(none)_ |
| `whatsapp-inbound` | WhatsApp Business API | GET challenge + POST HMAC-SHA256 | `message-id` (from message `id` field) |
| `twilio-sms-inbound` | Twilio SMS | HMAC-SHA1 over URL + sorted params (Twilio's algorithm) | `message-sid` (from `MessageSid` param) |

**Security:** All HMAC comparisons use `MessageDigest.isEqual()` (constant-time).
`Unauthorized` from POST → HTTP 200 (suppress retry storms); from GET → HTTP 403
(admin console setup failure must be visible).

**Built-in pull implementations (`email-inbound` module):**

| ID | Transport | Discovery |
|----|-----------|-----------|
| `email-inbound` | IMAP polling via `EmailInboundAccountProvider` SPI | `@DefaultBean` reads from MP Config; custom providers supply multi-account or DB-backed configs |

`EmailInboundConnector` polls each configured IMAP account on a dedicated single-threaded
daemon executor. `connectorId` is always `"email-inbound"` (type discriminator); per-account
identity is in `InboundMessage.metadata["account-id"]`. Delivery is at-least-once — the SEEN
flag is set per-message in a `finally` block, but a JVM shutdown mid-flag can cause redelivery.
Observers must be idempotent.

---

## Data Model

```java
public record ConnectorMessage(
        String destination,
        String title,
        String body,
        Map<String, String> attributes) { }
```

| Field | Type | Semantics |
|-------|------|-----------|
| `destination` | `String` | Where to send: webhook URL, E.164 phone number, or email address |
| `title` | `String?` | Subject or card title — connector-specific; null uses a connector default |
| `body` | `String` | Main text content |
| `attributes` | `Map<String,String>` | Connector-specific extras (e.g. `templateName` for WhatsApp); unrecognised keys are silently ignored |

**Per-connector field semantics:**

| Connector | `destination` | `title` | `body` |
|-----------|--------------|---------|--------|
| Slack | Webhook URL | Card header | Message text |
| Teams | Webhook URL | Card title | Message text |
| Twilio SMS | E.164 number (e.g. `+447700900000`) | Ignored | SMS text (max 1600 chars) |
| WhatsApp | E.164 number | Ignored | Message text |
| Email | Email address | Subject (`"Notification"` if blank) | Plain-text body |

Convenience constructors are provided for the common cases (no attributes; body only).

### `InboundMessage`

```java
public record InboundMessage(
        String connectorId,
        String externalSenderId,
        String externalChannelRef,
        String content,
        Instant receivedAt,
        Map<String, String> metadata) { }
```

| Field | Semantics |
|-------|-----------|
| `connectorId` | Source connector type id (e.g. `"slack-inbound"`, `"email-inbound"`) — observers filter on this; never an account-level id |
| `externalSenderId` | Who sent it — Slack user ID, E.164 phone number, email address (`InternetAddress.getAddress()`); `""` if absent/unparseable |
| `externalChannelRef` | Where it came from — Slack channel ID, WhatsApp destination number, email recipient address; falls back to account username for email when `To:` is absent |
| `content` | Message text. **Text-only in v1** — media messages yield `content` = media URL or empty string; HTML-only emails yield raw HTML |
| `receivedAt` | Server-assigned arrival time: `getReceivedDate()` → `getSentDate()` → `Instant.now()` (fallback chain) |
| `metadata` | Connector-specific extras. Keys are present only when the underlying header/field exists. `"account-id"` is always present for multi-account connectors (e.g. email inbound). |

---

## Configuration

Slack and Teams require no application configuration — the webhook URL is passed
as `destination` at call time.

**Twilio SMS:**

| Property | Description |
|----------|-------------|
| `casehub.connectors.twilio.account-sid` | Twilio Account SID (`ACxxx...`) |
| `casehub.connectors.twilio.auth-token` | Twilio Auth Token |
| `casehub.connectors.twilio.from` | Sender phone number (E.164) |

If `account-sid` is blank, `send()` logs a warning and no-ops — the connector
remains active but inactive, safe to include in a deployment that doesn't use SMS.

**WhatsApp:**

| Property | Description |
|----------|-------------|
| `casehub.connectors.whatsapp.api-token` | Meta Cloud API bearer token |
| `casehub.connectors.whatsapp.phone-number-id` | WhatsApp Business phone number ID |

If `api-token` is blank, `send()` logs and no-ops (same pattern as Twilio).

**Email** — configure `quarkus-mailer` as normal:

| Property | Description |
|----------|-------------|
| `quarkus.mailer.from` | Sender address |
| `quarkus.mailer.host` | SMTP host |
| `quarkus.mailer.port` | SMTP port (typically 587) |
| `quarkus.mailer.username` | SMTP credentials |
| `quarkus.mailer.password` | SMTP credentials |

Set `quarkus.mailer.mock=true` (the default test-profile value) to intercept
emails in tests without a real SMTP server.

**Email inbound (`email-inbound` module):**

| Property | Default | Description |
|----------|---------|-------------|
| `casehub.connectors.email-inbound.host` | `""` | IMAP server host. Blank → connector is inactive (no polling threads started) |
| `casehub.connectors.email-inbound.port` | `993` | IMAP port |
| `casehub.connectors.email-inbound.tls` | `true` | Use implicit SSL/TLS (IMAPS) |
| `casehub.connectors.email-inbound.username` | `""` | IMAP username |
| `casehub.connectors.email-inbound.password` | `""` | IMAP password |
| `casehub.connectors.email-inbound.folder` | `"INBOX"` | Mailbox folder to poll |
| `casehub.connectors.email-inbound.poll-interval-seconds` | `60` | Seconds between polls |

For multi-account deployments, implement `EmailInboundAccountProvider` as an
`@ApplicationScoped` CDI bean (without `@DefaultBean`) to supply multiple accounts
programmatically.

---

## Usage

Inject `ConnectorService` and route by id:

```java
@ApplicationScoped
public class NotificationService {

    @Inject
    ConnectorService connectors;

    public void notify(String channel, String destination, String title, String body) {
        connectors.send(channel, new ConnectorMessage(destination, title, body));
    }
}
```

`channel` is one of `"slack"`, `"teams"`, `"twilio-sms"`, `"whatsapp"`, `"email"`,
or the id of a custom connector registered in the CDI context.

`send()` throws `IllegalArgumentException` if the channel is not registered — the
message includes the available ids, making misconfiguration straightforward to diagnose.

Use `supports()` to guard before sending when the channel id comes from user input:

```java
if (connectors.supports(channel)) {
    connectors.send(channel, message);
}
```

Use `ids()` to enumerate available channels (e.g. for UI validation or capability checks):

```java
Set<String> available = connectors.ids();
```
