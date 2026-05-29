# Inbound Connector SPI — Design Spec

**Issue:** casehubio/connectors#4  
**Branch:** `issue-4-inbound-connector-spi`  
**Date:** 2026-05-29

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
- `WebhookInboundConnector` abstract base and sealed result type in `core`
- `InboundConnectorService` CDI bean in `core`
- New `webhook` module with `WebhookRouter` (JAX-RS) and four concrete implementations:
  Slack, Teams, WhatsApp, Twilio SMS
- Full test coverage: pure-Java unit tests for connectors; `@QuarkusTest` for the router

**Explicitly out of scope — filed as follow-up issues:**
- Qhorus bridge (`InboundMessage` → `MessageService.dispatch()`) — connectors#6
- Email inbound (IMAP polling) — connectors#7
- Platform doc updates (`casehub-connectors.md`, `PLATFORM.md`) — parent#89
- Routing `InboundMessage` to WorkItem creation — work#234

---

## Design Principles

**Symmetric with outbound.** The outbound pattern is `Connector` SPI + `ConnectorService`
CDI bean. Inbound mirrors it: `InboundConnector` SPI + `InboundConnectorService`. Callers
(CDI observers) receive `InboundMessage` events; they do not interact with connectors directly.

**Pure delivery infrastructure.** No domain knowledge here. An `InboundMessage` carries
transport metadata (`externalSenderId`, `externalChannelRef`) — it does not interpret the
message or decide what to do with it. That is the job of the Qhorus bridge (connectors#6)
and the routing layer (work#234).

**Impossible to misuse.** The `WebhookResult` sealed type forces the router to handle every
outcome — `Delivered`, `Challenged`, `Ignored`, `Unauthorized`. Signature verification
cannot be skipped by accident; returning `Unauthorized` is the only way to signal auth
failure. Java 21 exhaustive pattern matching makes this ergonomic.

**Zero cost when absent.** The `webhook` module is an optional submodule. Deployments that
do not need inbound webhooks do not include it. `core` gains the SPI types only — no REST
infrastructure in the base artifact.

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
`quarkus-rest` should not be a mandatory dependency for deployments that only use outbound.

---

## Core Types

### `InboundConnector` SPI

```java
package io.casehub.connectors;

public interface InboundConnector {
    String id();
    void start(InboundMessageSink sink);
    void stop();
}
```

`start()` is called once at Quarkus startup with the sink. Pull-based connectors (polling)
use the sink to deliver messages from their background loop. Webhook-based connectors override
`start()`/`stop()` as no-ops via `WebhookInboundConnector`.

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
| `content` | Message text |
| `receivedAt` | `Instant.now()` at parse time |
| `metadata` | Connector-specific extras — Slack workspace ID, message timestamp, Twilio message SID, etc. Observers that do not recognise a key ignore it. |

### `WebhookRequest`

```java
public record WebhookRequest(
    String body,
    Map<String, List<String>> headers,   // keys normalised to lower-case by the router
    Map<String, String> queryParams,
    String method,                        // "GET" or "POST"
    String requestUrl                     // full URL, e.g. "https://example.com/connectors/twilio-sms-inbound/webhook"
) {}
```

Pure-Java. The `WebhookRouter` translates JAX-RS types into `WebhookRequest` at the HTTP
boundary so that connector implementations and tests have no framework dependency.

`headers` keys are **normalised to lower-case** by the router before building the record.
HTTP headers are case-insensitive; lower-casing at the boundary means connectors use
`headers.get("x-slack-signature")` everywhere without case-insensitive lookup logic.

`requestUrl` is required for Twilio's signature scheme: `X-Twilio-Signature` is HMAC-SHA1
over the full URL concatenated with sorted form params. The router populates it from
`UriInfo.getAbsolutePath().toString()` (POST) or `UriInfo.getRequestUri().toString()` (GET).

### `WebhookResult`

```java
public sealed interface WebhookResult {
    record Delivered(List<InboundMessage> messages)            implements WebhookResult {}
    record Challenged(String responseBody, String contentType) implements WebhookResult {}
    record Ignored()                                           implements WebhookResult {}
    record Unauthorized()                                      implements WebhookResult {}
}
```

`Delivered` takes `List<InboundMessage>` to handle platforms that batch multiple events per
webhook call. `Challenged` carries `contentType` because Slack expects `application/json`
while WhatsApp expects `text/plain`.

### `WebhookInboundConnector`

```java
public abstract class WebhookInboundConnector implements InboundConnector {

    @Override
    public final void start(InboundMessageSink sink) {}

    @Override
    public final void stop() {}

    public abstract WebhookResult handle(WebhookRequest request);
}
```

`final` on `start()`/`stop()` enforces the abstraction: webhook connector lifecycle is owned
by JAX-RS. If a webhook connector needs startup initialisation beyond its endpoint, it uses
`@Observes StartupEvent` directly — that concern is orthogonal.

### `InboundConnectorService`

```java
@ApplicationScoped
public class InboundConnectorService {

    private final Map<String, InboundConnector> registry;
    private final Event<InboundMessage> messageEvent;

    InboundConnectorService(@All List<InboundConnector> connectors,
                            Event<InboundMessage> messageEvent) {
        this.messageEvent = messageEvent;
        this.registry = connectors.stream().collect(Collectors.toMap(
            InboundConnector::id, Function.identity(),
            (a, b) -> { throw new IllegalStateException(
                "Duplicate inbound connector id: '" + a.id() + "'"); }));
    }

    void onStart(@Observes StartupEvent ignored) {
        registry.values().forEach(c -> c.start(this::receive));
    }

    void onStop(@Observes ShutdownEvent ignored) {
        registry.values().forEach(InboundConnector::stop);
    }

    public void receive(InboundMessage message) {
        messageEvent.fire(message);
    }

    public Optional<InboundConnector> find(String id) {
        return Optional.ofNullable(registry.get(id));
    }

    public Set<String> ids() {
        return Set.copyOf(registry.keySet());
    }
}
```

Duplicate id detection mirrors `ConnectorService`. `receive()` fires a **synchronous** CDI
event — observers that need async processing own their own dispatch. Synchronous fire keeps
error propagation predictable and avoids the `@ObservesAsync` silent-delivery gotcha in
`@QuarkusTest` (GE-20260513-b15933).

---

## Webhook Module

### `WebhookRouter`

```java
@Path("/connectors")
@ApplicationScoped
public class WebhookRouter {

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
            "POST",
            uriInfo.getAbsolutePath().toString()));
    }

    @GET
    @Path("/{id}/webhook")
    public Response get(@PathParam("id") String id,
                        @Context UriInfo uriInfo) {
        Map<String, String> flat = uriInfo.getQueryParameters().entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey,
                e -> e.getValue().isEmpty() ? "" : e.getValue().get(0)));
        return dispatch(id, new WebhookRequest(
            "", Map.of(), flat, "GET",
            uriInfo.getRequestUri().toString()));
    }

    // HTTP headers are case-insensitive; normalise at the boundary so connectors
    // use simple Map.get("x-slack-signature") without case handling.
    private static Map<String, List<String>> lowerCaseKeys(MultivaluedMap<String, String> headers) {
        return headers.entrySet().stream()
            .collect(Collectors.toMap(
                e -> e.getKey().toLowerCase(Locale.ROOT),
                Map.Entry::getValue,
                (a, b) -> { List<String> merged = new ArrayList<>(a); merged.addAll(b); return merged; }));
    }

    private Response dispatch(String id, WebhookRequest request) {
        WebhookInboundConnector connector = webhookRegistry.get(id);
        if (connector == null) {
            return Response.status(404)
                .entity("No webhook connector registered for id '" + id + "'").build();
        }
        return switch (connector.handle(request)) {
            case WebhookResult.Delivered(var msgs) -> {
                msgs.forEach(service::receive);
                yield Response.ok().build();
            }
            case WebhookResult.Challenged(var body, var ct) ->
                Response.ok(body).type(ct).build();
            case WebhookResult.Ignored()      -> Response.ok().build();
            case WebhookResult.Unauthorized() -> Response.status(401).build();
        };
    }
}
```

The router has its own `Map<String, WebhookInboundConnector>` — CDI's
`@All List<WebhookInboundConnector>` gives the correctly-typed sublist without a cast.
`InboundConnectorService` independently holds `@All List<InboundConnector>` (polymorphic).
No duplication of state — two typed views of the same CDI beans.

### Concrete Implementations

All four webhook connectors follow the same structure:

```java
@ApplicationScoped
public class SlackInboundConnector extends WebhookInboundConnector {

    public static final String ID = "slack-inbound";

    @ConfigProperty(name = "casehub.connectors.slack-inbound.signing-secret",
                    defaultValue = "")
    String signingSecret;

    @Override public String id() { return ID; }

    @Override
    public WebhookResult handle(WebhookRequest request) {
        if (signingSecret.isBlank()) {
            LOG.warning("slack-inbound: signing-secret not configured — ignoring request");
            return new WebhookResult.Ignored();
        }
        if ("POST".equals(request.method()) && isUrlVerification(request.body())) {
            return new WebhookResult.Challenged(challengeResponse(request.body()),
                                                "application/json");
        }
        if (!verifySignature(request)) return new WebhookResult.Unauthorized();
        List<InboundMessage> messages = parseMessages(request.body());
        return messages.isEmpty()
            ? new WebhookResult.Ignored()
            : new WebhookResult.Delivered(messages);
    }
    // verifySignature: HMAC-SHA256("v0:<timestamp>:<body>") vs X-Slack-Signature
    // parseMessages: filters bot_id, non-message event types
}
```

WhatsApp additionally handles the `GET` challenge:

```java
if ("GET".equals(request.method())) {
    if ("subscribe".equals(request.queryParams().get("hub.mode"))
            && verifyToken.equals(request.queryParams().get("hub.verify_token"))) {
        return new WebhookResult.Challenged(
            request.queryParams().get("hub.challenge"), "text/plain");
    }
    return new WebhookResult.Unauthorized();
}
```

**Connector IDs and signature schemes:**

| Connector | ID | Signature |
|-----------|-----|-----------|
| `SlackInboundConnector` | `slack-inbound` | HMAC-SHA256 of `v0:<ts>:<body>` vs `X-Slack-Signature` |
| `TeamsInboundConnector` | `teams-inbound` | HMAC-SHA256 of body vs `Authorization` header |
| `WhatsAppInboundConnector` | `whatsapp-inbound` | HMAC-SHA256 of body vs `X-Hub-Signature-256` |
| `TwilioSmsInboundConnector` | `twilio-sms-inbound` | HMAC-SHA1 of URL+params vs `X-Twilio-Signature` |

**Webhook URLs:**  
`POST /connectors/{connector-id}/webhook` — the connector's `id()` is the path segment.

**Configuration:**

| Property | Connector |
|----------|-----------|
| `casehub.connectors.slack-inbound.signing-secret` | Slack |
| `casehub.connectors.teams-inbound.shared-secret` | Teams |
| `casehub.connectors.whatsapp-inbound.app-secret` | WhatsApp POST sig |
| `casehub.connectors.whatsapp-inbound.verify-token` | WhatsApp GET challenge |
| `casehub.connectors.twilio-sms-inbound.auth-token` | Twilio |

All default to blank. Blank secret = `Ignored()` + warning log. The connector remains in
the CDI context but inactive — same fail-open pattern as outbound Twilio/WhatsApp.

---

## Authentication Note

Webhook signature verification (Slack HMAC, X-Hub-Signature-256, X-Twilio-Signature) is
**transport-layer authentication** — it proves the request originated from the platform, not
an arbitrary caller. This is correctly in the connector, not in an RBAC/`@RolesAllowed`
framework. It does not conflict with `auth-retrofit-readiness`: the protocol governs
principal-based access control, not platform webhook signing.

`WebhookRouter` carries no `@Authenticated` or `@RolesAllowed` annotations, consistent with
all other foundation REST resources.

---

## Testing

### `core` — pure Java, no CDI

**`InboundConnectorServiceTest`**
- Registry construction: registers connectors, detects duplicate ids at construction
- `find()`: known id returns connector; unknown id returns empty
- `ids()`: returns all registered ids
- Lifecycle: `onStart()` calls `start(sink)` on all connectors; `onStop()` calls `stop()`
- `receive()`: calls the provided sink consumer (injected as a recording lambda in tests)

**Per-connector unit tests** (e.g. `SlackInboundConnectorTest`)
- Valid signature + message event → `Delivered`
- Invalid signature → `Unauthorized`
- `url_verification` POST → `Challenged` with JSON body
- Bot message → `Ignored`
- Blank signing secret → `Ignored` + warning

### `webhook` — `@QuarkusTest`

**`WebhookRouterTest`**
- Unknown connector id → 404
- Valid Slack POST → 200; `InboundMessage` CDI event captured by test observer
- Invalid Slack signature → 401
- WhatsApp GET challenge → 200, response body = challenge token
- WhatsApp GET with wrong verify token → 401
- Twilio SMS form-encoded POST → 200; `InboundMessage` delivered

Test secrets configured in `webhook/src/test/resources/application.properties`. A
`@ApplicationScoped` `@Observes InboundMessage` capture bean records fired events for
assertion. Uses synchronous CDI fire — `@ObservesAsync` must not be used here (GE-20260513-b15933).

---

## Deferred Items (filed as issues)

| Issue | Description |
|-------|-------------|
| connectors#6 | Qhorus bridge — `InboundMessage` CDI event → `MessageService.dispatch()` |
| connectors#7 | Email inbound — IMAP/SMTP polling, pull-based lifecycle |
| parent#89 | Platform docs update — `casehub-connectors.md` + `PLATFORM.md` capability table |
| work#234 | Route `InboundMessage` to WorkItem creation |
