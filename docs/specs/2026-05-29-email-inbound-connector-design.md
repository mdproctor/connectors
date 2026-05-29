# Email Inbound Connector — Design Spec

**Issue:** casehubio/connectors#7  
**Branch:** issue-7-email-inbound-v1-polish  
**Date:** 2026-05-29  
**Rev:** 4 (post-review-3)

---

## Purpose

Add an email inbound connector that polls one or more IMAP mailboxes and delivers
received emails as `InboundMessage` CDI events via `InboundConnectorService`.

---

## Module Structure

A new `email-inbound` module, **separate from the existing `email` module**.

`quarkus-mailer` (SMTP, outbound) and `angus-mail` (Jakarta Mail, IMAP) have no
shared infrastructure. Putting them in the same module would force IMAP users to
configure `quarkus-mailer` (which stalls at augmentation time when unconfigured)
and force outbound-email users to carry an IMAP dependency. The separation mirrors
`webhook` (inbound) vs. outbound connectors in `core`.

| Module | Artifact | Contents |
|---|---|---|
| `email` | `casehub-connectors-email` | `EmailConnector` (outbound) + `quarkus-mailer` — unchanged |
| `email-inbound` | `casehub-connectors-email-inbound` | `EmailInboundConnector`, `EmailInboundAccount`, `EmailInboundAccountProvider`, `angus-mail` |

---

## Architecture

Three new types, all in the `email-inbound` module:

### `EmailInboundAccount` (record)

Value type carrying one IMAP account's connection details:

```java
public record EmailInboundAccount(
        String id,                // goes into metadata["account-id"]; NOT connectorId
        String host,
        int port,                 // default 993
        boolean tls,              // default true (IMAPS)
        String username,
        String password,
        String folder,            // default "INBOX"
        int pollIntervalSeconds   // default 60
) {}
```

`id` identifies the account in `metadata["account-id"]`. It does **not** appear in
`InboundMessage.connectorId` — that field is always `"email-inbound"` (the connector
type constant from `EmailInboundConnector.id()`), regardless of how many accounts are
configured. Observers filter by connector type on `connectorId`; they filter by account
on `metadata["account-id"]`.

### `EmailInboundAccountProvider` (SPI)

```java
public interface EmailInboundAccountProvider {
    List<EmailInboundAccount> accounts();
}
```

CDI interface returning all accounts to poll. The default `@DefaultBean`
implementation (`DefaultEmailInboundAccountProvider`) uses `@ConfigProperty` to
read a single account from MicroProfile Config (same pattern as all other
connectors). If `host` is blank, returns `List.of()` — connector is inactive,
no threads started.

Callers implement this bean with higher CDI priority to supply accounts from any
source (database, multi-tenant config, etc.) without changing the connector.

### `EmailInboundConnector implements InboundConnector`

`@ApplicationScoped` CDI bean. `id()` returns `"email-inbound"`.

- `start(sink)`: iterates accounts from provider; launches one single-threaded
  `ScheduledExecutorService` per account using `scheduleWithFixedDelay` (next poll
  only starts after previous completes). The executor list is initialised at
  construction (not lazily), so `stop()` is always safe to call.
- `stop()`: calls `shutdownNow()` on all executors.

**Session vs. Store lifecycle:** one `jakarta.mail.Session` per account — created
at the start of each poll cycle alongside the `Store`. Session is a lightweight
properties holder (TLS settings, host, port baked in at construction), so a shared
Session cannot accommodate accounts with differing `tls` values; per-account
Sessions eliminate this. `Store` holds a live TCP connection — both Session and
Store are opened and closed per poll cycle.

---

## Configuration

`DefaultEmailInboundAccountProvider` reads via `@ConfigProperty`:

| Property | Default | Notes |
|---|---|---|
| `casehub.connectors.email-inbound.host` | `""` | Blank → no-op (provider returns empty list) |
| `casehub.connectors.email-inbound.port` | `993` | IMAPS default |
| `casehub.connectors.email-inbound.tls` | `true` | Implicit SSL/TLS on connect |
| `casehub.connectors.email-inbound.username` | `""` | |
| `casehub.connectors.email-inbound.password` | `""` | |
| `casehub.connectors.email-inbound.folder` | `"INBOX"` | |
| `casehub.connectors.email-inbound.poll-interval-seconds` | `60` | |

