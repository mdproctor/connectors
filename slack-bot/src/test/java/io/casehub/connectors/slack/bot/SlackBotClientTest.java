package io.casehub.connectors.slack.bot;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.notMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import io.casehub.connectors.DiscoveredTarget;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

class SlackBotClientTest {

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

    // ── Payload shape ─────────────────────────────────────────────────────────

    @Test
    void postMessage_sendsAuthorizationHeader() {
        wireMock.stubFor(post(urlEqualTo("/api/chat.postMessage"))
                .willReturn(okJson("{\"ok\":true,\"ts\":\"1638535627.000200\"}")));

        client.postMessage("xoxb-test-token", "C123ABC", "Hello", null);

        wireMock.verify(postRequestedFor(urlEqualTo("/api/chat.postMessage"))
                .withHeader("Authorization", equalTo("Bearer xoxb-test-token")));
    }

    @Test
    void postMessage_sendsChannelAndText() {
        wireMock.stubFor(post(urlEqualTo("/api/chat.postMessage"))
                .willReturn(okJson("{\"ok\":true,\"ts\":\"1638535627.000200\"}")));

        client.postMessage("xoxb-test-token", "C123ABC", "Hello world", null);

        wireMock.verify(postRequestedFor(urlEqualTo("/api/chat.postMessage"))
                .withRequestBody(matchingJsonPath("$.channel", equalTo("C123ABC")))
                .withRequestBody(matchingJsonPath("$.text", equalTo("Hello world"))));
    }

    @Test
    void postMessage_withoutThreadTs_noThreadTsField() {
        wireMock.stubFor(post(urlEqualTo("/api/chat.postMessage"))
                .willReturn(okJson("{\"ok\":true,\"ts\":\"1638535627.000200\"}")));

        client.postMessage("xoxb-test-token", "C123ABC", "Hello", null);

        wireMock.verify(postRequestedFor(urlEqualTo("/api/chat.postMessage"))
                .withRequestBody(notMatching(".*thread_ts.*")));
    }

    @Test
    void postMessage_withThreadTs_includesThreadTsField() {
        wireMock.stubFor(post(urlEqualTo("/api/chat.postMessage"))
                .willReturn(okJson("{\"ok\":true,\"ts\":\"1638535628.000400\"}")));

        client.postMessage("xoxb-test-token", "C123ABC", "Reply", "1638535600.000100");

        wireMock.verify(postRequestedFor(urlEqualTo("/api/chat.postMessage"))
                .withRequestBody(matchingJsonPath("$.thread_ts", equalTo("1638535600.000100"))));
    }

    // ── Response parsing ──────────────────────────────────────────────────────

    @Test
    void postMessage_okResponse_returnsSuccessResult() {
        wireMock.stubFor(post(urlEqualTo("/api/chat.postMessage"))
                .willReturn(okJson("{\"ok\":true,\"ts\":\"1638535627.000200\"}")));

        final SlackBotClient.PostResult result =
                client.postMessage("xoxb-test-token", "C123ABC", "Hello", null);

        assertThat(result.ok()).isTrue();
        assertThat(result.ts()).isEqualTo("1638535627.000200");
        assertThat(result.error()).isNull();
    }

    @Test
    void postMessage_notOkResponse_returnsFailureResult() {
        wireMock.stubFor(post(urlEqualTo("/api/chat.postMessage"))
                .willReturn(okJson("{\"ok\":false,\"error\":\"channel_not_found\"}")));

        final SlackBotClient.PostResult result =
                client.postMessage("xoxb-test-token", "C123ABC", "Hello", null);

        assertThat(result.ok()).isFalse();
        assertThat(result.ts()).isNull();
        assertThat(result.error()).isEqualTo("channel_not_found");
    }

    // ── Rate limit retry ──────────────────────────────────────────────────────

    @Test
    void postMessage_429WithRetryAfterZero_retriesOnceAndSucceeds() {
        wireMock.stubFor(post(urlEqualTo("/api/chat.postMessage"))
                .inScenario("rate-limit")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse()
                        .withStatus(429)
                        .withHeader("Retry-After", "0")
                        .withBody("{\"ok\":false,\"error\":\"ratelimited\"}"))
                .willSetStateTo("retried"));

