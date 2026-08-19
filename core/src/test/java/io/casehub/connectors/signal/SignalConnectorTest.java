package io.casehub.connectors.signal;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import io.casehub.connectors.ConnectorMessage;

class SignalConnectorTest {

    private WireMockServer wm;
    private SignalConnector connector;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wm.start();
        connector = new SignalConnector();
        connector.apiUrl = "http://localhost:" + wm.port();
        connector.number = "+15551000000";
    }

    @AfterEach
    void tearDown() {
        wm.stop();
    }

    @Test
    void id_and_channelType() {
        assertThat(connector.id()).isEqualTo("signal");
        assertThat(connector.channelType()).isEqualTo("signal");
    }

    @Test
    void send_1to1_formats_recipients_field() {
        wm.stubFor(post(urlEqualTo("/v2/send"))
                .willReturn(okJson("{\"timestamp\":\"123\"}")));

        boolean ok = connector.send(new ConnectorMessage(
                "+15552000000", "Alert", "System down"));

        assertThat(ok).isTrue();
        wm.verify(postRequestedFor(urlEqualTo("/v2/send"))
                .withRequestBody(containing("\"recipients\""))
                .withRequestBody(containing("+15552000000")));
    }

    @Test
    void send_group_formats_base64_group_id_field() {
        wm.stubFor(post(urlEqualTo("/v2/send"))
                .willReturn(okJson("{\"timestamp\":\"123\"}")));

        boolean ok = connector.send(new ConnectorMessage(
                "Z3JvdXAx", null, "Group notification"));

        assertThat(ok).isTrue();
        wm.verify(postRequestedFor(urlEqualTo("/v2/send"))
                .withRequestBody(containing("\"base64_group_id\""))
                .withRequestBody(containing("Z3JvdXAx")));
    }

    @Test
    void send_returns_false_when_not_configured() {
        SignalConnector unconfigured = new SignalConnector();
        unconfigured.apiUrl = "";
        unconfigured.number = "";

        boolean ok = unconfigured.send(new ConnectorMessage(
                "+15552000000", null, "Hello"));

        assertThat(ok).isFalse();
    }
}
