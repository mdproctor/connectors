package io.casehub.connectors.mcp;

import io.casehub.connectors.ConnectorMessage;
import io.casehub.connectors.ConnectorMeshBridge;
import io.casehub.connectors.ConnectorService;
import io.casehub.connectors.teams.TeamsConnector;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class TeamsMcpTool {

    private static final Logger LOG = Logger.getLogger(TeamsMcpTool.class);

    private final ConnectorService connectorService;
    private final ConnectorMeshBridge meshBridge;

    @Inject
    TeamsMcpTool(final ConnectorService connectorService, final ConnectorMeshBridge meshBridge) {
        this.connectorService = connectorService;
        this.meshBridge = meshBridge;
    }

    @Blocking
    @Tool(name = "send_teams",
          description = "Posts an adaptive card message to a Microsoft Teams channel "
                      + "via an incoming webhook URL. "
                      + "Returns 'Dispatched to <url>' on success or 'Failed: <reason>' on error. "
                      + "'Dispatched' means the request was sent — not that Teams confirmed delivery.")
    public String sendTeams(
            @ToolArg(description = "Teams incoming webhook URL — "
                                 + "format: https://<org>.webhook.office.com/webhookb2/...")
            final String webhookUrl,
            @ToolArg(description = "Card title displayed at the top of the message.")
            final String title,
            @ToolArg(description = "Message text body.")
            final String body) {
        try {
            connectorService.send(TeamsConnector.ID, new ConnectorMessage(webhookUrl, title, body));
            meshBridge.notifyDelivered(TeamsConnector.ID, webhookUrl,
                    McpContentSanitizer.sanitize(body));
            return "Dispatched to " + webhookUrl;
        } catch (final Exception e) {
            LOG.warnf("send_teams failed [%s]: %s", e.getClass().getSimpleName(), e.getMessage());
            return "Failed: " + e.getMessage();
        }
    }
}
