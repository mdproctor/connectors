package io.casehub.connectors.chat.signal;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import io.casehub.connectors.chat.model.Channel;
import io.casehub.connectors.chat.model.ChatChannelRef;
import io.casehub.connectors.chat.model.ChatContent;
import io.casehub.connectors.chat.model.ChatMessageRef;
import io.casehub.connectors.chat.model.Member;
import io.casehub.connectors.chat.model.MemberRef;
import io.casehub.connectors.chat.model.SendResult;
import io.casehub.connectors.chat.spi.ChannelManagement;
import io.casehub.connectors.chat.spi.Discovery;
import io.casehub.connectors.chat.spi.MemberManagement;
import io.casehub.connectors.chat.spi.Members;
import io.casehub.connectors.chat.spi.Messaging;
import io.casehub.connectors.chat.spi.Presence;
import io.casehub.connectors.chat.spi.Reactions;
import io.casehub.connectors.chat.spi.Threading;
import io.casehub.connectors.chat.spi.MessageHistory;
import io.casehub.connectors.signal.cli.SignalClient;

class SignalChatPlatformTest {

    private WireMockServer wm;
    private SignalChatPlatform platform;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wm.start();
        wm.stubFor(get(urlEqualTo("/v1/health")).willReturn(aResponse().withStatus(204)));
        platform = new SignalChatPlatform(
                new SignalClient("http://localhost:" + wm.port()),
                "http://localhost:" + wm.port(),
                "+15551000000");
        platform.init();
    }

    @AfterEach
    void tearDown() {
        wm.stop();
    }

    @Test
    void id_returns_signal() {
        assertThat(platform.id()).isEqualTo("signal");
    }

    @Test
    void supports_native_capabilities() {
        assertThat(platform.supports(Messaging.class)).isTrue();
        assertThat(platform.supports(Discovery.class)).isTrue();
        assertThat(platform.supports(Members.class)).isTrue();
        assertThat(platform.supports(Reactions.class)).isTrue();
        assertThat(platform.supports(ChannelManagement.class)).isTrue();
        assertThat(platform.supports(MemberManagement.class)).isTrue();
    }

    @Test
    void does_not_support_degraded_capabilities() {
        assertThat(platform.supports(Threading.class)).isFalse();
        assertThat(platform.supports(Presence.class)).isFalse();
        assertThat(platform.supports(MessageHistory.class)).isFalse();
    }

    @Test
    void discovery_returns_groups_and_contacts() {
        wm.stubFor(get(urlEqualTo("/v1/groups/+15551000000"))
                .willReturn(okJson("[{\"id\":\"Z3JvdXAx\",\"name\":\"Dev Team\",\"description\":\"Engineering\",\"members\":[\"+15552000000\",\"+15553000000\"]}]")));
        wm.stubFor(get(urlEqualTo("/v1/contacts/+15551000000"))
                .willReturn(okJson("[{\"number\":\"+15554000000\",\"profileName\":\"Alice\"}]")));

        List<Channel> channels = platform.discovery().listChannels();

        assertThat(channels).hasSize(2);

        Channel group = channels.stream()
                .filter(c -> c.ref().id().equals("Z3JvdXAx")).findFirst().orElseThrow();
        assertThat(group.name()).isEqualTo("Dev Team");
        assertThat(group.topic()).isEqualTo("Engineering");
        assertThat(group.isPrivate()).isFalse();
        assertThat(group.memberCount()).isEqualTo(2);

        Channel contact = channels.stream()
                .filter(c -> c.ref().id().equals("+15554000000")).findFirst().orElseThrow();
        assertThat(contact.name()).isEqualTo("Alice");
        assertThat(contact.isPrivate()).isTrue();
        assertThat(contact.memberCount()).isEqualTo(2);
    }

    @Test
    void send_message_builds_correct_message_ref() {
        wm.stubFor(post(urlEqualTo("/v2/send"))
                .willReturn(okJson("{\"timestamp\":\"1724025600000\"}")));

        SendResult result = platform.messaging().send(
                new ChatChannelRef("+15552000000"), new ChatContent("Hello"));

        assertThat(result.ok()).isTrue();
        assertThat(result.messageRef().messageId()).isEqualTo("+15551000000:1724025600000");
    }

    @Test
    void members_for_group() {
        wm.stubFor(get(urlEqualTo("/v1/groups/+15551000000/Z3JvdXAx"))
                .willReturn(okJson("{\"id\":\"Z3JvdXAx\",\"name\":\"Dev\",\"members\":[\"+15552000000\",\"+15553000000\"]}")));

        List<Member> members = platform.members().list(new ChatChannelRef("Z3JvdXAx"));

        assertThat(members).hasSize(2);
        assertThat(members.get(0).ref().id()).isEqualTo("+15552000000");
    }

    @Test
    void members_for_contact_returns_single_member() {
        List<Member> members = platform.members().list(new ChatChannelRef("+15554000000"));

        assertThat(members).hasSize(1);
        assertThat(members.get(0).ref().id()).isEqualTo("+15554000000");
    }

    @Test
    void reactions_list_returns_empty() {
        assertThat(platform.reactions().list(
                new ChatMessageRef(new ChatChannelRef("+15552000000"),
                        "+15552000000:1724025600000")))
                .isEmpty();
    }

    @Test
    void channel_management_delete_throws_for_contact() {
        assertThatThrownBy(() -> platform.channelManagement().delete("+15554000000"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contact channel");
    }

    @Test
    void channel_management_create_group() {
        wm.stubFor(post(urlEqualTo("/v1/groups/+15551000000"))
                .willReturn(okJson("{\"id\":\"bmV3\",\"name\":\"New\"}")));

        Channel ch = platform.channelManagement().create("New", null, null, false);

        assertThat(ch.ref().id()).isEqualTo("bmV3");
        assertThat(ch.name()).isEqualTo("New");
    }

    @Test
    void member_management_add_throws_for_contact() {
        assertThatThrownBy(() -> platform.memberManagement().add(
                new ChatChannelRef("+15554000000"),
                new Member(new MemberRef("+15555000000"), "Bob")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contact channel");
    }

    @Test
    void degrades_when_unconfigured() {
        SignalChatPlatform unconfigured = new SignalChatPlatform(
                new SignalClient("http://localhost:1"), "", "");
        unconfigured.init();

        assertThat(unconfigured.supports(Messaging.class)).isFalse();
        assertThat(unconfigured.discovery().listChannels()).isEmpty();
    }

    @Test
    void parseMessageId_splits_correctly() {
        String[] parts = SignalChatPlatform.parseMessageId("+15552000000:1724025600000");
        assertThat(parts[0]).isEqualTo("+15552000000");
        assertThat(parts[1]).isEqualTo("1724025600000");
    }
}
