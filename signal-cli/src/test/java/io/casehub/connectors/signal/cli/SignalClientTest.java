package io.casehub.connectors.signal.cli;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import io.casehub.connectors.signal.cli.model.SendResponse;

class SignalClientTest {

    private WireMockServer wm;
    private SignalClient client;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wm.start();
        client = new SignalClient("http://localhost:" + wm.port());
    }

    @AfterEach
    void tearDown() {
        wm.stop();
    }

    @Test
    void send_1to1_message() {
        wm.stubFor(post(urlEqualTo("/v2/send"))
                .willReturn(okJson("{\"timestamp\":\"1724025600000\"}")));

        SendResponse result = client.send("+15551000000", "+15552000000",
                "Hello", List.of());

        assertThat(result.ok()).isTrue();
        assertThat(result.timestamp()).isEqualTo("1724025600000");

        wm.verify(postRequestedFor(urlEqualTo("/v2/send"))
                .withRequestBody(matchingJsonPath("$.number", equalTo("+15551000000")))
                .withRequestBody(matchingJsonPath("$.recipients[0]", equalTo("+15552000000")))
                .withRequestBody(matchingJsonPath("$.message", equalTo("Hello"))));
    }

    @Test
    void send_group_message() {
        wm.stubFor(post(urlEqualTo("/v2/send"))
                .willReturn(okJson("{\"timestamp\":\"1724025600001\"}")));

        SendResponse result = client.send("+15551000000", "dGVzdGdyb3VwaWQ=",
                "Group hello", List.of());

        assertThat(result.ok()).isTrue();

        wm.verify(postRequestedFor(urlEqualTo("/v2/send"))
                .withRequestBody(matchingJsonPath("$.number", equalTo("+15551000000")))
                .withRequestBody(matchingJsonPath("$.base64_group_id", equalTo("dGVzdGdyb3VwaWQ=")))
                .withRequestBody(matchingJsonPath("$.message", equalTo("Group hello"))));
    }

    @Test
    void send_returns_failure_on_error() {
        wm.stubFor(post(urlEqualTo("/v2/send"))
                .willReturn(aResponse().withStatus(500).withBody("Internal error")));

        SendResponse result = client.send("+15551000000", "+15552000000",
                "Hello", List.of());

        assertThat(result.ok()).isFalse();
        assertThat(result.timestamp()).isNull();
    }

    @Test
    void health_returns_true_when_healthy() {
        wm.stubFor(get(urlEqualTo("/v1/health")).willReturn(aResponse().withStatus(204)));

        assertThat(client.health()).isTrue();
    }

    @Test
    void health_returns_false_when_unreachable() {
        SignalClient bad = new SignalClient("http://localhost:1");

        assertThat(bad.health()).isFalse();
    }
}
