---
id: PP-20260609-0625c9
title: "@Blocking is required on every @Tool method that calls blocking HTTP"
type: rule
scope: repo
applies_to: "casehub-connectors-mcp — all @Tool-annotated methods"
severity: important
refs:
  - mcp/src/main/java/io/casehub/connectors/mcp/SlackMcpTool.java
  - mcp/src/main/java/io/casehub/connectors/mcp/SlackBotMcpTool.java
violation_hint: "A @Tool method without @Blocking that calls HttpHelper.CLIENT.send() — stalls the Vert.x I/O thread silently; only visible under load"
garden_ref: "GE-20260604-96d82a"
created: 2026-06-09
---

Every `@Tool` method in `casehub-connectors-mcp` that calls `HttpHelper.CLIENT.send()` —
directly or via `ConnectorService` — must be annotated `@Blocking`
(`io.smallrye.common.annotation.Blocking`). Quarkus MCP server executes tool methods on
the Vert.x event loop by default; blocking HTTP on the event loop stalls the entire loop
under concurrent load with no visible error in tests. Five existing tools were missing this
annotation — the regression is latent and only surfaces under production traffic patterns.
New tools must include `@Blocking` at the time of writing; omitting it is not detectable by
the compiler or tests.
