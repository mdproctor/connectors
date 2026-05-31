# Design Spec — IMAP IDLE + Attachment Support

**Issues:** casehubio/connectors#9 (IMAP IDLE), casehubio/connectors#10 (attachment support)
**Branch:** `issue-9-imap-idle-and-attachments`
**Date:** 2026-05-30

---

## Context

`EmailInboundConnector` was shipped in connectors#7 as a polling-based IMAP connector: one
`ScheduledExecutorService` per account, opening a fresh connection every `pollIntervalSeconds`,
searching UNSEEN messages, delivering via `InboundMessageSink`, and closing. Two capabilities
were deferred: near-real-time delivery (IDLE) and binary/attachment support.

This spec covers both in one branch. They compose cleanly: IDLE replaces the execution model,
attachments extend the data model. Neither depends on the other.

---

## Issue #9 — IMAP IDLE

### Decision

IMAP IDLE (RFC 2177, 1997) replaces polling entirely. No polling fallback. Every target
deployment (Gmail, Outlook 365, Fastmail, Dovecot, Exchange, ProtonMail) has supported IDLE
for 20+ years. A fallback code path would add state management, failure-categorisation
complexity, and divergence risk — for a server capability that doesn't exist in the target
deployment universe.

When IDLE fails persistently (wrong server, firewall, credentials), the reconnect loop logs
at `SEVERE` after 5+ consecutive failures — loud, actionable, no silent degradation.

### Execution Model

One virtual thread per account, submitted to a `VirtualThreadPerTaskExecutor`, running a
blocking IDLE loop. Virtual threads are correct here: each thread blocks on a socket for up
to 29 minutes. Platform threads would hold an OS thread indefinitely per account; virtual
threads park during the block at zero OS-thread cost.

`ExecutorService` is preferred over bare `Thread.ofVirtual()` because `shutdownNow()`
interrupts all submitted tasks cleanly — eliminating a separate thread-list tracking concern
and simplifying `stop()`.

```
EmailInboundConnector fields:
  private volatile boolean stopping = false      // volatile: written by shutdown thread, read by virtual threads
  private ExecutorService executor               // null before start()
  private final List<Store> openStores = new CopyOnWriteArrayList<>()

start(sink):
  if (executor != null) return                  // double-start guard
  executor = Executors.newVirtualThreadPerTaskExecutor()
  for each account in provider.accounts():
    executor.submit(() -> idleLoop(account, sink))

stop():
  stopping = true
  new ArrayList<>(openStores).forEach(store -> store.close() quietly)   // primary: unblocks idle()
  if (executor != null) executor.shutdownNow()                          // secondary: interrupts sleeps
```

### IDLE Loop

```
idleLoop(account, sink):
  backoffSeconds = 1
  consecutiveFailures = 0

  while not stopping:
    store = null
    folder = null
    try:
      store = connect(account)                            // Session.getInstance() + store.connect()
      openStores.add(store)
      if (stopping):                                      // race guard: stop() may have run already
        closeQuietly(null, store)
        return
      folder = (IMAPFolder) store.getFolder(account.folder())   // org.eclipse.angus.mail.imap.IMAPFolder
      folder.open(Folder.READ_WRITE)
      backoffSeconds = 1
      consecutiveFailures = 0
      log INFO "email-inbound: IDLE connected for account <id>"

      while not stopping:
        folder.idle(false)                                // blocks until server notification or timeout
        processUnseen(folder, account, sink)              // search UNSEEN, deliver, mark SEEN

    catch FolderClosedException | StoreClosedException:   // jakarta.mail API
      openStores.remove(store)
      if not stopping:
        log INFO "email-inbound: IDLE session ended for account <id>, reconnecting"
        // No backoff, no consecutiveFailures++ — either normal IDLE timeout or server disconnect
        // If the reconnect fails, the next catch handles it with backoff

    catch Exception e:
      openStores.remove(store)
      if not stopping:
        consecutiveFailures++
        level = consecutiveFailures >= 5 ? SEVERE : WARNING
        log(level, "email-inbound: connection failed for account <id>"
                   + " (attempt " + consecutiveFailures + "): " + e.getMessage())
        sleepQuietly(backoffSeconds * 1000)
        backoffSeconds = min(backoffSeconds * 2, account.reconnectDelaySeconds())

    finally:
      openStores.remove(store)
      closeQuietly(folder, store)
```

