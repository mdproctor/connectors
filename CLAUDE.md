# connectors Workspace
**Name:** casehub-connectors
**Project repo:** /Users/mdproctor/claude/casehub/connectors
**Workspace type:** public

## Session Start

Run `add-dir /Users/mdproctor/claude/casehub/connectors` before any other work.

## Artifact Locations

| Skill | Writes to |
|-------|-----------|
| brainstorming (specs) | `specs/` |
| writing-plans (plans) | `plans/` |
| handover | `HANDOFF.md` |
| idea-log | `IDEAS.md` |
| design-snapshot | `snapshots/` |
| java-update-design / update-primary-doc | `design/JOURNAL.md` (created by `epic`) |
| adr | `adr/` |
| write-blog | `blog/` |

## Structure

- `HANDOFF.md` — session handover (single file, overwritten each session)
- `IDEAS.md` — idea log (single file)
- `specs/` — brainstorming / design specs (superpowers output)
- `plans/` — implementation plans (superpowers output)
- `snapshots/` — design snapshots with INDEX.md (auto-pruned, max 10)
- `adr/` — architecture decision records with INDEX.md
- `blog/` — project diary entries with INDEX.md
- `design/` — epic journal (created by `epic` at branch start)

## Git Discipline

Two git repositories are active in every session: a **workspace** (methodology artifacts: handover, blog, specs, plans, ADRs) and the **project repo** (source code).

Before any git operation, run `git rev-parse --show-toplevel` to confirm which repo is currently active. Do not assume — the session may have opened in either. cd to the correct repo before staging:
- Source code commits → project repo
- Methodology artifacts → workspace


## Rules

- All methodology artifacts go here, not in the project repo
- Promotion to project repo is always explicit — never automatic
- Workspace branches mirror project branches — switch both together

## Routing

| Artifact   | Destination | Notes |
|------------|-------------|-------|
| adr        | project     | lands in `docs/adr/` — promoted at epic close |
| specs      | project     | lands in `docs/specs/` — promoted at epic close |
| blog       | project     | lands in `docs/blog/` — promoted at work end |
| plans      | workspace   | stay in workspace permanently |
| design     | project     | journal file lives in workspace design/; merge target is project ARC42STORIES.MD (§10 for ADRs; was docs/DESIGN.md — now retired pending cleanup) |
| snapshots  | workspace   | stay in workspace permanently |
| handover   | workspace   | |

---

# casehub-connectors — Claude Code Project Guide

