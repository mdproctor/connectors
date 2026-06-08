package io.casehub.connectors.mcp;

import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.smallrye.common.annotation.Blocking;

import io.casehub.connectors.ConnectorMeshBridge;
import io.casehub.connectors.slack.bot.SlackBotClient;
import io.casehub.connectors.slack.bot.SlackBotClient.PostResult;

@ApplicationScoped
public class SlackBotMcpTool {

    private static final Logger LOG = Logger.getLogger(SlackBotMcpTool.class);

    private final SlackBotClient slackBotClient;
    private final ConnectorMeshBridge meshBridge;
    private final String botToken;

    @Inject // public: SlackBotMcpToolTest is in io.casehub.connectors.slack.bot (cross-package)
    public SlackBotMcpTool(final SlackBotClient slackBotClient,
                    final ConnectorMeshBridge meshBridge,
                    @ConfigProperty(name = "casehub.connectors.slack-bot.token",
                                    defaultValue = "") final String botToken) {
        this.slackBotClient = slackBotClient;
        this.meshBridge = meshBridge;
        this.botToken = botToken;
    }

    @Tool(name = "send_slack_bot",
          description = "Posts a message to a Slack channel using a configured bot token. "
                      + "Returns the message timestamp (ts) on success — save it to reply "
                      + "in-thread. Requires casehub.connectors.slack-bot.token on the server. "
                      + "Returns 'Posted to <channel> (ts=<ts>)' on success or "
                      + "'Failed: <reason>' on error.")
    @Blocking
    public String sendSlackBot(
            @ToolArg(description = "Slack channel ID (e.g. C123ABC). "
                                 + "Use list_channels to discover available IDs.")
            final String channel,
            @ToolArg(description = "Message text.")
            final String text,
            @ToolArg(description = "Thread timestamp for in-thread replies. Use the ts from "
                                 + "a previous send_slack_bot call. Omit for a new message.",
                     required = false)
            final String threadTs) {
        try {
            if (botToken.isBlank()) {
                return "Failed: casehub.connectors.slack-bot.token is not configured";
            }
            final PostResult result = slackBotClient.postMessage(
                    botToken, channel, text,
                    (threadTs == null || threadTs.isBlank()) ? null : threadTs);
            if (!result.ok()) {
                return "Failed: " + result.error();
            }
            meshBridge.notifyDelivered(SlackBotClient.ID, channel,
                    McpContentSanitizer.sanitize(text));
            return "Posted to " + channel + " (ts=" + result.ts() + ")";
        } catch (final Exception e) {
            LOG.warnf("send_slack_bot failed [%s]: %s", e.getClass().getSimpleName(), e.getMessage());
            return "Failed: " + e.getMessage();
        }
    }
}
