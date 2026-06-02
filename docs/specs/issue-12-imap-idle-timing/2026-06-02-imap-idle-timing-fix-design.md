# Design: Fix IMAP IDLE Test Timing Flakiness (connectors#12)

## Problem

`EmailInboundConnectorTest` has two intermittently failing tests under Maven cold-build conditions:

- `messageWithNoAttachments_attachmentsEmptyAndCountIsZero`
- `multipleUnseenMessages_allDelivered`

**Root cause — two execution paths after `connector.start()`:**

`start()` returns immediately after submitting a virtual thread. That thread races through:
IMAP connect → `folder.open` → `processUnseen()` → `folder.idle(true)` (blocks).

The test thread delivers messages via SMTP immediately after `start()`.

- **Path A (reliable):** SMTP completes before the virtual thread connects → `processUnseen()` on
  first connect finds the message deterministically. No IDLE involved.
- **Path B (flaky):** Virtual thread enters `idle(true)` before SMTP completes → the connector
  depends on GreenMail firing an EXISTS notification. GreenMail's notification latency is
  non-deterministic under JVM startup load (class loading, JIT warm-up, thread scheduling).
  The 3-second `BlockingQueue.poll()` timeout can be exceeded on a cold JVM.

**The window between `processUnseen()` and `folder.idle(true)`:** There is a brief window after
`processUnseen()` returns (finding nothing) and before `folder.idle(true)` is entered. If a message
arrives in this window, GreenMail buffers the EXISTS notification; per RFC 2177, the server sends
buffered responses immediately when the IDLE command arrives. This means `idle(true)` returns almost
instantly with the notification, and `processUnseen()` finds the message. This window is handled
correctly — it is not the source of flakiness.

**The `multipleUnseenMessages_allDelivered` race, specifically:** `processUnseen()` fetches all UNSEEN
messages in a single IMAP SEARCH. If both SMTP sends complete before the first IDLE fires, both
messages are batched and delivered in one cycle — no race. The race occurs when IDLE fires between
the two SMTP sends: the connector delivers message 1, re-enters `idle(true)`, and then message 2
arrives and requires a second IDLE notification. The current sequential `receive()` / `receive()`
calls wait up to 3s each, which can be exceeded when this second notification is slow.

## Alternatives Evaluated

### Alternative A: Convert content/field tests to `deliverDirect()` + start-after

Tests that check field mapping, content type parsing, attachment handling, and metadata do not need
SMTP delivery — they need IMAP processing. Converting them to pre-deliver via `deliverDirect()` puts
them on Path A, which is deterministic.

**Adopted partially.** Five tests (see Fix section below) have no coverage intent that requires
SMTP delivery. Converting them eliminates their exposure to IDLE notification latency entirely.
Three tests and one multi-message test retain SMTP-after-start because they specifically test the
IDLE notification path.

### Alternative B: Add a readiness signal to `EmailInboundConnector`

A `CountDownLatch` that fires when `folder.idle(true)` is first entered would let tests call
`awaitReady()` before delivering via SMTP, making the IDLE notification deterministic.

**Rejected.** `casehub-connectors` is a library. Libraries should not carry test-seam production
code. The state management is non-trivial: the latch must reset on reconnect (the IDLE loop
reconnects on `FolderClosedException`/`StoreClosedException`), must handle connection failures
(latch never fires, test hangs), and must handle zero accounts. The "production health check" use
case is a stretch — Quarkus health checks belong in a `HealthCheck` CDI bean, not on the connector.
The hybrid `deliverDirect()` + Awaitility approach below eliminates the race for content tests and
tolerates it robustly for IDLE-specific tests, without touching production code.

## Fix

### Test classification

Every SMTP-after-start test (Path B) is categorised:

| Test | Intent | Action |
|------|--------|--------|
| `singlePlainTextMessage_deliveredWithCorrectFields` | IDLE notification, field mapping | SMTP-after-start; stay + Awaitility |
| `multipleUnseenMessages_allDelivered` | Multi-message IDLE delivery | SMTP-after-start; stay + Awaitility + atomic wait |
| `doubleStart_isNoOp` | Live connector guard | SMTP-after-start; stay + Awaitility |
| `messageMarkedSeen_notRedeliveredAfterRestart` | SEEN flag persistence | SMTP-after-start; already `@Timeout(10)` |
| `htmlOnlyMessage_rawHtmlInContent` | Content type parsing | Convert to `deliverDirect()` |
| `messageWithoutSubject_subjectKeyAbsent` | Metadata parsing (uses `deliverViaSMTP()`) | Convert to `deliverDirect()` |
| `messageWithPdfAttachment_attachmentDelivered` | Attachment parsing | Convert to `deliverDirect()` |
| `messageWithNoAttachments_attachmentsEmptyAndCountIsZero` | Attachment count | Convert to `deliverDirect()` |
| `messageWithMultipleAttachments_allCollected` | Multiple attachments | Convert to `deliverDirect()` |

Pre-deliver tests (`missingFromHeader_...`, `missingToHeader_...`, `sinkThrows_...`) are already
on Path A and unchanged.

---

### 1. Convert five tests to `deliverDirect()` + start-after

For each test in the "Convert" column above:

1. Build the `MimeMessage` before `connector.start()`
2. Call `deliverDirect(msg)` to append directly to the IMAP mailbox
3. Then call `connector.start(captured::add)`
4. The `receive()` call is fast: `processUnseen()` on first connect finds the message immediately

Pattern (same as existing `missingFromHeader_...` and `missingToHeader_...` tests):