**Key behaviours:**

1. **`folder.idle(false)` return semantics:** most servers (Gmail, Fastmail) cause `idle(false)`
   to **return normally** on their server-side IDLE timeout — no exception. In that case
   `processUnseen()` runs (finding nothing new if no messages arrived), and `idle(false)` is
   called again. Servers that close the TCP connection on timeout throw `FolderClosedException`,
   handled at INFO with immediate reconnect. Either way, no WARNING noise from normal operation.

2. **`stopping` is `volatile`:** written by the Quarkus shutdown thread, read by each virtual
   thread in the inner `while (!stopping)` check. Without `volatile` the JVM may never propagate
   the write.

3. **Race guard after `openStores.add(store)`:** `stop()` snapshots `openStores` before calling
   `close()`. A thread that adds its store after the snapshot has been taken will block in
   `folder.idle(false)` indefinitely unless it checks `stopping` immediately after adding.

4. **Exponential backoff:** starts at 1s, doubles each failure, caps at
   `account.reconnectDelaySeconds()`. Resets to 1s on successful connection. After 5+
   consecutive failures, escalates to SEVERE — permanent failures (wrong credentials, firewall)
   produce a loud, actionable signal without giving up.

5. **`processUnseen()` logic:** unchanged from the original `pollAccount()` SEEN-flag-based
   fetch — at-least-once delivery. Shutdown interrupting between `sink.receive()` and SEEN flag
   write redelivers on next startup. Observers must be idempotent.

6. **Socket timeout:** `buildProperties()` adds `mail.imap.timeout=300000` (5 min) for plain
   IMAP and `mail.imaps.timeout=300000` for IMAPS. Without this, a dead network connection
   may block `folder.idle(false)` indefinitely — OS TCP keepalive defaults can take hours to
   fire. A 5-minute socket timeout forces reconnect on dead connections.

### `EmailInboundAccount` Change

`pollIntervalSeconds` renamed to `reconnectDelaySeconds`. The field now caps the exponential
reconnect backoff. The name is honest about what it controls.

```java
public record EmailInboundAccount(
    String id, String host, int port, boolean tls,
    String username, String password, String folder,
    int reconnectDelaySeconds)   // was: pollIntervalSeconds
```

### Configuration Change

MP Config property renamed:

| Old | New | Default |
|-----|-----|---------|
| `casehub.connectors.email-inbound.poll-interval-seconds` | `casehub.connectors.email-inbound.reconnect-delay-seconds` | `60` |

`GreenMailResource` drops `poll-interval-seconds=1` entirely. IDLE delivers on server
notification; the reconnect delay is irrelevant to test timing.

---

## Issue #10 — Attachment Support

### `Attachment` Record — `core` module

```java
public record Attachment(String filename, String contentType, byte[] content) {
    public Attachment {
        content = content == null ? new byte[0] : content.clone();
    }
    @Override public byte[] content() { return content.clone(); }
}
```

`filename` is nullable — MIME does not require a filename. `content` is defensively copied on
construction and access — `byte[]` is mutable and a record must enforce its own immutability
for library use. `contentType` carries the base MIME type only (parameters stripped,
lowercased — e.g. `"application/pdf"`, never `"Application/PDF; name=invoice.pdf"`).
`null` content-type from malformed parts defaults to `"application/octet-stream"` per RFC 2045 §5.2.

Lives in `core` because `InboundMessage` (also in `core`) references it.

