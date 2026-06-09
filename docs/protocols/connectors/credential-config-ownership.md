---
id: PP-20260609-0c3e24
title: "Credential config properties belong to callers, not to shared HTTP clients"
type: principle
scope: repo
applies_to: "Any shared HTTP client (SlackBotClient, future TelegramBotClient, etc.) used by multiple consumers with different credential needs"
severity: important
refs:
  - slack-bot/src/main/java/io/casehub/connectors/slack/bot/SlackBotClient.java
  - slack-bot/src/main/java/io/casehub/connectors/slack/bot/SlackBotDiscovery.java
  - mcp/src/main/java/io/casehub/connectors/mcp/SlackBotMcpTool.java
violation_hint: "A @ConfigProperty token field on a shared HTTP client that is also injected by Qhorus with its own token — the field is noise in the Qhorus deployment and creates a two-mode client"
created: 2026-06-09
---

Shared HTTP clients take credentials (tokens, keys) at call time as parameters — they do
not hold `@ConfigProperty` fields for credentials. Each caller (`SlackBotMcpTool`,
`SlackBotDiscovery`, `SlackChannelBackend`) injects its own `@ConfigProperty` and passes
the value at call time. This keeps the shared client deployment-agnostic: a Qhorus
deployment injects `casehub.qhorus.slack.bot.token`; an MCP deployment injects
`casehub.connectors.slack-bot.token`; neither deployment is polluted by the other's config.
A violation occurs when a credential field is added to `SlackBotClient` itself, creating a
client with two code paths (call-time token vs stored token) and injecting config that is
irrelevant to one of its consumers.
