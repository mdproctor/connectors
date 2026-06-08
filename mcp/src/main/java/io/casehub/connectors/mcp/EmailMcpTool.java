package io.casehub.connectors.mcp;

import io.casehub.connectors.ConnectorMessage;
import io.casehub.connectors.ConnectorMeshBridge;
import io.casehub.connectors.ConnectorService;
import io.casehub.connectors.email.EmailConnector;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class EmailMcpTool {

    private static final Logger LOG = Logger.getLogger(EmailMcpTool.class);

    private final ConnectorService connectorService;
    private final ConnectorMeshBridge meshBridge;

    @Inject
    EmailMcpTool(final ConnectorService connectorService, final ConnectorMeshBridge meshBridge) {
        this.connectorService = connectorService;
        this.meshBridge = meshBridge;
    }

    @Blocking
    @Tool(name = "send_email",
          description = "Sends an email via the SMTP server configured on this app "
                      + "(quarkus.mailer.*). "
                      + "Returns 'Dispatched to <address>' on success or 'Failed: <reason>' on error.")
    public String sendEmail(
            @ToolArg(description = "Recipient email address, e.g. user@example.com.")
            final String to,
            @ToolArg(description = "Email subject line.")
            final String subject,
            @ToolArg(description = "Plain-text email body.")
            final String body) {
        try {
            connectorService.send(EmailConnector.ID, new ConnectorMessage(to, subject, body));
            meshBridge.notifyDelivered(EmailConnector.ID, to,
                    McpContentSanitizer.sanitize(body));
            return "Dispatched to " + to;
        } catch (final Exception e) {
            LOG.warnf("send_email failed [%s]: %s", e.getClass().getSimpleName(), e.getMessage());
            return "Failed: " + e.getMessage();
        }
    }
}
