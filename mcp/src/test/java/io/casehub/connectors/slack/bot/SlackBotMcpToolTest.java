package io.casehub.connectors.slack.bot;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import io.casehub.connectors.mcp.McpToolTestSupport;
import io.casehub.connectors.mcp.SlackBotMcpTool;

class SlackBotMcpToolTest {

    private WireMockServer wireMock;
    private SlackBotClient client;
    private McpToolTestSupport.RecordingBridge bridge;
    private SlackBotMcpTool tool;

    @BeforeEach
    void start() {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();
        client = new SlackBotClient();
        client.apiBaseUrl = "http://localhost:" + wireMock.port();
        bridge = new McpToolTestSupport.RecordingBridge();
        tool = new SlackBotMcpTool(client, bridge, "xoxb-test-token");
    }

    @AfterEach
    void stop() {
        wireMock.stop();
    }

    // ── Success path ──────────────────────────────────────────────────────────

    @Test
    void sendSlackBot_success_returnsPostedWithTs() {
        wireMock.stubFor(post(urlEqualTo("/api/chat.postMessage"))
                .willReturn(okJson("{\"ok\":true,\"ts\":\"1638535627.000200\"}")));

        final String result = tool.sendSlackBot("C123ABC", "Hello", null);

        assertThat(result).isEqualTo("Posted to C123ABC (ts=1638535627.000200)");
    }

    @Test
    void sendSlackBot_success_bridgeCalledWithSlackBotIdChannelAndSanitizedText() {
        wireMock.stubFor(post(urlEqualTo("/api/chat.postMessage"))
                .willReturn(okJson("{\"ok\":true,\"ts\":\"1638535627.000200\"}")));

        tool.sendSlackBot("C123ABC", "line1\nline2", null);

        assertThat(bridge.lastConnectorId).isEqualTo(SlackBotClient.ID);
        assertThat(bridge.lastDestination).isEqualTo("C123ABC");
        assertThat(bridge.lastContent).isEqualTo("line1 line2");
    }

    @Test
    void sendSlackBot_withThreadTs_passesThreadTsToClient() {
        wireMock.stubFor(post(urlEqualTo("/api/chat.postMessage"))
                .willReturn(okJson("{\"ok\":true,\"ts\":\"1638535628.000300\"}")));

        tool.sendSlackBot("C123ABC", "Reply", "1638535627.000200");

        wireMock.verify(postRequestedFor(urlEqualTo("/api/chat.postMessage"))
                .withRequestBody(matchingJsonPath("$.thread_ts",
                        equalTo("1638535627.000200"))));
    }

    @Test
    void sendSlackBot_longText_contentTruncatedTo500InBridge() {
        wireMock.stubFor(post(urlEqualTo("/api/chat.postMessage"))
                .willReturn(okJson("{\"ok\":true,\"ts\":\"1638535627.000200\"}")));
        final String longBody = "x".repeat(600);

        tool.sendSlackBot("C123ABC", longBody, null);

        assertThat(bridge.lastContent).hasSize(500);
    }

    // ── Failure paths ─────────────────────────────────────────────────────────

    @Test
    void sendSlackBot_blankToken_returnsFailedWithoutHttpCall() {
        final SlackBotMcpTool blankTool = new SlackBotMcpTool(client, bridge, "");

        final String result = blankTool.sendSlackBot("C123ABC", "Hello", null);

        assertThat(result).isEqualTo(
                "Failed: casehub.connectors.slack-bot.token is not configured");
        assertThat(bridge.lastConnectorId).isNull();
        wireMock.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));
    }

    @Test
    void sendSlackBot_slackReturnsNotOk_returnsFailedNoBridgeCall() {
        wireMock.stubFor(post(urlEqualTo("/api/chat.postMessage"))
                .willReturn(okJson("{\"ok\":false,\"error\":\"channel_not_found\"}")));

        final String result = tool.sendSlackBot("CBAD", "Hello", null);

        assertThat(result).isEqualTo("Failed: channel_not_found");
        assertThat(bridge.lastConnectorId).isNull();
    }

    @Test
    void sendSlackBot_networkError_returnsFailedString() {
        wireMock.stop();

        final String result = tool.sendSlackBot("C123ABC", "Hello", null);

        assertThat(result).startsWith("Failed:");
        assertThat(bridge.lastConnectorId).isNull();
    }
}