```java
// build msg
deliverDirect(msg);           // append to IMAP before start
connector.start(captured::add);
final InboundMessage result = receive();
// assertions
```

No `@Timeout` change needed for these five — `@Timeout(5)` is adequate for Path A.

---

### 2. Replace `receive()` with Awaitility

Awaitility 4.3.0 is already on the test classpath (`test` scope in `email-inbound/pom.xml`).
Its default polling strategy is `FixedPollInterval(100ms)` — flat 100ms intervals, not Fibonacci
(which is opt-in via `.pollInterval(fibonacci())`).

```java
// Before
private InboundMessage receive() throws InterruptedException {
    final InboundMessage msg = captured.poll(3, TimeUnit.SECONDS);
    assertThat(msg).as("message not delivered within 3s — IDLE did not fire").isNotNull();
    return msg;
}

// After
private InboundMessage receive() {
    await().atMost(5, SECONDS)
           .failMessage("message not delivered within 5s — IDLE did not fire or processUnseen missed it")
           .untilAsserted(() -> assertThat(captured).isNotEmpty());
    return captured.poll();
}
```

`untilAsserted(() -> assertThat(captured).isNotEmpty())` keeps the assertion inside Awaitility's
retry loop. `captured.poll()` after the `await()` is safe: there is exactly one writer (the
connector virtual thread) and one reader (the test thread). No TOCTOU is possible. The `poll()`
can only return null if the queue is somehow drained between await and poll — impossible in this
single-consumer design — but the pattern is defensively sound because `untilAsserted` guarantees
non-empty before proceeding.

New import: `import static org.awaitility.Awaitility.await` and `import static java.util.concurrent.TimeUnit.SECONDS`
(replaces the existing `java.util.concurrent.TimeUnit` import with the static variant).

---

### 3. Atomic wait in `multipleUnseenMessages_allDelivered`

```java
// Before
final InboundMessage m1 = receive();
final InboundMessage m2 = receive();

// After
await().atMost(5, SECONDS)
       .failMessage("both messages not delivered within 5s")
       .untilAsserted(() -> assertThat(captured).hasSizeGreaterThanOrEqualTo(2));
final InboundMessage m1 = captured.poll();
final InboundMessage m2 = captured.poll();
```

Waits for both messages to be in the queue before pulling either, regardless of whether they
arrived in one or two IDLE cycles.

---

### 4. `@Timeout` bumps — invariant and scope

**Invariant:** `@Timeout` must exceed `atMost()` by enough margin for Awaitility to emit its failure
message before JUnit kills the thread. With `atMost(5, SECONDS)`, JUnit must not fire before at
least 6–7 seconds. `@Timeout(10)` gives a 5-second buffer — adequate.

If `@Timeout ≤ atMost`, JUnit kills the test thread before Awaitility reports; the test dies with
a generic timeout exception rather than Awaitility's informative failure message.

**Tests that change from `@Timeout(5)` to `@Timeout(10)`:**

- `singlePlainTextMessage_deliveredWithCorrectFields`
- `multipleUnseenMessages_allDelivered`
- `doubleStart_isNoOp`

**Already at `@Timeout(10)`, no change needed:**

- `messageMarkedSeen_notRedeliveredAfterRestart` — SMTP-after-start; `receive()` update applies;
  already has sufficient budget.

**Unchanged (Path A or no `receive()`):**

- All five converted tests (`@Timeout(5)` stays — Path A is fast)
- `sinkThrows_...` — SMTP-before-start, Path A, uses `poll(3, SECONDS)` which is fine
- `noAccounts_startIsNoOp_stopIsNoOp`, `imapConnectionFailure_...` — no delivery

**Leave unchanged:** `captured.poll(500, TimeUnit.MILLISECONDS)` in `doubleStart_isNoOp` — this is
a negative assertion (nothing more should arrive). A fixed wait is correct; Awaitility would not help.

---

## Known Issue: GreenMail Shared IMAP State

`withPerMethodLifecycle(false)` means the GreenMail server — and its IMAP mailbox — is shared
across all tests. If a test fails before consuming its delivered message, that message remains UNSEEN
in the INBOX. The next test's connector will find and deliver it on `processUnseen()` on connect,
potentially causing spurious passes or assertion failures.

This is a latent cross-test contamination bug independent of the timing fix. The clean mitigation is
`withPerMethodLifecycle(true)` (per-method GreenMail restart, slower) or a `@BeforeEach` INBOX purge.
Investigating this is out of scope for connectors#12; tracked separately.

---

## What This Does Not Fix

GreenMail's IDLE notification latency is in GreenMail internals. This fix makes the remaining SMTP
tests resilient to that latency — it does not change GreenMail's behavior.

**Why 5 seconds is the right bound:** GreenMail runs in-process. The notification path is entirely
within the JVM: SMTP server thread → in-memory store → `Object.notifyAll()` on the IMAP folder →
Angus Mail IDLE wake-up. Latency is bounded by JVM thread scheduling jitter, not network. On a cold
JVM under Maven startup load, observed latency is in the 1–3s range (the issue notes tests "pass on
retry", which implies they fail just above 3s). 5 seconds is ~2–5× the observed cold-start maximum.
If the notification takes > 5s, a more serious JVM or GreenMail issue is worth investigating
separately.

## References

- GE-20260501-0586a4: Awaitility `during()` for stable-count assertions
- GE-20260515-ed10ee: `untilAsserted` vs `during` for exact async counts
- GE-20260519-e193d2: Awaitility + JTA context caveat (does not apply — this is a plain JUnit test)
- GE-20260529-4691e8: `deliverDirect()` via `MailFolder.appendMessage()` — pattern already in use
