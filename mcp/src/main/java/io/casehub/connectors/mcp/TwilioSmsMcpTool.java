package io.casehub.connectors.mcp;

import io.casehub.connectors.ConnectorMessage;
import io.casehub.connectors.ConnectorMeshBridge;
import io.casehub.connectors.ConnectorService;
import io.casehub.connectors.twilio.TwilioSmsConnector;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class TwilioSmsMcpTool {

    private static final Logger LOG = Logger.getLogger(TwilioSmsMcpTool.class);

    private final ConnectorService connectorService;
    private final ConnectorMeshBridge meshBridge;

    @Inject
    TwilioSmsMcpTool(final ConnectorService connectorService, final ConnectorMeshBridge meshBridge) {
        this.connectorService = connectorService;
        this.meshBridge = meshBridge;
    }

    @Blocking
    @Tool(name = "send_sms",
          description = "Sends an SMS message via Twilio. "
                      + "Requires Twilio credentials configured on the server "
                      + "(casehub.connectors.twilio.*). "
                      + "Returns 'Dispatched to <number>' on success or 'Failed: <reason>' on error. "
                      + "Note: a Dispatched response means the request reached Twilio, "
                      + "not that the SMS was delivered to the handset.")
    public String sendSms(
            @ToolArg(description = "Recipient phone number in E.164 format — "
                                 + "must include country code, e.g. +447700900000 or +12125551234.")
            final String to,
            @ToolArg(description = "SMS message body. Max 1600 characters; "
                                 + "longer messages are split by Twilio into concatenated segments.")
            final String body) {
        try {
            connectorService.send(TwilioSmsConnector.ID, new ConnectorMessage(to, body));
            meshBridge.notifyDelivered(TwilioSmsConnector.ID, to,
                    McpContentSanitizer.sanitize(body));
            return "Dispatched to " + to;
        } catch (final Exception e) {
            LOG.warnf("send_sms failed [%s]: %s", e.getClass().getSimpleName(), e.getMessage());
            return "Failed: " + e.getMessage();
        }
    }
}
