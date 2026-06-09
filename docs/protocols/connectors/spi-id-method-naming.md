---
id: PP-20260609-e3a2bd
title: "SPI identifier methods are named id(), not connectorId() or typeId()"
type: rule
scope: repo
applies_to: "Any new SPI interface added to casehub-connectors-core"
severity: important
refs:
  - core/src/main/java/io/casehub/connectors/Connector.java
  - core/src/main/java/io/casehub/connectors/InboundConnector.java
  - core/src/main/java/io/casehub/connectors/ConnectorDiscovery.java
violation_hint: "An SPI interface with a method named connectorId(), typeId(), or any variant other than id() — caught in code review for ConnectorDiscovery"
created: 2026-06-09
---

All SPIs in casehub-connectors that expose a connector type identifier use `String id()`,
consistent with `Connector.id()`, `InboundConnector.id()`, and `ConnectorDiscovery.id()`.
A more qualified name like `connectorId()` is redundant when the interface already provides
type context, and diverges from the established `id()` convention across `ConnectorService`,
`InboundConnectorService`, and `ChannelDiscoveryMcpTool` — all of which dispatch by
calling `id()`. A violation causes silent inconsistency: the SPI works but the dispatch
code reads awkwardly and cross-references to `Connector::id` break.
