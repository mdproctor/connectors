package io.casehub.connectors.signal.cli;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.casehub.connectors.signal.cli.model.SendResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

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

    @Test
    void listGroups() {
        wm.stubFor(get(urlEqualTo("/v1/groups/+15551000000"))
                           .willReturn(okJson("[{\"id\":\"Z3JvdXAx\",\"name\":\"Test Group\",\"description\":\"A group\",\"members\":[\"+15552000000\"]}]")));

        var groups = client.listGroups("+15551000000");

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).name()).isEqualTo("Test Group");
        assertThat(groups.get(0).id()).isEqualTo("Z3JvdXAx");
        assertThat(groups.get(0).members()).containsExactly("+15552000000");
    }

    @Test
    void getGroup() {
        wm.stubFor(get(urlEqualTo("/v1/groups/+15551000000/Z3JvdXAx"))
                           .willReturn(okJson("{\"id\":\"Z3JvdXAx\",\"name\":\"Test Group\",\"description\":\"A group\",\"members\":[\"+15552000000\",\"+15553000000\"]}")));

        var group = client.getGroup("+15551000000", "Z3JvdXAx");

        assertThat(group).isNotNull();
        assertThat(group.name()).isEqualTo("Test Group");
        assertThat(group.members()).hasSize(2);
    }

    @Test
    void createGroup() {
        wm.stubFor(post(urlEqualTo("/v1/groups/+15551000000"))
                           .willReturn(okJson("{\"id\":\"bmV3Z3JvdXA=\",\"name\":\"New Group\"}")));

        var group = client.createGroup("+15551000000", "New Group",
                                       java.util.List.of("+15552000000"));

        assertThat(group).isNotNull();
        assertThat(group.id()).isEqualTo("bmV3Z3JvdXA=");
    }

    @Test
    void addReaction() {
        wm.stubFor(post(urlEqualTo("/v1/reactions/+15551000000"))
                           .willReturn(aResponse().withStatus(204)));

        client.addReaction("+15551000000", "+15552000000", "👍",
                           "+15552000000", 1724025600000L);

        wm.verify(postRequestedFor(urlEqualTo("/v1/reactions/+15551000000"))
                          .withRequestBody(matchingJsonPath("$.reaction", equalTo("👍")))
                          .withRequestBody(matchingJsonPath("$.target_author", equalTo("+15552000000"))));
    }

    @Test
    void listContacts() {
        wm.stubFor(get(urlEqualTo("/v1/contacts/+15551000000"))
                           .willReturn(okJson("[{\"number\":\"+15553000000\",\"profileName\":\"Alice\"}]")));

        var contacts = client.listContacts("+15551000000");

        assertThat(contacts).hasSize(1);
        assertThat(contacts.get(0).profileName()).isEqualTo("Alice");
        assertThat(contacts.get(0).number()).isEqualTo("+15553000000");
    }

    @Test
    void downloadAttachment() {
        byte[] content = new byte[]{1, 2, 3, 4};
        wm.stubFor(get(urlEqualTo("/v1/attachments/abc123"))
                           .willReturn(aResponse().withBody(content)
                                                  .withHeader("Content-Type", "application/octet-stream")));

        byte[] result = client.downloadAttachment("abc123");

        assertThat(result).isEqualTo(content);
    }

    @Test
    void downloadAttachment_returns_null_on_failure() {
        wm.stubFor(get(urlEqualTo("/v1/attachments/bad"))
                           .willReturn(aResponse().withStatus(404)));

        assertThat(client.downloadAttachment("bad")).isNull();
    }


}
