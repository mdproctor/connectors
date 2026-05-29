# Inbound Connector SPI — Design Spec

**Issue:** casehubio/connectors#4  
**Branch:** `issue-4-inbound-connector-spi`  
**Date:** 2026-05-29  
**Revision:** 3 (post second code-review)

---

## Problem

`casehub-connectors` is outbound-only. There is no path for external messages (Slack, Teams,
WhatsApp, SMS) to enter the platform. Customer-initiated contact — a reply to a notification,
a message in a support channel, an inbound SMS — has nowhere to go.

The platform has the downstream infrastructure (Qhorus `InboundNormaliser`, `ChannelGateway`,
WorkItem creation) but no transport layer to feed it.

---

## Scope

**In scope — this issue:**
- `InboundConnector` SPI and supporting types in `core`
- `WebhookInboundConnector` standalone abstract base and sealed result type in `core`
- `InboundConnectorService` CDI bean in `core`
- New `webhook` module with `WebhookRouter` (JAX-RS) and four concrete implementations:
  Slack, Teams (Outgoing Webhooks), WhatsApp, Twilio SMS
- Full test coverage: pure-Java unit tests for connectors; `@QuarkusTest` for the router

**Explicitly out of scope — filed as follow-up issues:**
- Qhorus bridge (`InboundMessage` → `MessageService.dispatch()`) — connectors#6
- Email inbound (IMAP polling) — connectors#7
- Platform doc updates (`casehub-connectors.md`, `PLATFORM.md`) — parent#89
- Routing `InboundMessage` to WorkItem creation — work#234

---

## Design Principles

**Symmetric with outbound.** The outbound pattern is `Connector` SPI + `ConnectorService`
CDI bean. Inbound mirrors it: `InboundConnector` SPI + `InboundConnectorService`. CDI
observers receive `InboundMessage` events; they do not interact with connectors directly.

**Pull and webhook are distinct transports.** Pull-based connectors (IMAP polling) have an
active lifecycle — `start(sink)`/`stop()` — managed by `InboundConnectorService`.
Webhook-based connectors are passive: their lifecycle is JAX-RS. These are different enough
that a unified interface with no-op lifecycle methods would be misleading. `InboundConnector`
covers pull; `WebhookInboundConnector` is standalone. Both deliver messages through
`InboundConnectorService.receive()`, which is the single CDI event bus.

