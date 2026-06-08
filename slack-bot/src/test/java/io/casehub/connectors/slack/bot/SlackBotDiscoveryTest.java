package io.casehub.connectors.slack.bot;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import io.casehub.connectors.DiscoveredTarget;

class SlackBotDiscoveryTest {

    private WireMockServer wireMock;
    private SlackBotClient client;

    @BeforeEach
    void start() {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();
        client = new SlackBotClient();
        client.apiBaseUrl = "http://localhost:" + wireMock.port();
    }

    @AfterEach
    void stop() {
        wireMock.stop();
    }

    @Test
    void id_returnsSlackBotId() {
        final SlackBotDiscovery discovery = new SlackBotDiscovery(client, "any-token");
        assertThat(discovery.id()).isEqualTo(SlackBotClient.ID);
    }

    @Test
    void discover_delegatesToClient_withConfiguredToken() {
        wireMock.stubFor(get(urlEqualTo(
                "/api/conversations.list?types=public_channel,private_channel&limit=200"))
                .willReturn(okJson("{\"ok\":true,\"channels\":["
                        + "{\"id\":\"C111\",\"name\":\"general\"}"
                        + "]}")));

        final SlackBotDiscovery discovery = new SlackBotDiscovery(client, "xoxb-test");
        final List<DiscoveredTarget> result = discovery.discover();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo("C111");
        assertThat(result.get(0).displayName()).isEqualTo("#general");
    }

    @Test
    void discover_blankToken_returnsEmptyListWithoutHttpCall() {
        final SlackBotDiscovery discovery = new SlackBotDiscovery(client, "");

        final List<DiscoveredTarget> result = discovery.discover();

        assertThat(result).isEmpty();
        wireMock.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));
    }
}
