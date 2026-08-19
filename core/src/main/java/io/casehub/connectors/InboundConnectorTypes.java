package io.casehub.connectors;

/**
 * Semantic connector-type constants for inbound connectors.
 *
 * <p>These are provider-agnostic and direction-agnostic labels used in the CloudEvent
 * {@code type} field and by routing code. Values use the channel name only (no
 * {@code -inbound} suffix, no provider prefix like {@code twilio-}).
 *
 * <p>For connector IDs (which do include {@code -inbound} and provider), see
 * {@link InboundConnectorIds}.
 */
public final class InboundConnectorTypes {

    public static final String SLACK    = "slack";
    public static final String EMAIL    = "email";
    public static final String SMS      = "sms";
    public static final String WHATSAPP = "whatsapp";
    public static final String TEAMS    = "teams";
    public static final String DISCORD  = "discord";
    public static final String IRC      = "irc";
    public static final String SIGNAL   = "signal";

    private InboundConnectorTypes() {}
}
