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

    /**
     * The connector type id this discovery is associated with.
     * Must match the value returned by the corresponding {@link Connector#id()}.
     *
     * @return the connector type id; never null or blank
     */
    String id();

    /**
     * Discovers the delivery targets available for this connector.
     *
     * @return list of discovered targets; never null; empty list on failure or when
     *         no targets are reachable
     */
    List<DiscoveredTarget> discover();
}