**V1 memory constraint:** `Part.getInputStream().readAllBytes()` fully materialises each
attachment into a heap-resident `byte[]`. For large files (multi-MB PDFs, scanned documents),
multiple concurrent emails create proportional heap pressure. Streaming is deferred to a future
version. Callers that need streaming cannot use this API version.

### `InboundMessage` Change — `core` module

`List<Attachment> attachments` added between `content` and `receivedAt`:

```java
public record InboundMessage(
    String connectorId,
    String externalSenderId,
    String externalChannelRef,
    String content,
    List<Attachment> attachments,   // NEW
    Instant receivedAt,
    Map<String, String> metadata) {

    public InboundMessage {
        attachments = List.copyOf(attachments);   // defensive copy — callers may pass mutable lists
    }
}
```

`List.copyOf()` in the compact constructor is required: `ExtractionResult.attachments()` returns
the mutable `ArrayList` from `Accumulator`, which is passed directly to this constructor without
wrapping. Without `List.copyOf()`, the list is externally mutable — inconsistent with
`Attachment.content`'s defensive copy.

Two convenience constructors delegate to the canonical, preserving all existing call sites:

```java
// no attachments, with metadata — all webhook connector call sites (SlackInboundConnector etc.) unchanged
public InboundMessage(String connectorId, String externalSenderId, String externalChannelRef,
                      String content, Instant receivedAt, Map<String, String> metadata) {
    this(connectorId, externalSenderId, externalChannelRef, content, List.of(), receivedAt, metadata);
}

// no attachments, no metadata
public InboundMessage(String connectorId, String externalSenderId, String externalChannelRef,
                      String content, Instant receivedAt) {
    this(connectorId, externalSenderId, externalChannelRef, content, List.of(), receivedAt, Map.of());
}
```

The existing fallback in `EmailInboundConnector.toInboundMessage()` —
`new InboundMessage(ID, "", account.username(), "", Instant.now(), Map.of("account-id", account.id()))` —
compiles unchanged against the 6-arg convenience constructor.

`attachments` is always non-null (never `null`). Observers check `message.attachments().isEmpty()`
rather than null-guarding. The javadoc comment "Text content only in v1" is removed.

### `ExtractionResult` — `email-inbound` module, own file

`ExtractionResult.java` — package-private, separate file (not nested inside `ContentExtractor`):

```java
record ExtractionResult(String content, List<Attachment> attachments) {}
```

Package-private: only `ContentExtractor` and `EmailInboundConnector` in the same package touch it.
A separate file is required because Java does not support package-private members in a
non-public nested class of a `final` outer class in the way needed here.

### `ContentExtractor` Refactor — `email-inbound` module

**Single MIME-tree pass** replacing the previous two-pass structure:

```java
static ExtractionResult extract(Part part) {
    Accumulator acc = new Accumulator();
    traverse(part, acc);
    return new ExtractionResult(acc.resolveContent(), Collections.unmodifiableList(acc.attachments));
}

private static void traverse(Part part, Accumulator acc) {
    try {
        if (part.isMimeType("text/plain") && acc.plainText == null) {
            acc.plainText = part.getContent().toString();
        } else if (part.isMimeType("text/html") && acc.htmlText == null) {
            acc.htmlText = part.getContent().toString();
        } else if (part.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) part.getContent();
            for (int i = 0; i < mp.getCount(); i++) traverse(mp.getBodyPart(i), acc);
        } else {
            acc.attachments.add(toAttachment(part));
        }
    } catch (Exception e) {
        LOG.log(Level.WARNING, "email-inbound: content extraction failed on part", e);
    }
}
```

**`Accumulator`** — private static inner class of `ContentExtractor`:

```java
private static final class Accumulator {
    String plainText = null;
    String htmlText  = null;
    final List<Attachment> attachments = new ArrayList<>();

    String resolveContent() {
        if (plainText != null) return plainText;
        if (htmlText  != null) return htmlText;
        return "";
    }
}
```