**Account id in `DefaultEmailInboundAccountProvider`:** hardcoded to `"email-inbound"`.
There is no `casehub.connectors.email-inbound.id` config property. Multi-account
providers set their own ids programmatically.

No `casehub-platform-api` dependency. Future multi-tenant providers implement
`EmailInboundAccountProvider` directly.

---

## InboundMessage Field Mapping

| Field | Value |
|---|---|
| `connectorId` | `"email-inbound"` — always the connector type constant; never the account id |
| `externalSenderId` | `From:` first address via `InternetAddress.getAddress()`; `""` when `From:` is absent or unparseable |
| `externalChannelRef` | First `To:` address via `InternetAddress.getAddress()`; falls back to `account.username()` when `To:` is absent or empty |
| `content` | Plain text part if present; raw HTML if HTML-only; `""` if neither. Recursive extraction (see below). |
| `receivedAt` | `Message.getReceivedDate()` → `Message.getSentDate()` → `Instant.now()` (fallback chain) |
| `metadata` | Keys present only when header exists: `"message-id"` → RFC 2822 Message-ID; `"subject"` → email subject. Always present: `"account-id"` → `EmailInboundAccount.id()` |

`"account-id"` lets observers distinguish accounts in multi-account deployments.
`connectorId` is always `"email-inbound"` so type-based dispatch never needs prefix matching.

---

## Poll Cycle (Per Account)

1. Create `Session` with account-specific properties (host, port, TLS settings); open `Store` and `Folder` in READ_WRITE mode — all created fresh every poll cycle, no reconnect logic needed
2. Search for UNSEEN messages: `folder.search(new FlagTerm(Flags.Flag.SEEN, false))`
3. For each message:
   a. Parse `From:` → `InternetAddress.getAddress()` → `externalSenderId`
   b. Parse `To:` first address → `InternetAddress.getAddress()` → `externalChannelRef`
   c. Extract content recursively (see Content Extraction below)
   d. Read `Message-ID`, `Subject` headers → metadata
   e. Resolve `receivedAt` via fallback chain
   f. Construct `InboundMessage`, call `sink.receive()`
   g. Mark message `SEEN` immediately after `sink.receive()` returns
4. Close `Folder` and `Store` in `finally` block

**Mark-SEEN timing:** per-message immediately after delivery. A crash downstream
will not redeliver already-processed messages on the next poll. However, if
`stop()` interrupts between `sink.receive()` returning and the SEEN flag write,
the message has been delivered but will not be marked SEEN — it will be
redelivered on next startup. **This is at-least-once delivery.** Observers must
be idempotent on message redelivery.

---

## Content Extraction

MIME structures are nested. A naive top-level type check fails for
`multipart/mixed` envelopes that contain a `multipart/alternative` subtree:

```
multipart/mixed
  └── multipart/alternative
        ├── text/plain  ← want this
        └── text/html
  └── application/pdf   ← ignore
```

