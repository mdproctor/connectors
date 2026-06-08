package io.casehub.connectors;

/**
 * A delivery target discovered at runtime via {@link ConnectorDiscovery}.
 *
 * @param id          the identifier to pass to MCP tools (e.g. Slack channel ID {@code C123ABC})
 * @param displayName human-readable label shown in {@code list_channels} output (e.g. {@code #general})
 */
public record DiscoveredTarget(String id, String displayName) {}