**`toAttachment(Part)`:**

```java
private static Attachment toAttachment(Part part) throws MessagingException, IOException {
    String filename = part.getFileName();   // null if absent — Attachment.filename is nullable
    String ct = part.getContentType();
    if (ct == null) ct = "application/octet-stream";   // RFC 2045 §5.2 default
    String baseType = ct.contains(";")
            ? ct.substring(0, ct.indexOf(';')).trim().toLowerCase()
            : ct.trim().toLowerCase();
    byte[] bytes = part.getInputStream().readAllBytes();
    return new Attachment(filename, baseType, bytes);
}
```

**What counts as an attachment:** any MIME part that is not `text/plain`, not `text/html`, and
not `multipart/*`. `Content-Disposition` is not consulted — the MIME type drives the decision.

This includes intentional attachments (`Content-Disposition: attachment`), inline binary parts
(`Content-Disposition: inline` images in HTML newsletters), and all other non-text types:
`text/calendar` (iCal invitations), `text/csv`, `text/x-vcard`, `text/xml`, and anything with
an unknown content type. Observers that want only explicit attachments filter themselves;
the connector delivers everything the message contains.

`ContentExtractor` remains package-private (`final class`, no `public` modifier).

### `buildMetadata()` — `attachment-count` always present for email messages

`buildMetadata()` signature changes to accept `attachmentCount`:

```java
static Map<String, String> buildMetadata(EmailInboundAccount account,
                                          Message msg,
                                          int attachmentCount)
```

`attachment-count` is always included (even `"0"`), making the key consistently present for
all email `InboundMessage`s regardless of whether attachments exist:

```java
meta.put("attachment-count", String.valueOf(attachmentCount));
```

Callers can always do `Integer.parseInt(metadata.get("attachment-count"))` without `containsKey`
checks. Non-email connectors (Slack, Teams, etc.) do not include this key — it is
email-connector-specific metadata.

The two existing direct-call tests (`buildMetadata_noMessageIdHeader_keyAbsent` and
`buildMetadata_noSubject_keyAbsent`) both need a third argument added: `buildMetadata(testAccount(), msg, 0)`.

### `EmailInboundConnector.toInboundMessage()` — updated

```java
ExtractionResult extracted = ContentExtractor.extract(msg);
return new InboundMessage(
    ID,
    extractSenderId(msg),
    extractChannelRef(msg, account),
    extracted.content(),
    extracted.attachments(),
    resolveReceivedAt(msg),
    buildMetadata(account, msg, extracted.attachments().size()));
```

---

## Testing Strategy

### `EmailInboundConnectorTest` (unit, GreenMailExtension)

`captured` changes from `List<InboundMessage>` to `LinkedBlockingQueue<InboundMessage>`.
Tests start the connector, deliver via SMTP or direct GreenMail inject, then block on:

```java
private InboundMessage receive() throws InterruptedException {
    InboundMessage msg = captured.poll(3, TimeUnit.SECONDS);
    assertThat(msg).as("message not delivered within 3s — IDLE did not fire").isNotNull();
    return msg;
}
```

Tests previously calling `pollAccount()` directly become `start()` → deliver → `receive()`.

**At-least-once (SEEN flag):** deliver → `receive()` → `stop()` → create new connector →
`start()` → assert nothing received within 1s.

**IDLE reconnect test:** dropped. GreenMail uses OS-assigned ports in the unit test context —
there is no reliable way to simulate a port that starts bad then becomes reachable. The
reconnect logic is covered by code inspection. A WARNING log assertion (verifying backoff
logging fires on bad account) is an acceptable substitute if a log-capture framework is added.

**New attachment tests:**

