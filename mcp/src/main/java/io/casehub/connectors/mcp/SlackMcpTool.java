package io.casehub.connectors.mcp;

import io.casehub.connectors.ConnectorMessage;
import io.casehub.connectors.ConnectorMeshBridge;
import io.casehub.connectors.ConnectorService;
import io.casehub.connectors.slack.SlackConnector;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class SlackMcpTool {

    private static final Logger LOG = Logger.getLogger(SlackMcpTool.class);

    private final ConnectorService connectorService;
    private final ConnectorMeshBridge meshBridge;

    @Inject
    SlackMcpTool(final ConnectorService connectorService, final ConnectorMeshBridge meshBridge) {
        this.connectorService = connectorService;
        this.meshBridge = meshBridge;
    }

    @Blocking
    @Tool(name = "send_slack",
          description = "Posts a message to a Slack channel via an incoming webhook URL. "
                      + "Returns 'Dispatched to <url>' on success or 'Failed: <reason>' on error. "
                      + "'Dispatched' means the request was sent — not that Slack confirmed delivery.")
    public String sendSlack(
            @ToolArg(description = "Full Slack incoming webhook URL — "
                                 + "starts with https://hooks.slack.com/services/. "
                                 + "This URL is the credential; keep it confidential.")
            final String webhookUrl,
            @ToolArg(description = "Card header / bold title. Use empty string if not needed.")
            final String title,
            @ToolArg(description = "Message text body.")
            final String body) {
        try {
            connectorService.send(SlackConnector.ID, new ConnectorMessage(webhookUrl, title, body));
            meshBridge.notifyDelivered(SlackConnector.ID, webhookUrl,
                    McpContentSanitizer.sanitize(body));
            return "Dispatched to " + webhookUrl;
        } catch (final Exception e) {
            LOG.warnf("send_slack failed [%s]: %s", e.getClass().getSimpleName(), e.getMessage());
            return "Failed: " + e.getMessage();
        }
    }
}
