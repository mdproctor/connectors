# Email Inbound Connector — Design Spec

**Issue:** casehubio/connectors#7  
**Branch:** issue-7-email-inbound-v1-polish  
**Date:** 2026-05-29

---

## Purpose

Add an email inbound connector that polls one or more IMAP mailboxes and delivers
received emails as `InboundMessage` CDI events via `InboundConnectorService`.

---

## Architecture

Three new types, all in the `email` module:

### `EmailInboundAccount` (record)

Value type carrying one IMAP account's connection details:

```java
public record EmailInboundAccount(
        String id,                // connector id suffix, e.g. "email-inbound"
        String host,
        int port,                 // default 993
        boolean tls,              // default true (IMAPS)
        String username,
        String password,
        String folder,            // default "INBOX"
        int pollIntervalSeconds   // default 60
) {}
```

`id` is used as `InboundMessage.connectorId`. Multi-account deployments use
caller-chosen ids (e.g. `"email-inbound-support"`, `"email-inbound-billing"`)
so observers can filter by account.

### `EmailInboundAccountProvider` (SPI)

```java
public interface EmailInboundAccountProvider {
    List<EmailInboundAccount> accounts();
}
```

CDI interface returning all accounts to poll. The default `@DefaultBean`
implementation (`DefaultEmailInboundAccountProvider`) resolves a single account
from `Preferences` using typed `PreferenceKey` constants. If `HOST` resolves to
blank, returns `List.of()` — connector is inactive, no threads started.

Callers implement this bean to supply accounts from any source (database,
multi-tenant preference store, etc.).

### `EmailInboundConnector implements InboundConnector`

`@ApplicationScoped` CDI bean. `id()` returns `"email-inbound"` (the connector
registration id — distinct from the per-account `EmailInboundAccount.id()`).

- `start(sink)`: iterates accounts from `EmailInboundAccountProvider`, launches
  one single-threaded `ScheduledExecutorService` per account using
  `scheduleWithFixedDelay` (next poll starts only after previous completes).
- `stop()`: calls `shutdownNow()` on all executors.

---

## Configuration — Typed `PreferenceKey` Constants

Defined in `EmailInboundPreferences` (package-private utility class):

| Key constant   | Config property                                        | Default  |
|---------------|--------------------------------------------------------|----------|
| `HOST`        | `casehub.connectors.email-inbound.host`               | `""`     |
| `PORT`        | `casehub.connectors.email-inbound.port`               | `993`    |
| `TLS`         | `casehub.connectors.email-inbound.tls`                | `true`   |
| `USERNAME`    | `casehub.connectors.email-inbound.username`           | `""`     |
| `PASSWORD`    | `casehub.connectors.email-inbound.password`           | `""`     |
| `FOLDER`      | `casehub.connectors.email-inbound.folder`             | `"INBOX"`|
| `POLL_INTERVAL` | `casehub.connectors.email-inbound.poll-interval-seconds` | `60` |

Resolved at `SettingsScope(Path.of("casehub", "connectors", "email-inbound"), Instant.now())`.

---

## InboundMessage Field Mapping

| Field               | Value                                                  |
|--------------------|--------------------------------------------------------|
| `connectorId`      | `EmailInboundAccount.id()` (e.g. `"email-inbound"`)   |
| `externalSenderId` | `From:` header — first address                         |
| `externalChannelRef` | IMAP folder name (e.g. `"INBOX"`)                   |
| `content`          | Plain text part if present; raw HTML if HTML-only; `""` if neither |
| `receivedAt`       | `Instant.now()` at parse time                          |
| `metadata`         | `"message-id"` → RFC 2822 Message-ID; `"subject"` → email subject |

---

## Poll Cycle

Per account, each scheduled execution:

1. Open `jakarta.mail.Session` with account credentials and TLS settings
2. Connect `Store` → open `Folder` in `READ_WRITE` mode
3. Search for `UNSEEN` messages: `folder.search(new FlagTerm(Flags.Flag.SEEN, false))`
4. For each message:
   a. Parse `From:` address → `externalSenderId`
   b. Extract content: prefer `text/plain` part; fall back to `text/html` part;
      fall back to `""` for non-text content
   c. Read `Message-ID` and `Subject` headers → `metadata`
   d. Construct `InboundMessage`, call `sink.receive()`
   e. Mark message `SEEN` immediately after `sink.receive()` returns
5. Close `Folder` and `Store` in `finally` block

**Mark-SEEN timing:** per-message immediately after delivery, not batch at end.
A downstream crash after some deliveries will not redeliver already-processed
messages on the next poll.

---

## Error Handling

| Situation | Behaviour |
|---|---|
| IMAP connection failure | Log WARNING with account id; close connections in `finally`; retry next scheduled poll |
| `sink.receive()` throws | Log SEVERE; mark message SEEN anyway (prevents infinite redelivery loop); continue with remaining messages |
| `stop()` during active poll | `shutdownNow()` — in-progress IMAP session is abandoned; no shared state, safe to discard |
| Provider returns empty list | `start()` starts no threads; `stop()` is a no-op |

No backoff/retry in v1 — connection failures retry at the next scheduled interval.

---

## New Dependencies

**`email/pom.xml`:**

| Artifact | Scope | Reason |
|---|---|---|
| `casehub-platform-api` | compile | `Preferences`, `PreferenceKey`, `Path`, `SettingsScope` |
| `org.eclipse.angus:angus-mail` | compile | Jakarta Mail implementation — IMAP transport |
| `casehub-platform` | test | `MockPreferenceProvider @DefaultBean` for `@QuarkusTest` |

**Cross-repo dependency table in PLATFORM.md:** add row for
`casehub-platform-api → casehub-connectors / email` (Preferences + PreferenceKey
in `DefaultEmailInboundAccountProvider`).

---

## Testing

### Unit tests — `EmailInboundConnectorTest` (no Quarkus container)

Uses Greenmail (embedded IMAP+SMTP, no Docker) as the IMAP server.

| Test | Assertion |
|---|---|
| No UNSEEN messages | Sink not called |
| Single plain-text message | Correct `InboundMessage` fields; message marked SEEN |
| HTML-only message | Raw HTML in `content` |
| Multipart with plain text | Plain text extracted; HTML part ignored |
| Multiple UNSEEN messages | All delivered; all marked SEEN |
| Second poll after first | Already-SEEN messages not redelivered |
| Provider returns empty list | No threads started; `stop()` is no-op |
| IMAP connection failure | Exception logged; no sink call; no crash |
| `sink.receive()` throws | Message still marked SEEN; remaining messages processed |

### Integration test — `EmailInboundConnectorQuarkusTest`

`@QuarkusTest` with Greenmail started via `@BeforeAll`. Host/port fed via
`@TestProfile` MP Config overrides.

One happy-path test: send plain-text email to Greenmail → poll fires → CDI
event received by test observer → assert `InboundMessage` fields correct.

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
| PLATFORM.md boundary rules — no parallel preference types | ✅ Using `casehub-platform-api` `PreferenceKey`/`Preferences` directly |
| casehub-platform-dependency-scope | ✅ `casehub-platform-api` at compile; `casehub-platform` (mocks) at test |

---

## Deferred (Issues Filed)

- connectors#9 — IMAP IDLE support for near-real-time delivery
- connectors#10 — Binary/attachment content support