```java
@Test void messageWithPdfAttachment_attachmentInResult()
    // filename, contentType, content bytes, attachment-count=1 in metadata

@Test void attachmentOnlyMessage_emptyContent_attachmentPresent()
    // content == "", attachments has 1 entry

@Test void multipleAttachments_allCollected()
    // attachments.size() == N, attachment-count == "N"

@Test void inlineImage_collectedAsAttachment()
    // Content-Disposition: inline, image/png → goes to attachments

@Test void messageWithNoAttachments_attachmentsEmpty_countIsZero()
    // regression: attachments empty, attachment-count == "0"
```

**Updated `buildMetadata` direct tests:**

Both `buildMetadata_noMessageIdHeader_keyAbsent` and `buildMetadata_noSubject_keyAbsent` add
`0` as the third argument. Both assert `attachment-count` == `"0"`.

### `ContentExtractorTest` (unit, no IMAP)

New cases:

```java
@Test void multipartMixed_textAndPdf_textInContent_pdfInAttachments()
@Test void multipartMixed_noText_attachmentsPresent_contentEmpty()
@Test void inlineImage_collectedAsAttachment()
@Test void attachmentWithNoFilename_filenameIsNull()
@Test void contentTypeParametersStripped_baseTypeOnly_lowercased()
@Test void nullContentType_defaultsToOctetStream()
@Test void textCalendar_collectedAsAttachment()   // text/* subtypes other than plain/html
```

Existing text/html/multipart/alternative cases retained.

### `EmailInboundConnectorQuarkusTest` (Quarkus CDI integration)

`GreenMailResource` drops `poll-interval-seconds=1`. No replacement key. IDLE delivers on
message arrival; the 2-second `capture.poll()` wait remains sufficient.

### Unchanged

- `InboundConnectorServiceTest` — mocks `InboundConnector`; `InboundMessage` constructed via
  6-arg convenience constructor, which still compiles.
- All webhook inbound tests — same convenience constructor, no attachments.
- `DefaultEmailInboundAccountProviderTest` — update property key assertions only.

---

## Files Changed

| File | Change |
|------|--------|
| `core/.../Attachment.java` | New record with defensive copy |
| `core/.../InboundMessage.java` | Add `attachments` field, `List.copyOf()` in compact constructor, two convenience constructors |
| `email-inbound/.../ExtractionResult.java` | New package-private record, own file |
| `email-inbound/.../EmailInboundAccount.java` | Rename `pollIntervalSeconds` → `reconnectDelaySeconds` |
| `email-inbound/.../EmailInboundConnector.java` | Full rewrite: IDLE loop, virtual threads via ExecutorService, volatile stopping, stop mechanism |
| `email-inbound/.../DefaultEmailInboundAccountProvider.java` | Rename config property |
| `email-inbound/.../ContentExtractor.java` | Single-pass refactor; add `Accumulator`, `toAttachment()` |
| `email-inbound/.../EmailInboundConnectorTest.java` | IDLE test model (LinkedBlockingQueue), attachment tests, updated buildMetadata calls (3-arg) |
| `email-inbound/.../EmailInboundConnectorQuarkusTest.java` | Drop poll-interval config |
| `email-inbound/.../GreenMailResource.java` | Drop poll-interval config |
| `email-inbound/.../DefaultEmailInboundAccountProviderTest.java` | Update property key |

**Not changed:** webhook module (convenience constructors cover all call sites),
`InboundConnectorService`, `EmailInboundAccountProvider` SPI.

---

## Platform Coherence

- `inbound-connector-id-is-type-not-account`: `connectorId` remains `"email-inbound"` (type). ✓
- `inbound-connector-type-separation`: `EmailInboundConnector` implements `InboundConnector` (pull). ✓
- Module tier structure: `Attachment` in pure-Java `core`; IMAP logic in `email-inbound`. ✓
- No Flyway, no JPA, no new dependencies. `IMAPFolder` import: `org.eclipse.angus.mail.imap.IMAPFolder` (angus-mail 2.0.3 — verified in jar). ✓
- `casehub-connectors` remains dependency-free within the casehubio ecosystem. ✓
