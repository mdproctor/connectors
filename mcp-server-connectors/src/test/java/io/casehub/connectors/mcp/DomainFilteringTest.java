package io.casehub.connectors.mcp;

import io.quarkiverse.mcp.server.ToolManager;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@TestProfile(DomainFilteringTest.ExplicitProfile.class)
class DomainFilteringTest {

    public static class ExplicitProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "casehub.mcp.mode", "explicit",
                    "casehub.mcp.domains", "chat,calendar"
            );
        }
    }

    @Inject
    ToolManager toolManager;

    @Test
    void chatToolsRegistered() {
        assertThat(toolManager.getTool("connectors_chat_send")).isNotNull();
    }

    @Test
    void calendarToolsRegistered() {
        assertThat(toolManager.getTool("connectors_calendar_listCalendars")).isNotNull();
    }

    @Test
    void bankToolsExcluded() {
        assertThat(toolManager.getTool("connectors_bank_listAccounts")).isNull();
    }

    @Test
    void healthToolsExcluded() {
        assertThat(toolManager.getTool("connectors_health_searchProviders")).isNull();
    }

    @Test
    void commerceToolsExcluded() {
        assertThat(toolManager.getTool("connectors_commerce_searchProducts")).isNull();
    }

    @Test
    void voiceReportExcluded() {
        assertThat(toolManager.getTool("connectors_voice_report")).isNull();
    }
}
