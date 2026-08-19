package io.casehub.connectors.chat.signal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.casehub.connectors.chat.degraded.ChannelFallbackThreading;
import io.casehub.connectors.chat.degraded.EmptyDiscovery;
import io.casehub.connectors.chat.degraded.EmptyMembers;
import io.casehub.connectors.chat.degraded.EmptyMessageHistory;
import io.casehub.connectors.chat.degraded.NoOpChannelManagement;
import io.casehub.connectors.chat.degraded.NoOpMemberManagement;
import io.casehub.connectors.chat.degraded.NoOpReactions;
import io.casehub.connectors.chat.degraded.UnknownPresence;
import io.casehub.connectors.chat.model.Channel;
import io.casehub.connectors.chat.model.ChatChannelRef;
import io.casehub.connectors.chat.model.ChatContent;
import io.casehub.connectors.chat.model.ChatMessageRef;
import io.casehub.connectors.chat.model.Member;
import io.casehub.connectors.chat.model.MemberRef;
import io.casehub.connectors.chat.model.SendResult;
import io.casehub.connectors.chat.spi.ChannelManagement;
import io.casehub.connectors.chat.spi.ChatPlatform;
import io.casehub.connectors.chat.spi.Discovery;
import io.casehub.connectors.chat.spi.MemberManagement;
import io.casehub.connectors.chat.spi.Members;
import io.casehub.connectors.chat.spi.MessageHistory;
import io.casehub.connectors.chat.spi.Messaging;
import io.casehub.connectors.chat.spi.Presence;
import io.casehub.connectors.chat.spi.Reactions;
import io.casehub.connectors.chat.spi.Threading;
import io.casehub.connectors.signal.cli.SignalClient;
import io.casehub.connectors.signal.cli.model.SendResponse;
import io.casehub.connectors.signal.cli.model.SignalContact;
import io.casehub.connectors.signal.cli.model.SignalGroup;

@ApplicationScoped
public class SignalChatPlatform implements ChatPlatform {

    private static final Logger LOG = Logger.getLogger(SignalChatPlatform.class.getName());

    private static final Set<Class<?>> NATIVE_CAPABILITIES = Set.of(
            Messaging.class, Discovery.class, Members.class,
            Reactions.class, ChannelManagement.class, MemberManagement.class);

    private final SignalClient client;
    private final String apiUrl;
    private final String number;

    private Set<Class<?>> activeCapabilities = Set.of();
    private Messaging messaging;
    private Threading threading;
    private Discovery discovery;
    private Reactions reactions;
    private Presence presence;
    private Members members;
    private ChannelManagement channelManagement;
    private MemberManagement memberManagement;
    private MessageHistory messageHistory;

    @Inject
    public SignalChatPlatform(
            final SignalClient client,
            @ConfigProperty(name = "casehub.signal.api-url", defaultValue = "") final String apiUrl,
            @ConfigProperty(name = "casehub.signal.number", defaultValue = "") final String number) {
        this.client = client;
        this.apiUrl = apiUrl;
        this.number = number;
    }

    @PostConstruct
    void init() {
        if (apiUrl.isBlank() || number.isBlank()) {
            LOG.warning("signal: not configured, platform inactive");
            initDegraded();
            return;
        }
        if (!client.health()) {
            LOG.warning("signal: container unreachable at " + apiUrl + ", platform inactive");
            initDegraded();
            return;
        }

        activeCapabilities = NATIVE_CAPABILITIES;
        messaging = this::sendMessage;
        threading = new ChannelFallbackThreading(messaging);
        discovery = this::listChannels;
        reactions = new SignalReactions();
        presence = new UnknownPresence();
        members = this::listMembers;
        channelManagement = new SignalChannelManagement();
        memberManagement = new SignalMemberManagement();
        messageHistory = new EmptyMessageHistory();
    }

    private void initDegraded() {
        activeCapabilities = Set.of();
        messaging = (channel, content) -> SendResult.failure("Signal not configured");
        threading = new ChannelFallbackThreading(messaging);
        discovery = new EmptyDiscovery();
        reactions = new NoOpReactions();
        presence = new UnknownPresence();
        members = new EmptyMembers();
        channelManagement = new NoOpChannelManagement();
        memberManagement = new NoOpMemberManagement();
        messageHistory = new EmptyMessageHistory();
    }

    @Override public String id() { return "signal"; }
    @Override public Messaging messaging() { return messaging; }
    @Override public Threading threading() { return threading; }
    @Override public Discovery discovery() { return discovery; }
    @Override public Reactions reactions() { return reactions; }
    @Override public Presence presence() { return presence; }
    @Override public Members members() { return members; }
    @Override public ChannelManagement channelManagement() { return channelManagement; }
    @Override public MemberManagement memberManagement() { return memberManagement; }
    @Override public MessageHistory messageHistory() { return messageHistory; }

