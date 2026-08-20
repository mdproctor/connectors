package io.casehub.connectors.chat.signal;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

import io.casehub.connectors.ConnectorService;
import io.casehub.connectors.InboundConnectorService;
import io.casehub.connectors.chat.ChatPlatformService;

import java.util.Map;

@QuarkusTest
@TestProfile(SignalCdiWiringTest.Profile.class)
class SignalCdiWiringTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "casehub.signal.api-url", "http://localhost:0",
                    "casehub.signal.number", "+10000000000",
                    "casehub.connectors.twilio.account-sid", "test",
                    "casehub.connectors.twilio.auth-token", "test",
                    "casehub.connectors.twilio.from", "+10000000001",
                    "casehub.connectors.whatsapp.api-token", "test",
                    "casehub.connectors.whatsapp.phone-number-id", "test");
        }
    }

    @Inject
    ChatPlatformService chatPlatformService;

    @Inject
    ConnectorService connectorService;

    @Inject
    InboundConnectorService inboundConnectorService;

    @Test
    void signal_chat_platform_registered() {
        assertThat(chatPlatformService.supports("signal")).isTrue();
        assertThat(chatPlatformService.platform("signal").id()).isEqualTo("signal");
    }

    @Test
    void signal_chat_platform_degrades_when_container_unreachable() {
        var platform = chatPlatformService.platform("signal");
        assertThat(platform.supports(io.casehub.connectors.chat.spi.Messaging.class)).isFalse();
        assertThat(platform.discovery().listChannels()).isEmpty();
    }

    @Test
    void signal_connector_registered() {
        assertThat(connectorService.supports("signal")).isTrue();
    }

    @Test
    void signal_inbound_connector_registered() {
        assertThat(inboundConnectorService.pullIds()).contains("signal-inbound");
    }
}
