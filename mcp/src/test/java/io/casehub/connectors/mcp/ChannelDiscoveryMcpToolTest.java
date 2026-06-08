package io.casehub.connectors.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.casehub.connectors.ConnectorDiscovery;
import io.casehub.connectors.DiscoveredTarget;

class ChannelDiscoveryMcpToolTest {

    private static StubDiscovery stub(final String id,
                                      final List<DiscoveredTarget> targets) {
        return new StubDiscovery(id, targets);
    }

    private static final class StubDiscovery implements ConnectorDiscovery {
        private final String connectorId;
        private final List<DiscoveredTarget> targets;

        StubDiscovery(final String connectorId, final List<DiscoveredTarget> targets) {
            this.connectorId = connectorId;
            this.targets = targets;
        }

        @Override public String id() { return connectorId; }
        @Override public List<DiscoveredTarget> discover() { return targets; }
    }

    private static final class ThrowingDiscovery implements ConnectorDiscovery {
        @Override public String id() { return "bad"; }
        @Override public List<DiscoveredTarget> discover() {
            throw new RuntimeException("simulated failure");
        }
    }

    @Test
    void listChannels_singleConnector_formatsOutput() {
        final ChannelDiscoveryMcpTool tool = new ChannelDiscoveryMcpTool(List.of(
                stub("slack-bot", List.of(
                        new DiscoveredTarget("C123ABC", "#general"),
                        new DiscoveredTarget("C456DEF", "#engineering")))));

        final String result = tool.listChannels();

        assertThat(result).isEqualTo(
                "slack-bot:\n"
                + "  #general (C123ABC)\n"
                + "  #engineering (C456DEF)");
    }

    @Test
    void listChannels_multipleConnectors_formatsAll() {
        final ChannelDiscoveryMcpTool tool = new ChannelDiscoveryMcpTool(List.of(
                stub("slack-bot", List.of(new DiscoveredTarget("C1", "#general"))),
                stub("demo", List.of(new DiscoveredTarget("main", "#main")))));

        final String result = tool.listChannels();

        assertThat(result).contains("slack-bot:");
        assertThat(result).contains("  #general (C1)");
        assertThat(result).contains("demo:");
        assertThat(result).contains("  #main (main)");
    }

    @Test
    void listChannels_emptyDiscover_skipsConnector() {
        final ChannelDiscoveryMcpTool tool = new ChannelDiscoveryMcpTool(List.of(
                stub("slack-bot", List.of())));

        final String result = tool.listChannels();

        assertThat(result).isEqualTo("No channels discovered.");
    }

    @Test
    void listChannels_noConnectors_returnsNoneDiscovered() {
        final ChannelDiscoveryMcpTool tool = new ChannelDiscoveryMcpTool(List.of());

        final String result = tool.listChannels();

        assertThat(result).isEqualTo("No channels discovered.");
    }

    @Test
    void listChannels_discoveryThrows_logsWarnAndContinues() {
        final ChannelDiscoveryMcpTool tool = new ChannelDiscoveryMcpTool(List.of(
                new ThrowingDiscovery(),
                stub("slack-bot", List.of(new DiscoveredTarget("C1", "#general")))));

        final String result = tool.listChannels();

        assertThat(result).isEqualTo("slack-bot:\n  #general (C1)");
    }
}