    @Override
    public boolean supports(final Class<?> capability) {
        return activeCapabilities.contains(capability);
    }

    private SendResult sendMessage(final ChatChannelRef channel, final ChatContent content) {
        final String text = content.markdown() != null ? content.markdown() : content.text();
        final SendResponse resp = client.send(number, channel.id(), text, List.of());
        if (!resp.ok()) {
            return SendResult.failure("Signal send failed");
        }
        return SendResult.success(
                new ChatMessageRef(channel, number + ":" + resp.timestamp()),
                Instant.now());
    }

    private List<Channel> listChannels() {
        final List<Channel> result = new ArrayList<>();

        for (final SignalGroup g : client.listGroups(number)) {
            result.add(new Channel(
                    new ChatChannelRef(g.id()),
                    g.name(),
                    g.description(),
                    null,
                    false,
                    g.members() != null ? g.members().size() : null));
        }

        for (final SignalContact c : client.listContacts(number)) {
            final String name = c.profileName() != null ? c.profileName() : c.number();
            result.add(new Channel(
                    new ChatChannelRef(c.number()),
                    name,
                    null,
                    null,
                    true,
                    2));
        }

        return result;
    }

    private List<Member> listMembers(final ChatChannelRef channel) {
        if (isContactChannel(channel.id())) {
            return List.of(new Member(new MemberRef(channel.id()), channel.id()));
        }
        final SignalGroup group = client.getGroup(number, channel.id());
        if (group == null || group.members() == null) return List.of();
        return group.members().stream()
                .map(phone -> new Member(new MemberRef(phone), phone))
                .toList();
    }

    private class SignalReactions implements Reactions {
        @Override
        public void add(final ChatMessageRef message, final String emoji) {
            final String[] parts = parseMessageId(message.messageId());
            client.addReaction(number, message.channel().id(), emoji, parts[0],
                    Long.parseLong(parts[1]));
        }

        @Override
        public void remove(final ChatMessageRef message, final String emoji) {
            final String[] parts = parseMessageId(message.messageId());
            client.removeReaction(number, message.channel().id(), emoji, parts[0],
                    Long.parseLong(parts[1]));
        }

        @Override
        public List<String> list(final ChatMessageRef message) {
            return Collections.emptyList();
        }
    }

    private class SignalChannelManagement implements ChannelManagement {
        @Override
        public Channel create(final String name, final String topic,
                              final String description, final boolean isPrivate) {
            final SignalGroup group = client.createGroup(number, name, List.of());
            if (group == null) {
                throw new IllegalStateException("Signal group creation failed");
            }
            return new Channel(
                    new ChatChannelRef(group.id()),
                    group.name(),
                    group.description(),
                    null,
                    false,
                    group.members() != null ? group.members().size() : 0);
        }

        @Override
        public void delete(final String channelId) {
            if (isContactChannel(channelId)) {
                throw new IllegalArgumentException(
                        "Cannot delete a contact channel: " + channelId);
            }
            client.deleteGroup(number, channelId);
        }

        @Override
        public Optional<Channel> find(final String channelId) {
            if (isContactChannel(channelId)) {
                for (final SignalContact c : client.listContacts(number)) {
                    if (channelId.equals(c.number())) {
                        final String name = c.profileName() != null
                                ? c.profileName() : c.number();
                        return Optional.of(new Channel(
                                new ChatChannelRef(c.number()),
                                name, null, null, true, 2));
                    }
                }
                return Optional.empty();
            }
            final SignalGroup group = client.getGroup(number, channelId);
            if (group == null) return Optional.empty();
            return Optional.of(new Channel(
                    new ChatChannelRef(group.id()),
                    group.name(),
                    group.description(),
                    null,
                    false,
                    group.members() != null ? group.members().size() : null));
        }
    }

    private class SignalMemberManagement implements MemberManagement {
        @Override
        public void add(final ChatChannelRef channel, final Member member) {
            if (isContactChannel(channel.id())) {
                throw new IllegalArgumentException(
                        "Cannot add members to a contact channel: " + channel.id());
            }
            client.addMembers(number, channel.id(), List.of(member.ref().id()));
        }

        @Override
        public void remove(final ChatChannelRef channel, final MemberRef member) {
            if (isContactChannel(channel.id())) {
                throw new IllegalArgumentException(
                        "Cannot remove members from a contact channel: " + channel.id());
            }
            client.removeMembers(number, channel.id(), List.of(member.id()));
        }
    }

    private static boolean isContactChannel(final String channelId) {
        return channelId.startsWith("+");
    }

    static String[] parseMessageId(final String messageId) {
        final int colon = messageId.lastIndexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException("Invalid message ID: " + messageId);
        }
        return new String[]{messageId.substring(0, colon), messageId.substring(colon + 1)};
    }
}
