package io.casehub.connectors;

import java.util.List;

/**
 * Optional SPI for connectors whose delivery targets are discoverable at runtime.
 *
 * <p>Implementations are {@code @ApplicationScoped} CDI beans discovered automatically.
 * The {@code list_channels} MCP tool aggregates all registered implementations via
 * {@code @All List<ConnectorDiscovery>}.
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
    String connectorId();
    List<DiscoveredTarget> discover();
}
