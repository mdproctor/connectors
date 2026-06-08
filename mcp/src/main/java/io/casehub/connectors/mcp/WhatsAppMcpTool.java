package io.casehub.connectors.mcp;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import io.casehub.connectors.ConnectorMessage;
import io.casehub.connectors.ConnectorMeshBridge;
import io.casehub.connectors.ConnectorService;
import io.casehub.connectors.whatsapp.WhatsAppConnector;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class WhatsAppMcpTool {

    private static final Logger LOG = Logger.getLogger(WhatsAppMcpTool.class);

    private final ConnectorService connectorService;
    private final ConnectorMeshBridge meshBridge;

    @Inject
    WhatsAppMcpTool(final ConnectorService connectorService, final ConnectorMeshBridge meshBridge) {
        this.connectorService = connectorService;
        this.meshBridge = meshBridge;
    }

    @Blocking
    @Tool(name = "send_whatsapp",
          description = "Sends a WhatsApp message via the Meta Cloud API. "
                      + "Requires WhatsApp Business credentials configured on the server "
                      + "(casehub.connectors.whatsapp.*). "
                      + "For recipients outside the 24-hour engagement window, provide templateName "
                      + "with a pre-approved Meta Business Manager template name. "
                      + "Returns 'Dispatched to <number>' on success or 'Failed: <reason>' on error.")
    public String sendWhatsApp(
            @ToolArg(description = "Recipient phone number in E.164 format, e.g. +447700900000.")
            final String to,
            @ToolArg(description = "Message body text. Ignored when templateName is provided.")
            final String body,
            @ToolArg(description = "Optional WhatsApp template name (e.g. 'hello_world'). "
                                 + "Required for first-contact messages or outside the 24-hour window. "
                                 + "Template must be pre-approved in Meta Business Manager.",
                     required = false)
            final String templateName,
            @ToolArg(description = "BCP-47 language code for the template, e.g. 'en_US', 'es_MX'. "
                                 + "Defaults to 'en_US' when omitted. "
                                 + "Only used when templateName is provided.",
                     required = false)
            final String templateLanguage) {
        try {
            final Map<String, String> attrs;
            if (templateName != null && !templateName.isBlank()) {
                final Map<String, String> mutableAttrs = new HashMap<>();
                mutableAttrs.put("templateName", templateName);
                if (templateLanguage != null && !templateLanguage.isBlank()) {
                    mutableAttrs.put("templateLanguage", templateLanguage);
                }
                attrs = Collections.unmodifiableMap(mutableAttrs);
            } else {
                attrs = Map.of();
            }
            connectorService.send(WhatsAppConnector.ID,
                    new ConnectorMessage(to, null, body, attrs));
            meshBridge.notifyDelivered(WhatsAppConnector.ID, to,
                    McpContentSanitizer.sanitize(body));
            return "Dispatched to " + to;
        } catch (final Exception e) {
            LOG.warnf("send_whatsapp failed [%s]: %s", e.getClass().getSimpleName(), e.getMessage());
            return "Failed: " + e.getMessage();
        }
    }
}
