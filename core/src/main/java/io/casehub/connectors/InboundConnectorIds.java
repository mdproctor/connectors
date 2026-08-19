package io.casehub.connectors;

/**
 * Canonical connector ID constants for the built-in inbound connectors.
 *
 * <p>These match the value returned by each connector's {@link InboundConnector#id()} /
 * {@link WebhookInboundConnector#id()} method. Routing code in downstream modules
 * (e.g. {@code casehub-qhorus/connector-backend}) can reference these constants
 * without depending on the specific connector implementation modules.
 */
public final class InboundConnectorIds {

    public static final String SLACK_INBOUND = "slack-inbound";
    public static final String TWILIO_SMS = "twilio-sms-inbound";
    public static final String WHATSAPP = "whatsapp-inbound";
    public static final String EMAIL = "email-inbound";
    public static final String TEAMS_INBOUND = "teams-inbound";
    public static final String IRC = "irc-inbound";
    public static final String DISCORD_INBOUND = "discord-inbound";
    public static final String CHAT_INJECT     = "chat-inject";
    public static final String SIGNAL_INBOUND  = "signal-inbound";

    private InboundConnectorIds() {}
}