        wireMock.stubFor(post(urlEqualTo("/api/chat.postMessage"))
                .inScenario("rate-limit")
                .whenScenarioStateIs("retried")
                .willReturn(okJson("{\"ok\":true,\"ts\":\"1638535627.000200\"}")));

        final SlackBotClient.PostResult result =
                client.postMessage("xoxb-test-token", "C123ABC", "Hello", null);

        assertThat(result.ok()).isTrue();
        wireMock.verify(2, postRequestedFor(urlEqualTo("/api/chat.postMessage")));
    }

    @Test
    void postMessage_429WithoutRetryAfter_sleepsOneSecondAndRetries() {
        wireMock.stubFor(post(urlEqualTo("/api/chat.postMessage"))
                .inScenario("rate-limit-no-header")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse()
                        .withStatus(429)
                        .withBody("{\"ok\":false,\"error\":\"ratelimited\"}"))
                .willSetStateTo("retried"));

        wireMock.stubFor(post(urlEqualTo("/api/chat.postMessage"))
                .inScenario("rate-limit-no-header")
                .whenScenarioStateIs("retried")
                .willReturn(okJson("{\"ok\":true,\"ts\":\"1638535627.000200\"}")));

        final SlackBotClient.PostResult result =
                client.postMessage("xoxb-test-token", "C123ABC", "Hello", null);

        assertThat(result.ok()).isTrue();
        wireMock.verify(2, postRequestedFor(urlEqualTo("/api/chat.postMessage")));
    }

    @Test
    void postMessage_429ThenAnotherError_returnsSecondResult() {
        wireMock.stubFor(post(urlEqualTo("/api/chat.postMessage"))
                .inScenario("rate-limit-fail")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse()
                        .withStatus(429)
                        .withHeader("Retry-After", "0"))
                .willSetStateTo("retried"));

        wireMock.stubFor(post(urlEqualTo("/api/chat.postMessage"))
                .inScenario("rate-limit-fail")
                .whenScenarioStateIs("retried")
                .willReturn(okJson("{\"ok\":false,\"error\":\"fatal_error\"}")));

        final SlackBotClient.PostResult result =
                client.postMessage("xoxb-test-token", "C123ABC", "Hello", null);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isEqualTo("fatal_error");
        wireMock.verify(2, postRequestedFor(urlEqualTo("/api/chat.postMessage")));
    }

    // ── Channel discovery ─────────────────────────────────────────────────────────

    @Test
    void listChannels_returnsDiscoveredTargets() {
        wireMock.stubFor(get(urlEqualTo(
                "/api/conversations.list?types=public_channel,private_channel&limit=200"))
                .willReturn(okJson("{\"ok\":true,\"channels\":["
                        + "{\"id\":\"C123ABC\",\"name\":\"general\"},"
                        + "{\"id\":\"C456DEF\",\"name\":\"engineering\"}"
                        + "]}")));

        final List<DiscoveredTarget> result = client.listChannels("xoxb-test-token");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).id()).isEqualTo("C123ABC");
        assertThat(result.get(0).displayName()).isEqualTo("#general");
        assertThat(result.get(1).id()).isEqualTo("C456DEF");
        assertThat(result.get(1).displayName()).isEqualTo("#engineering");
    }

    @Test
    void listChannels_sendsAuthorizationHeader() {
        wireMock.stubFor(get(urlEqualTo(
                "/api/conversations.list?types=public_channel,private_channel&limit=200"))
                .willReturn(okJson("{\"ok\":true,\"channels\":[]}")));

        client.listChannels("xoxb-my-token");

        wireMock.verify(getRequestedFor(urlEqualTo(
                "/api/conversations.list?types=public_channel,private_channel&limit=200"))
                .withHeader("Authorization", equalTo("Bearer xoxb-my-token")));
    }

    @Test
    void listChannels_slackReturnsNotOk_returnsEmptyList() {
        wireMock.stubFor(get(urlEqualTo(
                "/api/conversations.list?types=public_channel,private_channel&limit=200"))
                .willReturn(okJson("{\"ok\":false,\"error\":\"invalid_auth\"}")));

        final List<DiscoveredTarget> result = client.listChannels("xoxb-bad");

        assertThat(result).isEmpty();
    }
}