Algorithm: recursive descent, prefer depth-first `text/plain`, fall back to
depth-first `text/html`, fall back to `""`. Binary parts (attachments) are
silently ignored in v1 (see connectors#10 for attachment support).

```
extractText(part):
  if part is text/plain → return part content
  if part is multipart → recurse each body part, return first text/plain found
  return null

extractHtml(part):
  if part is text/html → return part content
  if part is multipart → recurse each body part, return first text/html found
  return null

content = extractText(message) ?? extractHtml(message) ?? ""
```

---

## Error Handling

| Situation | Behaviour |
|---|---|
| IMAP connection failure during poll | Log WARNING with account id; close connections in `finally`; retry next scheduled poll |
| `sink.receive()` throws | Log SEVERE; mark message SEEN anyway (prevents infinite redelivery loop); continue with remaining messages |
| `stop()` during active poll | `shutdownNow()` — in-progress IMAP session abandoned. May produce one redelivery on next startup (at-least-once; see above) |
| Provider returns empty list | `start()` starts no threads; `stop()` is a no-op |

No backoff in v1. Connection failures retry at the next scheduled interval.

---

## Thread Management

Each account gets its own single-threaded `ScheduledExecutorService` with a
named daemon thread factory:

```java
Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "email-inbound-" + account.id() + "-poller");
    t.setDaemon(true);  // prevents hung IMAP from blocking JVM shutdown
    return t;
});
```

Named threads are distinguishable in logs and thread dumps. Daemon threads
allow clean JVM shutdown even if an IMAP call is blocked.

---

## New Dependencies

**`email-inbound/pom.xml`:**

| Artifact | Scope | Reason |
|---|---|---|
| `casehub-connectors-core` | compile | `InboundConnector`, `InboundMessage`, `InboundMessageSink` |
| `org.eclipse.angus:angus-mail` | compile | Jakarta Mail implementation — IMAP transport |
| `io.quarkus:quarkus-junit` | test | `@QuarkusTest` |
| `com.icegreen:greenmail-junit5` | test | Embedded IMAP+SMTP server |
| `org.assertj:assertj-core` | test | Assertions |

No dependency on `casehub-platform-api`. No dependency on `quarkus-mailer`.

---

## Testing

### Unit tests — `EmailInboundConnectorTest` (no Quarkus container)

Uses Greenmail (embedded IMAP+SMTP, no Docker). `InboundMessageSink` is a plain
capturing lambda. `EmailInboundAccount` constructed with `tls=false` and Greenmail's
plain IMAP port (default 3143).

| Test | Assertion |
|---|---|
| No UNSEEN messages | Sink not called |
| Single plain-text message | Correct `InboundMessage` fields; message marked SEEN |
| HTML-only message | Raw HTML in `content` |
| Multipart/alternative | Plain text extracted; HTML part ignored |
| Multipart/mixed with nested alternative + attachment | Plain text extracted; attachment silently ignored |
| Multiple UNSEEN messages | All delivered; all marked SEEN |
| Second poll after first | Already-SEEN messages not redelivered |
| Provider returns empty list | No threads started; `stop()` is no-op |
| IMAP connection failure | Exception logged; no sink call; no crash |
| `sink.receive()` throws | Message still marked SEEN; remaining messages processed |
| Fallback receivedAt | When `getReceivedDate()` returns null, `getSentDate()` used; when both null, `Instant.now()` used |

### Integration test — `EmailInboundConnectorQuarkusTest`

`@QuarkusTest` with Greenmail started via `@BeforeAll`. Config fed via `@TestProfile`
MP Config overrides — must include:
```
casehub.connectors.email-inbound.host=localhost
casehub.connectors.email-inbound.port=<greenmail-imap-port>
casehub.connectors.email-inbound.tls=false
casehub.connectors.email-inbound.username=test@example.com
casehub.connectors.email-inbound.password=password
casehub.connectors.email-inbound.poll-interval-seconds=1
```

One happy-path test: send plain-text email to Greenmail → poll fires → CDI event
received by test observer → assert `InboundMessage` fields correct.

### `DefaultEmailInboundAccountProviderTest`

| Test | Assertion |
|---|---|
| All config properties set | `EmailInboundAccount` constructed correctly |
| Blank host | Returns `List.of()` |

---

## Protocol Coherence

| Protocol | Status |
|---|---|
| PP-20260529-7b94ab — inbound connector type separation | ✅ Implements `InboundConnector` (pull-based); no `WebhookInboundConnector` involved |
| PP-20260529-b7765c — constant-time HMAC | ✅ Not applicable (no signature verification) |
| PLATFORM.md — no parallel preference types | ✅ Not using Preferences; `@ConfigProperty` for static IMAP config |
| Module tier structure — optional dependency separation | ✅ Separate `email-inbound` module; no forced coupling with `quarkus-mailer` |

---

## Deferred (Issues Filed)

- connectors#9 — IMAP IDLE support for near-real-time delivery
- connectors#10 — Binary/attachment content support
