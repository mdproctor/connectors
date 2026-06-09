# MCP Tools for SlackBot — Design Spec

**Issue:** connectors#16  
**Branch:** issue-16-mcp-slack-bot-tools  
**Date:** 2026-06-08 (revised after code review)

---

## Problem

The `casehub-connectors-mcp` module exposes `send_slack` via incoming webhook URL. Webhook
URLs are per-channel credentials the caller supplies at call time — agents cannot discover
available channels, cannot reply to threads, and must manage URLs as secrets in every call.

`SlackBotClient` (shipped in connectors#2) provides a richer model: a single configured bot
token enables multi-channel posting, thread replies, and channel discovery via the Slack Web
API. The MCP surface has not been updated to reflect this.

---

## Goals

1. Expose `send_slack_bot` — bot-token-based Slack posting that returns the message `ts` for
   thread replies.
2. Expose `list_channels` — generic channel discovery across all connectors that support it.
3. Introduce `ConnectorDiscovery` and `DiscoveredTarget` as reusable SPIs so Discord,
   Telegram, and the planned Quarkus demo chat service can register discoverable targets
   without a new MCP tool each time.
4. Fix the latent `@Blocking` omission on all existing MCP tools.

---

## Design

### `DiscoveredTarget` — `casehub-connectors-core`

Top-level record in `io.casehub.connectors`:

```java
public record DiscoveredTarget(String id, String displayName) {}
```

Not nested inside `ConnectorDiscovery`. A nested record inside an interface is valid Java but
unconventional for a public SPI — external implementors write
`ConnectorDiscovery.DiscoveredTarget` at every call site. A top-level record in the same
package is conventional and importable independently.

- `id` — what the agent passes to `send_slack_bot` (e.g. `C123ABC`)
- `displayName` — human-readable label (e.g. `#general`)

### `ConnectorDiscovery` SPI — `casehub-connectors-core`

```java
/**
 * Optional SPI for connectors whose delivery targets are discoverable at runtime.
 *
 * <p>Implementations are {@code @ApplicationScoped} CDI beans discovered automatically.
 * The {@code list_channels} MCP tool aggregates all registered implementations.
 *
 * <h2>Contract for implementations</h2>
 * <ul>
 * <li>Must not throw — exceptions propagate to the MCP tool caller and silence all
 *     other discoveries. Catch internally and return an empty list on failure.</li>
 * <li>Must return quickly — no long-running blocking calls without virtual-thread
 *     offloading.</li>
 * </ul>
 */
public interface ConnectorDiscovery {
    String id();
    List<DiscoveredTarget> discover();
}
```

Completely separate from the `Connector` SPI — a connector may implement one, both, or
neither. `id()` links results to their source connector for `list_channels` output
formatting. Named `id()` (not `connectorId()`) — consistent with `Connector.id()` and
`InboundConnector.id()` platform convention.

No pagination contract in the SPI — each implementation decides internally (see Known
Limitations).

### `SlackBotClient` changes — `casehub-connectors-slack-bot`

**`SlackBotClient` remains a pure HTTP client** — token always passed at call time, consistent
with `postMessage(String token, ...)`. The `casehub-connectors-slack-bot.token` config property
belongs to callers that need it (`SlackBotMcpTool`, `SlackBotDiscovery`), not to the shared
HTTP client that Qhorus also uses.

**New constant:**

```java
public static final String ID = "slack-bot";
```

Mirrors `SlackConnector.ID`, `TeamsConnector.ID`. Used by `SlackBotDiscovery.id()`
and `SlackBotMcpTool`'s bridge call. A renamed constant compiles; a renamed string literal
silently diverges from connected bridge receivers.

**New `listChannels` method** — calls `GET /api/conversations.list`:

```java
public List<DiscoveredTarget> listChannels(String token) {
    final HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(apiBaseUrl + "/api/conversations.list"
                    + "?types=public_channel,private_channel&limit=200"))
            .header("Authorization", "Bearer " + token)
            .timeout(REQUEST_TIMEOUT)
            .GET()
            .build();
    try {
        final HttpResponse<String> response =
                HttpHelper.CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        return parseChannels(response.body());
    } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        return List.of();
    } catch (final Exception e) {
        LOG.warning("SlackBotClient: listChannels HTTP error — " + e.getMessage());
        return List.of();
    }
}

private List<DiscoveredTarget> parseChannels(String body) {
    if (body == null || body.isBlank()) return List.of();
    try (var reader = Json.createReader(new StringReader(body))) {
        final JsonObject obj = reader.readObject();
        if (!obj.getBoolean("ok", false)) return List.of();
        return obj.getJsonArray("channels").stream()
                .map(v -> v.asJsonObject())
                .map(ch -> new DiscoveredTarget(
                        ch.getString("id"),
                        "#" + ch.getString("name")))
                .toList();
    } catch (Exception e) {
        LOG.warning("SlackBotClient: listChannels parse error — " + e.getMessage());
        return List.of();
    }
}
```

`GET` request. Same `REQUEST_TIMEOUT` and `HttpHelper.CLIENT` as `postMessage()`. No
`Content-Type` header (GET has no body). Parsing mirrors `parseResponse()` — `Json.createReader()`,
no JSONB, no Jackson. Maps directly to `DiscoveredTarget` — no intermediate record types.

`listChannels()` calls `HttpHelper.CLIENT.send()` (blocking). `@Blocking` lives on the MCP
tool method, not here.

**No `botToken` field. No 3-param `postMessage` overload.** The existing
`postMessage(String token, String channelId, String text, String threadTs)` is unchanged.

### `SlackBotDiscovery` — `casehub-connectors-slack-bot`

New `@ApplicationScoped` bean. Owns the `ConnectorDiscovery` implementation and the MCP
token config property for discovery.

```java
@ApplicationScoped
public class SlackBotDiscovery implements ConnectorDiscovery {

    private final SlackBotClient slackBotClient;
    private final String botToken;

    @Inject
    SlackBotDiscovery(SlackBotClient slackBotClient,
                      @ConfigProperty(name = "casehub.connectors.slack-bot.token",
                                      defaultValue = "") String botToken) {
        this.slackBotClient = slackBotClient;
        this.botToken = botToken;
    }

    @Override
    public String id() { return SlackBotClient.ID; }

    @Override
    public List<DiscoveredTarget> discover() {
        if (botToken.isBlank()) return List.of();
        return slackBotClient.listChannels(botToken);
    }
}
```

`botToken` is a constructor parameter — immutable after construction, no field injection.
Blank-token fast-fail: returns empty list without an HTTP round-trip.

`SlackBotDiscovery` is separate from `SlackBotClient` because `botToken` is MCP-deployment-
specific configuration that is noise in a shared HTTP client bean also used by Qhorus (which
injects its own token from `casehub.qhorus.slack.bot.token` and passes it at call time).
Keeping these concerns separate preserves `SlackBotClient` as a pure HTTP client.

### `SlackBotMcpTool` — `casehub-connectors-mcp`

Injects `SlackBotClient` directly and the bot token via `@ConfigProperty`. Does not route
through `ConnectorService` — the `Connector` SPI is void (no return value) and has no
threading concept; forcing bot posting through it would discard the `ts`.

```java
@ApplicationScoped
public class SlackBotMcpTool {

    private final SlackBotClient slackBotClient;
    private final ConnectorMeshBridge meshBridge;
    private final String botToken;

    @Inject
    SlackBotMcpTool(SlackBotClient slackBotClient, ConnectorMeshBridge meshBridge,
                    @ConfigProperty(name = "casehub.connectors.slack-bot.token",
                                    defaultValue = "") String botToken) {
        this.slackBotClient = slackBotClient;
        this.meshBridge = meshBridge;
        this.botToken = botToken;
    }

    @Tool(name = "send_slack_bot",
          description = "Posts a message to a Slack channel using a configured bot token. "
                      + "Returns the message timestamp (ts) on success — save it to reply "
                      + "in-thread. Requires casehub.connectors.slack-bot.token on the server. "
                      + "Returns 'Posted to <channel> (ts=<ts>)' on success or "
                      + "'Failed: <reason>' on error.")
    @Blocking
    public String sendSlackBot(
            @ToolArg(description = "Slack channel ID (e.g. C123ABC). "
                                 + "Use list_channels to discover available IDs.")
            String channel,
            @ToolArg(description = "Message text.")
            String text,
            @ToolArg(description = "Thread timestamp for in-thread replies. Use the ts from "
                                 + "a previous send_slack_bot call. Omit for a new message.",
                     required = false)
            String threadTs) {
        try {
            if (botToken.isBlank()) {
                return "Failed: casehub.connectors.slack-bot.token is not configured";
            }
            PostResult result = slackBotClient.postMessage(botToken, channel, text, threadTs);
            if (!result.ok()) {
                return "Failed: " + result.error();
            }
            meshBridge.notifyDelivered(SlackBotClient.ID, channel,
                    McpContentSanitizer.sanitize(text));
            return "Posted to " + channel + " (ts=" + result.ts() + ")";
        } catch (Exception e) {
            LOG.warnf("send_slack_bot failed [%s]: %s", e.getClass().getSimpleName(),
                    e.getMessage());
            return "Failed: " + e.getMessage();
        }
    }
}
```

**Blank-token guard:** fast-fails before any HTTP call. Slack would return `invalid_auth` if
called with a blank token — the guard makes the misconfiguration diagnosis immediate.

**Bridge called only on `result.ok()`** — `ok=false` from Slack is not a delivered message.

**`SlackBotClient.ID`** used in the bridge call — not a raw string literal.

**Channel IDs only** — the `@ToolArg` description does not offer channel names. `list_channels`
returns IDs; agents should use those. Channel names work in some Slack API paths but are
deprecated in others and require an extra resolution step that `SlackBotClient` does not
perform.

### `ChannelDiscoveryMcpTool` — `casehub-connectors-mcp`

```java
@ApplicationScoped
public class ChannelDiscoveryMcpTool {

    private final List<ConnectorDiscovery> discoveries;

    @Inject
    ChannelDiscoveryMcpTool(@All List<ConnectorDiscovery> discoveries) {
        this.discoveries = discoveries;
    }

    @Tool(name = "list_channels",
          description = "Lists discoverable channels across all configured connectors "
                      + "(e.g. Slack Bot). Returns channel IDs to use with send_slack_bot. "
                      + "Only connectors with a token configured appear in the output.")
    @Blocking
    public String listChannels() {
        StringBuilder sb = new StringBuilder();
        for (ConnectorDiscovery d : discoveries) {
            List<DiscoveredTarget> targets;
            try {
                targets = d.discover();
            } catch (Exception e) {
                LOG.warnf("ConnectorDiscovery[%s] threw: %s",
                        d.id(), e.getMessage());
                continue;
            }
            if (targets.isEmpty()) continue;
            sb.append(d.id()).append(":\n");
            for (DiscoveredTarget t : targets) {
                sb.append("  ").append(t.displayName())
                  .append(" (").append(t.id()).append(")\n");
            }
        }
        return sb.isEmpty() ? "No channels discovered." : sb.toString().stripTrailing();
    }
}
```

**`@All List<ConnectorDiscovery>`** — the Quarkus ARC idiom for collecting SPI beans,
consistent with `ConnectorService(@All List<Connector>)` and
`InboundConnectorService(@All List<InboundConnector>)`. Field type is `List<ConnectorDiscovery>`.

**Single constructor.** `@Inject` and `@All` are CDI annotations — they do not prevent
direct `new` construction in tests. Tests call
`new ChannelDiscoveryMcpTool(List.of(new StubDiscovery(...)))` directly.

**Per-discovery try/catch** — a buggy or network-impaired `ConnectorDiscovery` must not silence
all others. Each discovery call is wrapped individually. The SPI Javadoc says "must not throw"
as the primary contract; this is belt-and-suspenders.

No bridge call — discovery is a read operation.

### `ConnectorMeshBridge` Javadoc update — `casehub-connectors-core`

The `destination` parameter on `notifyDelivered()` currently says:
> `webhook URL, E.164 number, or email address`

`send_slack_bot` passes a Slack channel ID (e.g. `C123ABC`). Update to:
> `delivery target: webhook URL, E.164 number, email address, or channel ID`

This update is in scope for this branch.

### `send_slack` (webhook) — unchanged

The existing `send_slack` tool remains as-is. It serves a different use case: no bot setup
required, caller supplies the webhook URL per call. The two tools are complementary.

### `@Blocking` on existing MCP tools

All five existing tools call `HttpHelper.CLIENT.send()` through `ConnectorService`. None have
`@Blocking`. Per garden GE-20260604-96d82a, blocking calls from a `@Tool` method require
`@Blocking` to avoid stalling the Vert.x I/O thread. One-line fix on each:

- `SlackMcpTool.sendSlack`
- `TeamsMcpTool.sendTeams`
- `TwilioSmsMcpTool.sendSms`
- `WhatsAppMcpTool.sendWhatsApp`
- `EmailMcpTool.sendEmail`

### Module dependency

`mcp/pom.xml` gains:

```xml
<dependency>
  <groupId>io.casehub</groupId>
  <artifactId>casehub-connectors-slack-bot</artifactId>
  <version>0.2-SNAPSHOT</version>
</dependency>
```

### Configuration

| Property | Default | Description |
|---|---|---|
| `casehub.connectors.slack-bot.token` | `""` | Bot token (`xoxb-…`). Injected independently by `SlackBotMcpTool` and `SlackBotDiscovery`. Blank → fast-fail in `sendSlackBot`; empty list in `discover()`. |

### Return format

| Scenario | Return |
|---|---|
| `send_slack_bot` success | `"Posted to C123ABC (ts=1638535627.000200)"` |
| `send_slack_bot` blank token | `"Failed: casehub.connectors.slack-bot.token is not configured"` |
| `send_slack_bot` Slack error | `"Failed: channel_not_found"` |
| `send_slack_bot` HTTP/network error | `"Failed: <exception message>"` |
| `list_channels` with results | Multi-line formatted string (see ChannelDiscoveryMcpTool above) |
| `list_channels` nothing discovered | `"No channels discovered."` |

---

## Testing

### `McpToolTestSupport` — make fully public

`McpToolTestSupport` is currently `package-private final class` in `io.casehub.connectors.mcp`.
`SlackBotMcpToolTest` lives in `io.casehub.connectors.slack.bot` and cannot access a
package-private class from a different package.

Making the type declarations public is necessary but not sufficient. The fields that tests
assert against and `reset()` are package-private members — they remain inaccessible from
another package even after the enclosing type is made public:

| Member | Current visibility | After type-only fix |
|---|---|---|
| `RecordingBridge.lastConnectorId` | package-private field | ❌ still inaccessible |
| `RecordingBridge.lastDestination` | package-private field | ❌ still inaccessible |
| `RecordingBridge.lastContent` | package-private field | ❌ still inaccessible |
| `RecordingBridge.reset()` | package-private method | ❌ still inaccessible |
| `RecordingBridge.notifyDelivered()` | public (interface impl) | ✅ accessible |
| `RecordingConnector.lastMessage` | package-private field | ❌ still inaccessible |
| `RecordingConnector.reset()` | package-private method | ❌ still inaccessible |

**Complete fix:**

- `McpToolTestSupport` → `public final class`
- `RecordingBridge` → `public static final class`; `lastConnectorId`, `lastDestination`,
  `lastContent` → `public` fields; `reset()` → `public` method
- `RecordingConnector` → `public static final class`; `lastMessage` → `public` field;
  `reset()` → `public` method; constructor `RecordingConnector(String id)` → `public`
- `serviceWith()` — not needed from external packages in current tests (`SlackBotMcpTool`
  bypasses `ConnectorService` entirely); no change required

`RecordingConnector` members are included for consistency — future cross-package tests will
need them too.

This is an in-scope change for this branch.

### `SlackBotClientTest` additions

All in package `io.casehub.connectors.slack.bot`. WireMock stubs `GET /api/conversations.list`.

- `listChannels_returnsDiscoveredTargets` — stubs Slack response with two channels; verifies
  `DiscoveredTarget` list with correct `id` and `#`-prefixed `displayName`.
- `listChannels_sendsAuthorizationHeader` — verifies `Authorization: Bearer test-token` header
  is present in the request. Mirrors `postMessage_sendsAuthorizationHeader`.
- `listChannels_slackReturnsNotOk_returnsEmptyList` — stubs `{"ok":false}`; verifies
  empty list returned.

### `SlackBotDiscoveryTest`

Unit tests in `io.casehub.connectors.slack.bot`. Constructs via
`new SlackBotDiscovery(client, "xoxb-test")`. Uses WireMock for HTTP with `apiBaseUrl`
set directly on `SlackBotClient`.

- `discover_delegatesToClient_withConfiguredToken` — stubs `conversations.list`; verifies
  `DiscoveredTarget` list is returned with the correct values.
- `discover_blankToken_returnsEmptyListWithoutHttpCall` — constructs with `""` token;
  asserts `List.of()` returned AND
  `wireMock.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()))` — required because
  without this assertion a removed guard still passes (WireMock unmatched request → error
  caught → `List.of()` returned via error path).
- `id_returnsSlackBotId` — verifies `id()` returns `SlackBotClient.ID`.

### `SlackBotMcpToolTest`

In package `io.casehub.connectors.slack.bot` (in `mcp` module test sources) to access
`SlackBotClient.apiBaseUrl` package-private field. `botToken` is passed directly to the
constructor: `new SlackBotMcpTool(client, bridge, "xoxb-test")`. Uses WireMock and
`McpToolTestSupport.RecordingBridge` (now public — see above).

- `sendSlackBot_success_returnsPostedWithTs`
- `sendSlackBot_blankToken_returnsFailedWithoutHttpCall` — no WireMock stub; asserts
  the return string, bridge NOT called, AND
  `wireMock.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()))` — the last assertion
  is required to prove the guard actually prevented the HTTP call, not just that the error
  path happened to return the correct value.
- `sendSlackBot_slackReturnsNotOk_returnsFailedNoBridgeCall`
- `sendSlackBot_networkError_returnsFailedString`
- `sendSlackBot_withThreadTs_passesThreadTsToClient`
- `sendSlackBot_longText_contentTruncatedTo500InBridge`
- `sendSlackBot_success_bridgeCalledWithSlackBotIdChannelAndSanitizedText` — verifies
  `SlackBotClient.ID`, not a raw string literal, is passed to the bridge.

### `ChannelDiscoveryMcpToolTest`

Tests use the single `@Inject` constructor directly: `new ChannelDiscoveryMcpTool(list)`.
`StubDiscovery` — inner class implementing `ConnectorDiscovery`.

- `listChannels_singleConnector_formatsOutput`
- `listChannels_multipleConnectors_formatsAll`
- `listChannels_emptyDiscover_skipsConnector`
- `listChannels_noConnectors_returnsNoneDiscovered`
- `listChannels_discoveryThrows_logsWarnAndContinues` — one discovery throws; verifies
  the other's results still appear in output.

### Existing MCP tool tests

No logic changes — verify `@Blocking` addition compiles cleanly (tests continue to pass).

---

## Known Limitations

- `SlackBotClient.listChannels()` requests the first page only (`limit=200`). Large Slack
  workspaces (Enterprise Grid) may have more than 200 channels — those beyond the first page
  are silently absent from `list_channels` output. Pagination is deferred to a follow-up issue.

---

## Deferred Concerns

- parent#197 — Update PLATFORM.md capability ownership table
- parent#198 — Sync casehub-connectors.md deep-dive

---

## ARC42STORIES.MD Impact

The following sections of `ARC42STORIES.MD` will be updated at branch close (via journal merge):

- **§5 Building Block View** — add `SlackBotDiscovery`, `SlackBotMcpTool`,
  `ChannelDiscoveryMcpTool`, `DiscoveredTarget`, `ConnectorDiscovery` SPI to module diagram;
  update L3 Agent Bridge container
- **§9.2 Chapter Index** — add new chapter or extend C3 to cover bot MCP tools
- **§9.4 Layer × Chapter Matrix** — update L1 (ConnectorDiscovery SPI in core) and L3
  (new MCP tools in mcp)
- **§10 Architectural Decisions** — add `SlackBotDiscovery` separation rationale and
  `DiscoveredTarget` top-level placement decision
- **§12 Risks** — remove parent#191 entry (closed); add pagination known limitation

---

## Platform Coherence

- `send_slack_bot` calls `meshBridge.notifyDelivered(SlackBotClient.ID, ...)` on `ok=true`
  using the ID constant, not a raw string.
- `ConnectorDiscovery` SPI has no external SDK types in its signature.
- `listChannels()` uses `HttpHelper.CLIENT` (shared-http-client protocol).
- All `@Tool` methods that call blocking HTTP annotated `@Blocking` (GE-20260604-96d82a).
- No same-name `@Tool` overloads on any class (GE-20260430-b015f5).
- `@ToolArg(required = false)` for optional `threadTs` (GE-20260414-fa6489).
- `@All List<ConnectorDiscovery>` — consistent with `ConnectorService(@All List<Connector>)`
  and `InboundConnectorService(@All List<InboundConnector>)`.
- `DiscoveredTarget` as top-level record — conventional SPI placement for external
  implementors.
- `botToken` injected via `@ConfigProperty` constructor parameter on both `SlackBotMcpTool`
  and `SlackBotDiscovery` — immutable after construction, no field injection, tests pass the
  token directly to `new`. Not on `SlackBotClient` (shared HTTP client used by Qhorus too).
- `ConnectorMeshBridge.notifyDelivered()` Javadoc updated to include channel IDs as valid
  destination type.