**Pure delivery infrastructure.** No domain knowledge here. An `InboundMessage` carries
transport metadata — it does not interpret the message or decide what to do with it. That
is the job of the Qhorus bridge (connectors#6) and the routing layer (work#234).

**200 OK for POST failures; 4xx for GET challenge failures.** All four webhook platforms
retry automated POST delivery on non-2xx — Slack for up to 30 days with exponential backoff.
Returning non-2xx for a POST signature failure triggers retry storms and leaks information to
probers. `WebhookResult.Unauthorized` from a POST maps to HTTP 200 + `WARNING` security log.

GET challenge verification is different: it is a one-time admin action in a platform console
(Meta, Teams admin center). The admin sees the HTTP status directly and needs a 4xx to know
that configuration failed. Returning 200 for a wrong verify-token gives the admin a false
success signal — the platform silently rejects the subscription and no webhook traffic
arrives. `WebhookResult.Unauthorized` from a GET maps to HTTP 403 so the failure is visible.

The router checks `request.method()` in the `Unauthorized` case to apply the correct mapping.

**Constant-time HMAC comparison everywhere.** All signature verification uses
`MessageDigest.isEqual(expected, actual)`, never `String.equals()` or `Arrays.equals()`.
Timing attacks on webhook HMAC verification are a real exploit vector.

---

## Module Structure

| Module | Artifact | New? | Purpose |
|--------|----------|------|---------|
| `core` | `casehub-connectors` | existing | SPI + Connector + ConnectorService + **InboundConnector SPI** |
| `webhook` | `casehub-connectors-webhook` | **new** | WebhookRouter (JAX-RS) + 4 concrete inbound connectors |
| `email` | `casehub-connectors-email` | existing | Email outbound via quarkus-mailer |

Root `pom.xml` module order: `core` → `webhook` → `email`.

`webhook` dependencies: `casehub-connectors` (core) + `quarkus-rest`.

The same reason `email` is separate from `core` (quarkus-mailer is optional) applies here:
`quarkus-rest` must not be a mandatory dependency for deployments that only use outbound.

---

## Core Types

### `HttpMethod` enum

```java
package io.casehub.connectors;

public enum HttpMethod { GET, POST }
```

Used in `WebhookRequest`. Eliminates string comparison (`"POST".equals(...)`) and associated
typo risk throughout connector implementations.

### `InboundConnector` SPI — pull-based transports only

```java
package io.casehub.connectors;

public interface InboundConnector {
    /**
     * Unique identifier. Must be lowercase, URL-safe, no slashes or spaces
     * (e.g. {@code "email-inbound"}). Validated at registration.
     */
    String id();
    void start(InboundMessageSink sink);
    void stop();
}
```

This interface is for **pull-based connectors only** (IMAP polling, connectors#7 and similar).
`start()` is called once at Quarkus startup with the sink; the connector uses it to deliver
messages from its background loop. `stop()` is called at shutdown.

Webhook-based connectors do **not** implement this interface — they extend
`WebhookInboundConnector` (see below), which is a separate type with no lifecycle methods.

### `InboundMessageSink`

```java
@FunctionalInterface
public interface InboundMessageSink {
    void receive(InboundMessage message);
}
```

### `InboundMessage`

```java
public record InboundMessage(
    String connectorId,
    String externalSenderId,
    String externalChannelRef,
    String content,
    Instant receivedAt,
    Map<String, String> metadata
) {}
```

| Field | Semantics |
|-------|-----------|
| `connectorId` | Source connector id — `"slack-inbound"`, `"twilio-sms-inbound"`. Observers filter on this. |
| `externalSenderId` | Who sent it — Slack user ID, E.164 phone number, email address |
| `externalChannelRef` | Where it came from — Slack channel ID, Teams conversation ID, WhatsApp destination number |
| `content` | Message text. **Text content only in v1.** WhatsApp images, audio, and documents are not delivered as binary — `content` is set to the media URL for media messages, or empty string if no URL is available. This is a documented v1 limitation; binary media support is out of scope. |
| `receivedAt` | `Instant.now()` at parse time in the connector |
| `metadata` | Connector-specific extras — Slack workspace ID, message timestamp, Twilio message SID, WhatsApp message ID, etc. Observers that do not recognise a key ignore it. |

### `WebhookRequest`

```java
public record WebhookRequest(
    String body,
    Map<String, List<String>> headers,   // keys normalised to lower-case by the router
    Map<String, String> queryParams,
    HttpMethod method,
    String requestUrl                     // full URL, e.g. "https://api.casehub.io/connectors/twilio-sms-inbound/webhook"
) {}
```

Pure-Java. The `WebhookRouter` translates JAX-RS types into `WebhookRequest` at the HTTP
boundary so that connector implementations and tests have no framework dependency.

`headers` keys are **normalised to lower-case** by the router before building the record.
HTTP headers are case-insensitive; lower-casing at the boundary means connectors use
`request.headers().get("x-slack-signature")` throughout without per-site case handling.

`requestUrl` is required for Twilio's `X-Twilio-Signature` scheme: HMAC-SHA1 over the full
URL + sorted form params. The router populates it from `UriInfo.getAbsolutePath().toString()`
for POST and `UriInfo.getRequestUri().toString()` for GET.

**Reverse proxy note for Twilio:** If the service runs behind an ingress or load balancer,
the container URL (`http://10.0.0.1:8080/connectors/...`) differs from what Twilio signed
(`https://api.casehub.io/connectors/...`). Configure:

```properties
quarkus.http.proxy.proxy-address-forwarding=true
quarkus.http.proxy.allow-forwarded=true
```

Without this, every Twilio request fails signature verification silently (returns `Ignored()` +
WARNING log).

### `WebhookResult`

```java
public sealed interface WebhookResult {
    record Delivered(List<InboundMessage> messages)            implements WebhookResult {}
    record Challenged(String responseBody, String contentType) implements WebhookResult {}
    record Ignored()                                           implements WebhookResult {}
    /** Signature invalid, replay detected, or challenge token wrong.
     *  POST → HTTP 200 + SECURITY log (suppress retries).
     *  GET  → HTTP 403 (admin setup failure — visible in platform console). */
    record Unauthorized()                                      implements WebhookResult {}
}
```

`Delivered` takes `List<InboundMessage>` to handle platforms that batch multiple events per
webhook call. `Challenged` carries `contentType` because Slack expects `application/json`
while WhatsApp expects `text/plain`.

**`Unauthorized` HTTP mapping depends on method.** For POST: 200 OK + WARNING security log
(suppress automated retries — Slack retries for up to 30 days on non-2xx). For GET: 403
(admin console setup failure — the admin sees this and knows to fix their configuration). The
router applies the correct mapping based on `request.method()`.

### `WebhookInboundConnector` — webhook-based transports, standalone

```java
public abstract class WebhookInboundConnector {

    /**
     * Unique identifier. Must be lowercase, URL-safe, no slashes or spaces.
     * Also serves as the URL path segment: POST /connectors/{id}/webhook.
     */
    public abstract String id();

    /**
     * Handle an inbound HTTP request (GET or POST).
     *
     * <p><b>Connector contract:</b> must not throw. Catch all exceptions internally and
     * return {@link WebhookResult.Ignored} (or {@link WebhookResult.Unauthorized} for auth
     * failures). The router wraps {@code handle()} in a try-catch as defense-in-depth
     * against bugs, but that catch is a last resort — it is not a substitute for
     * connector-level error handling.
     */
    public abstract WebhookResult handle(WebhookRequest request);
}
```

`WebhookInboundConnector` does **not** implement `InboundConnector`. The two types are
independent:

- `InboundConnector`: pull-based lifecycle (start/stop), managed by `InboundConnectorService`
- `WebhookInboundConnector`: stateless handler, managed by `WebhookRouter`

Both deliver messages through `InboundConnectorService.receive()`. There are no shared
lifecycle no-ops, and no `instanceof` checks needed.

`WebhookRouter` has its own CDI registry: `@All List<WebhookInboundConnector>`. The CDI
type hierarchy keeps the two lists separate.

### `InboundConnectorService`

```java
@ApplicationScoped
public class InboundConnectorService {

    private final Map<String, InboundConnector> pullRegistry;
    private final Event<InboundMessage> messageEvent;

    InboundConnectorService(@All List<InboundConnector> pullConnectors,
                            Event<InboundMessage> messageEvent) {
        this.messageEvent = messageEvent;
        pullConnectors.forEach(c -> validateId(c.id()));
        this.pullRegistry = pullConnectors.stream().collect(Collectors.toMap(
            InboundConnector::id, Function.identity(),
            (a, b) -> { throw new IllegalStateException(
                "Duplicate inbound connector id: '" + a.id() + "'"); }));
    }

    private static void validateId(String id) {
        if (!id.matches("[a-z0-9][a-z0-9\\-]*")) {
            throw new IllegalStateException(
                "InboundConnector id '" + id
                + "' is invalid — must be lowercase, URL-safe, no slashes or spaces");
        }
    }

    void onStart(@Observes StartupEvent ignored) {
        pullRegistry.values().forEach(c -> c.start(this::receive));
    }

    void onStop(@Observes ShutdownEvent ignored) {
        pullRegistry.values().forEach(InboundConnector::stop);
    }

    /**
     * Fire a synchronous CDI {@code Event<InboundMessage>}. Called by pull connectors
     * via the sink and directly by {@code WebhookRouter} for webhook connectors.
     *
     * <p>CDI fire is synchronous. Observers that require async processing must dispatch
     * to their own executor — do not perform blocking I/O in an {@code @Observes}
     * method. Slack's retry deadline is 3 seconds; a slow observer breaks that budget.
     */
    public void receive(InboundMessage message) {
        messageEvent.fire(message);
    }

    public Set<String> pullIds() {
        return Set.copyOf(pullRegistry.keySet());
    }
}
```

**ID validation** runs at construction in both `InboundConnectorService` (pull connectors)
and `WebhookRouter` (webhook connectors). The pattern `[a-z0-9][a-z0-9\-]*` enforces
lowercase, URL-safe, no slashes or spaces. Startup failure is better than a silent HTTP
routing bug discovered in production.

**CDI event is synchronous.** Observers must not perform blocking I/O inline. The Qhorus
bridge (connectors#6) must dispatch asynchronously (e.g. via `Event.fireAsync()` internally,
or a `ManagedExecutor`) before writing to the database. Slack's 3-second ACK deadline leaves
no margin for a synchronous DB write in the observer chain.

---

## Webhook Module

### `WebhookRouter`

```java
@Path("/connectors")
@ApplicationScoped
public class WebhookRouter {

    private static final Logger LOG = Logger.getLogger(WebhookRouter.class.getName());

    private final Map<String, WebhookInboundConnector> webhookRegistry;
    private final InboundConnectorService service;

    @Inject
    WebhookRouter(@All List<WebhookInboundConnector> connectors,
                  InboundConnectorService service) {
        this.service = service;
        this.webhookRegistry = connectors.stream().collect(Collectors.toMap(
            WebhookInboundConnector::id, Function.identity(),
            (a, b) -> { throw new IllegalStateException(
                "Duplicate webhook connector id: '" + a.id() + "'"); }));
        // Validate ID format at startup for all webhook connectors
        webhookRegistry.keySet().forEach(WebhookRouter::validateId);
    }

    @POST
    @Path("/{id}/webhook")
    @Consumes({ "application/json", "application/x-www-form-urlencoded" })
    public Response post(@PathParam("id") String id,
                         @Context HttpHeaders httpHeaders,
                         @Context UriInfo uriInfo,
                         String body) {
        return dispatch(id, new WebhookRequest(
            body,
            lowerCaseKeys(httpHeaders.getRequestHeaders()),
            Map.of(),
            HttpMethod.POST,
            uriInfo.getAbsolutePath().toString()));
    }

    @GET
    @Path("/{id}/webhook")
    public Response get(@PathParam("id") String id,
                        @Context HttpHeaders httpHeaders,
                        @Context UriInfo uriInfo) {
        Map<String, String> flat = uriInfo.getQueryParameters().entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey,
                e -> e.getValue().isEmpty() ? "" : e.getValue().get(0)));
        return dispatch(id, new WebhookRequest(
            "",
            lowerCaseKeys(httpHeaders.getRequestHeaders()),
            flat,
            HttpMethod.GET,
            uriInfo.getRequestUri().toString()));
    }

    private Response dispatch(String id, WebhookRequest request) {
        WebhookInboundConnector connector = webhookRegistry.get(id);
        if (connector == null) {
            return Response.status(404)
                .entity("No webhook connector registered for id '" + id + "'").build();
        }
        try {
            return switch (connector.handle(request)) {
                case WebhookResult.Delivered(var msgs) -> {
                    msgs.forEach(service::receive);
                    yield Response.ok().build();
                }
                case WebhookResult.Challenged(var body, var ct) ->
                    Response.ok(body).type(ct).build();
                case WebhookResult.Ignored() -> Response.ok().build();
                case WebhookResult.Unauthorized() -> {
                    LOG.warning("SECURITY: rejected webhook request for connector '"
                        + id + "' — signature invalid or replay detected. method="
                        + request.method() + " url=" + request.requestUrl());
                    // POST: return 200 to suppress automated platform retries
                    //       (Slack retries non-2xx for up to 30 days).
                    // GET:  return 403 — admin console setup; human sees it directly
                    //       and needs a clear failure signal to fix their config.
                    yield request.method() == HttpMethod.GET
                        ? Response.status(403).build()
                        : Response.ok().build();
                }
            };
        } catch (Exception e) {
            // Connector.handle() threw — return 200 to suppress retries, log the failure.
            LOG.log(Level.SEVERE, "Unexpected exception in webhook connector '" + id
                + "': " + e.getMessage(), e);
            return Response.ok().build();
        }
    }

    // HTTP headers are case-insensitive — normalise at the boundary.
    private static Map<String, List<String>> lowerCaseKeys(
            MultivaluedMap<String, String> headers) {
        return headers.entrySet().stream().collect(Collectors.toMap(
            e -> e.getKey().toLowerCase(Locale.ROOT),
            Map.Entry::getValue,
            (a, b) -> { List<String> m = new ArrayList<>(a); m.addAll(b); return m; }));
    }

    /** Returns the ids of all registered webhook connectors. */
    public Set<String> webhookIds() {
        return Set.copyOf(webhookRegistry.keySet());
    }

    private static void validateId(String id) {
        if (!id.matches("[a-z0-9][a-z0-9\\-]*")) {
            throw new IllegalStateException(
                "Webhook connector id '" + id
                + "' is invalid — must be lowercase, URL-safe, no slashes or spaces");
        }
    }
}
```

### Concrete Implementations

All four extend `WebhookInboundConnector`. Shared contract for all implementations:

- **Constant-time HMAC comparison**: `MessageDigest.isEqual(expected, actual)`. Never
  `String.equals()` or `Arrays.equals()` — both are timing-attackable.
- **Blank secret → `Ignored()` + WARNING log**: connector is present but inactive. This is
  a deliberate choice matching the outbound no-op pattern. **Observability gap:** from the
  platform's perspective, `Ignored()` and `Delivered()` both produce HTTP 200. A message
  dropped due to a missing secret is invisible without consulting logs. There is currently no
  operational state on connectors. This gap is acknowledged; connectors#6 may surface it via
  bridge metrics.
- **Exception safety**: `handle()` must not throw. Catch all exceptions internally, return
  `Ignored()` with an ERROR log.

---

#### `SlackInboundConnector` — id: `slack-inbound`

**Integration model:** Slack Incoming Events API (not Bot Framework).

**Signature:** HMAC-SHA256 of `"v0:" + timestamp + ":" + body` using the signing secret.
Compare result to `x-slack-signature` header (`v0=<hex>`). Use `MessageDigest.isEqual()`.

**Replay prevention:** Reject requests where `x-slack-request-timestamp` is more than
5 minutes from the current time (`Math.abs(Instant.now().getEpochSecond() - ts) > 300`).
This must be checked **before** the HMAC computation to avoid wasting CPU on stale requests.
Slack explicitly requires this check in their security documentation. Teams, WhatsApp, and
Twilio do not embed request timestamps in their signing schemes — no equivalent check for
those connectors.

**Check ordering in `handle()`** (Slack-specific, matters for initial setup):

1. URL verification check — **before** blank-secret guard
2. Blank-secret guard → `Ignored()`
3. Replay prevention (timestamp age check)
4. HMAC signature verification
5. Message parsing and filtering

URL verification must be first because Slack sends it **before** the operator has configured
the signing secret. If the blank-secret guard ran first, initial setup would always fail.
Once a secret is configured, URL verification events bypass signature checking — the challenge
is the authentication for this one event type.

**URL verification bypass:** If the POST body is `{"type":"url_verification","challenge":"..."}`,
the connector returns `Challenged({"challenge":"..."}, "application/json")` **without checking
the HMAC signature**. This is intentional — documented here so a future reader does not "fix"
it and break initial setup in live workspaces.

**Message filtering:** Return `Ignored()` for events with a `bot_id` field, events where
`type != "event_callback"`, and `event.type != "message"`.

**Config:** `casehub.connectors.slack-inbound.signing-secret` (default: blank)

---

#### `TeamsInboundConnector` — id: `teams-inbound`

**Integration model:** Teams [Outgoing Webhooks](https://learn.microsoft.com/en-us/microsoftteams/platform/webhooks-and-connectors/how-to/add-outgoing-webhook)
(not Bot Framework / Azure AD OAuth). Teams Outgoing Webhooks use HMAC-SHA256 with a shared
secret — no JWT, no Azure registration required. Bot Framework webhooks use JWT OAuth token
validation and are a completely different implementation model. This spec targets Outgoing
Webhooks.

**Signature algorithm (Outgoing Webhooks):**
1. Base64-decode the shared secret configured in Teams when the outgoing webhook was registered
2. Compute `HMAC-SHA256(decoded_secret, utf8_bytes(body))`
3. Base64-encode the HMAC result
4. Compare to the `authorization` header value (`HMAC <base64>`) using `MessageDigest.isEqual()`

Teams Outgoing Webhooks do not use GET challenges. `handle()` returns `Ignored()` for
`HttpMethod.GET` — no Teams admin flow triggers a GET to this endpoint.

**Config:** `casehub.connectors.teams-inbound.shared-secret` (default: blank, base64-encoded
as provided by Teams)

---

#### `WhatsAppInboundConnector` — id: `whatsapp-inbound`

**Integration model:** WhatsApp Business API (Meta Cloud API webhooks).

**GET challenge (subscription verification):**
- `hub.mode == "subscribe"` AND `hub.verify_token == configured-verify-token`
- Return `Challenged(hub.challenge, "text/plain")`
- Any other GET → `Unauthorized()`

**POST signature:** `X-Hub-Signature-256: sha256=<hex>`. HMAC-SHA256 of the raw body using
the app secret. Constant-time compare with `MessageDigest.isEqual()`.

**Message parsing:** WhatsApp sends structured JSON with message objects. Text messages
set `content` to message text. Media messages (image, audio, document, sticker) set
`content` to the media URL if available, empty string otherwise (v1 limitation —
see `InboundMessage.content` above).

**Config:**
- `casehub.connectors.whatsapp-inbound.app-secret` — HMAC signing key (default: blank)
- `casehub.connectors.whatsapp-inbound.verify-token` — GET challenge token (default: blank)

---

#### `TwilioSmsInboundConnector` — id: `twilio-sms-inbound`

**Integration model:** Twilio Webhooks (form-encoded POST).

**Signature:** `X-Twilio-Signature` — HMAC-SHA1 of the full request URL concatenated with
all POST parameters sorted alphabetically (key+value, no separator). The URL must match what
Twilio used when it sent the request. See **reverse proxy note** in `WebhookRequest` above.

SHA-1 is Twilio's specified algorithm per their [webhook security docs](https://www.twilio.com/docs/usage/security);
it is not configurable. Security audits that flag SHA-1 here should note it is a platform
constraint, not a design choice.

Constant-time compare with `MessageDigest.isEqual()`.

**Fields:** `From` (E.164 sender number) → `externalSenderId`; `To` (destination number) →
`externalChannelRef`; `Body` → `content`.

**Config:** `casehub.connectors.twilio-sms-inbound.auth-token` (default: blank — required
for signature verification; same token used for outbound Twilio API calls)

---

## Configuration Summary

| Property | Connector |
|----------|-----------|
| `casehub.connectors.slack-inbound.signing-secret` | Slack |
| `casehub.connectors.teams-inbound.shared-secret` | Teams (base64, as shown in Teams UI) |
| `casehub.connectors.whatsapp-inbound.app-secret` | WhatsApp POST sig |
| `casehub.connectors.whatsapp-inbound.verify-token` | WhatsApp GET challenge |
| `casehub.connectors.twilio-sms-inbound.auth-token` | Twilio |

All default to blank. Blank = `Ignored()` + WARNING log on every request. See observability
gap note under concrete implementations.

---

## Response-Time Constraints

| Platform | ACK deadline | Retry behaviour on non-2xx |
|----------|-------------|---------------------------|
| Slack | **3 seconds** | Exponential backoff, up to 30 days |
| Twilio | 15 seconds | Up to 11 retries over 24 hours |
| WhatsApp | ~20 seconds | Platform-controlled retry |
| Teams | ~5 seconds | Teams-controlled retry |

**Slack's 3-second deadline is the binding constraint** for the system as a whole.

The router fires a synchronous CDI event (`messageEvent.fire()`). Observer chain total time
must stay well under 3 seconds. The Qhorus bridge (connectors#6) **must not** perform
synchronous database writes in its `@Observes InboundMessage` method. It must dispatch
asynchronously (e.g. `Event.fireAsync()` internally or a `ManagedExecutor`) and return
immediately.

All webhook platforms receive HTTP 200 regardless of whether the message was processed
successfully downstream — 200 means "transport accepted", not "message delivered to domain".

---

## Testing

### `core` — pure Java, no CDI

**`InboundConnectorServiceTest`** (pull-only registry):
- Registry construction: registers pull connectors, detects duplicate ids
- `onStart()`: calls `start(sink)` only on pull connectors (no webhook connectors present)
- `onStop()`: calls `stop()` on all pull connectors
- `receive()`: calls the provided sink consumer (recording lambda)
- ID validation: rejects non-URL-safe ids at construction

**Per-connector unit tests** (e.g. `SlackInboundConnectorTest`):
- Valid signature + message event → `Delivered`
- Invalid signature → `Unauthorized` (constant-time path verified)
- Slack url_verification POST → `Challenged` (no sig check — intentional)
- Replay: timestamp > 5 minutes old → `Unauthorized` (before HMAC computed)
- Bot message → `Ignored`
- Blank signing secret → `Ignored` + warning
- WhatsApp GET valid token → `Challenged("hub_challenge", "text/plain")`
- WhatsApp GET wrong token → `Unauthorized`
- Twilio form-encoded body → `Delivered` with correct field mapping

### `webhook` — `@QuarkusTest`

**`WebhookRouterTest`**:
- Unknown connector id → 404
- Valid Slack POST → 200; `InboundMessage` CDI event captured by test observer
- Invalid Slack signature → 200 (suppressed — not 401); security WARNING logged
- Slack replay (stale timestamp) → 200; logged
- `connector.handle()` throws → 200; ERROR logged; no exception propagated
- WhatsApp GET challenge → 200, body = challenge token
- WhatsApp GET wrong token → 403 (GET Unauthorized maps to 403, not 200)
- Twilio form-encoded POST → 200; InboundMessage delivered

Test secrets in `webhook/src/test/resources/application.properties`. A
`@ApplicationScoped` `@Observes InboundMessage` capture bean records fired events for
assertion. Uses synchronous CDI fire — `@ObservesAsync` is not used here (GE-20260513-b15933
documents silent non-delivery in `@QuarkusTest`).

---

## Deferred Items (filed as issues)

| Issue | Description |
|-------|-------------|
| connectors#6 | Qhorus bridge — `InboundMessage` CDI event → `MessageService.dispatch()` |
| connectors#7 | Email inbound — IMAP/SMTP polling, pull-based lifecycle |
| parent#89 | Platform docs update — `casehub-connectors.md` + `PLATFORM.md` capability table |
| work#234 | Route `InboundMessage` to WorkItem creation |
