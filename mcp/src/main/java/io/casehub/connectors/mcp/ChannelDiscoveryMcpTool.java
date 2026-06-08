package io.casehub.connectors.mcp;

import java.util.List;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkus.arc.All;
import io.quarkiverse.mcp.server.Tool;
import io.smallrye.common.annotation.Blocking;

import io.casehub.connectors.ConnectorDiscovery;
import io.casehub.connectors.DiscoveredTarget;

@ApplicationScoped
public class ChannelDiscoveryMcpTool {

    private static final Logger LOG = Logger.getLogger(ChannelDiscoveryMcpTool.class);

    private final List<ConnectorDiscovery> discoveries;

    @Inject
    ChannelDiscoveryMcpTool(@All final List<ConnectorDiscovery> discoveries) {
        this.discoveries = discoveries;
    }

    @Tool(name = "list_channels",
          description = "Lists discoverable channels across all configured connectors "
                      + "(e.g. Slack Bot). Returns channel IDs to use with send_slack_bot. "
                      + "Only connectors with a token configured appear in the output.")
    @Blocking
    public String listChannels() {
        final StringBuilder sb = new StringBuilder();
        for (final ConnectorDiscovery d : discoveries) {
            final List<DiscoveredTarget> targets;
            try {
                targets = d.discover();
            } catch (final Exception e) {
                LOG.warnf("ConnectorDiscovery[%s] threw: %s", d.id(), e.getMessage());
                continue;
            }
            if (targets.isEmpty()) continue;
            sb.append(d.id()).append(":\n");
            for (final DiscoveredTarget t : targets) {
                sb.append("  ").append(t.displayName())
                  .append(" (").append(t.id()).append(")\n");
            }
        }
        return sb.isEmpty() ? "No channels discovered." : sb.toString().stripTrailing();
    }
}