## Platform Docs
- [Platform Index](https://raw.githubusercontent.com/casehubio/parent/main/docs/INDEX.md) — discovery index (start here)
- [Building Platform](https://raw.githubusercontent.com/casehubio/parent/main/docs/guides/building-platform.md) — platform contributor guide

## Repo Guide

This repo owns its own documentation, synced to parent via CI:
- `docs/guides/consumer-guide.md` — for app builders: modules, APIs, quick start
- `docs/guides/contributor-guide.md` — for platform builders: architecture, SPIs, internals

Update the relevant guide in the same session when implementation changes modules, SPIs, or public APIs. Do not defer — drift compounds.

Read `docs/guides/consumer-guide.md` for app-level work. Only read `docs/guides/contributor-guide.md` when modifying this repo's internals or extension points.

## Reference Documents (casehub-parent)

| Document | What it covers |
|----------|---------------|
| `../garden/docs/protocols/casehub/FOUNDATION-INDEX.md` | CaseHub foundation protocols |

---

## Project Type

type: java

**Stack:** Java 21 (on Java 26 JVM), Quarkus 3.32.2

---

## What This Project Is

Outbound and inbound message connector library for the casehubio platform. Provides a `Connector` CDI SPI (outbound) and `InboundConnector`/`WebhookInboundConnector` SPIs (inbound) with built-in implementations for Slack, Teams, Twilio SMS, WhatsApp, and email. Also provides a `ChatPlatform` SPI (`chat-spi`) for structured interaction with chat systems (channels, threads, reactions, presence, members, channel management, member management, message history) with graceful degradation across platforms. ChatPlatform model includes `RichCard` for platform-agnostic rich content and `Channel` with `memberCount`. ChatPlatform implementations: `chat-ref` (in-memory reference), `chat-irc` (IRC with 3 native capabilities), `chat-discord` (Discord with 8 native capabilities + Gateway inbound + attachment downloading + rich embed support), `chat-slack` (Slack with 9 native capabilities — most capable implementation; batch user fetch for members, full ts-precision message history), `chat-signal` (Signal with 6 native capabilities — Messaging, Discovery, Members, Reactions, ChannelManagement, MemberManagement; backed by external signal-cli-rest-api Docker container over HTTP/WebSocket; groups + contacts as channels; compound message identity sender:timestamp; WebSocket inbound). Shared HTTP clients: `slack-bot` (Slack Web API — 16 methods: messaging, channel listing, reactions, presence, members, users, channel management incl. archive, member management, message history), `discord` (Discord Bot REST API v10 + Gateway WebSocket + CDN attachment download with SSRF defense + rich embed serialization + channel delete), `signal-cli` (signal-cli-rest-api HTTP + WebSocket client — send, groups, contacts, reactions, members, attachments; no AGPL dependencies). MCP tools: `send_slack`, `send_teams`, `send_sms`, `send_whatsapp`, `send_email`, `send_chat`, `list_channels`, `list_chat_channels`, `calendar_list_calendars`, `calendar_list_events`, `calendar_get_event`, `calendar_create_event`, `calendar_update_event`, `calendar_delete_event`. `@McpDomain("connectors")` provides platform-dispatch operations via `casehub_action`: `injectChat` (simulate inbound chat message), `sendNotification` (outbound delivery via named connector), `connectorStatus` (registered connectors and capabilities), `sentMessages` (verification of sent messages in dev/test). `ConnectorService.send()` fires `Event<SentMessage>` on every outbound delivery for CDI observer capture. `ChannelManagement` SPI includes `delete()` — Slack archives via `conversations.archive`, Discord calls `DELETE /channels/{id}`. The runnable chat workbench (formerly `chat-demo`) has been migrated to [casehubio/chat-app](https://github.com/casehubio/chat-app). `graphql` module provides `@McpDomain("connectors")` resolvers for platform MCP dispatch — generated from `ConnectorOperations` SPI interface via `GraphQLResolverProcessor`, auto-discovered by `GraphQLModelScanner`. `SentMessageCapture` (profile-gated, `@UnlessBuildProfile("prod")`) records sent messages for verification queries. `notification-bridge` module bridges the platform notification delivery system (`NotificationDeliverer`, `DeliveryChannelRegistry`) to the connector SPI — each `Connector` with a non-null `channelType()` auto-registers as a notification delivery channel at startup. `Connector.send()` returns `boolean` (success/failure). `Connector.channelType()` defaults to `id()`; override to map to a different channel type (`TwilioSmsConnector` → `"sms"`) or return `null` to opt out of notification bridging. `DeliveryChannelDescriptor` carries `DestinationScope` (PER_USER or PER_TENANT) — per-tenant channels (Slack, Teams) deliver once per tenant per event, with the dispatcher deduplicating across the per-user loop. `DestinationResolver` SPI (in `casehub-platform-api`) resolves `userId` → connector-specific destination per channel. Config-based `DestinationResolver` fallback reads destinations from `casehub.notification.destinations.<channel>.<userId>` config properties — starter implementation for dev/test. `DigestFormatter` CDI SPI provides channel-type-aware digest delivery (email HTML, SMS short text, WhatsApp rich text). `EmailConnector` supports `format=html` attribute for HTML rendering via `Mail.withHtml()`. Also provides a `CalendarPlatform` SPI (`calendar-spi`) for calendar integration (list calendars, list/get/create/update/delete events) with sealed `EventTiming` model (Timed/AllDay). CalendarPlatform implementations: `calendar-ref` (in-memory reference), `calendar-google` (Google Calendar API with OAuth2 refresh token auth, paginated listEvents).

**This is the canonical connector infrastructure for the platform.** Any casehubio repo that needs to send outbound messages or receive inbound webhook messages must use these SPIs, not implement its own connector.

---

## Key Rule

Do not add business logic, orchestration, or domain knowledge here. This library is pure delivery infrastructure — it sends outbound messages and receives inbound ones, firing a CDI event. Callers decide when, what, and to whom; observers decide what to do with received messages.

---

## Build and Test

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn clean install
```

**Use `mvn` not `./mvnw`** — maven wrapper not configured on this machine.

---

## Java on This Machine

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26)    # Java 26, use for dev and tests
JAVA_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home  # GraalVM 25, native only
```

---

## Ecosystem Conventions

**Quarkus version:** All projects use `3.32.2`. When bumping, bump all projects together.

**GitHub Packages — dependency resolution:** Add to `pom.xml` `<repositories>`:
```xml
<repository>
  <id>github</id>
  <url>https://maven.pkg.github.com/casehubio/*</url>
  <snapshots><enabled>true</enabled></snapshots>
</repository>
```
CI must use `server-id: github` + `GITHUB_TOKEN` in `actions/setup-java`.

**Cross-project SNAPSHOT versions:** All casehubio artifacts are `0.2-SNAPSHOT` resolved from GitHub Packages.


## Work Tracking

Issue tracking: enabled
GitHub repo: casehubio/connectors

## Development Workflow

Before designing: `superpowers:brainstorming`
Before implementing: `superpowers:test-driven-development`
Before committing: `superpowers:requesting-code-review`

Living docs — check for drift after significant changes:
- `ARC42STORIES.MD` — primary design doc; check §9–10 after SPI, module, or connector changes
- `docs/adr/INDEX.md`

## Writing Style Guide

**The writing style guide at `~/claude-workspace/writing-styles/blog-technical.md` is mandatory for all blog and diary entries.** Load it in full before drafting. Complete the pre-draft voice classification (I / we / Claude-named) before generating any prose. Do not show a draft without verifying it against the style guide.