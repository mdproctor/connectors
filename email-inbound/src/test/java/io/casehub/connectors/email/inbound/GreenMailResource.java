package io.casehub.connectors.email.inbound;

import java.util.Map;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

public class GreenMailResource implements QuarkusTestResourceLifecycleManager {

    static GreenMail INSTANCE;

    @Override
    public Map<String, String> start() {
        INSTANCE = new GreenMail(ServerSetupTest.SMTP_IMAP);
        INSTANCE.withConfiguration(GreenMailConfiguration.aConfig()
                .withUser("inbox@example.com", "password"));
        INSTANCE.start();
        return Map.of(
                "casehub.connectors.email-inbound.host", "localhost",
                "casehub.connectors.email-inbound.port",
                        String.valueOf(INSTANCE.getImap().getPort()),
                "casehub.connectors.email-inbound.tls", "false",
                "casehub.connectors.email-inbound.username", "inbox@example.com",
                "casehub.connectors.email-inbound.password", "password",
                "casehub.connectors.email-inbound.poll-interval-seconds", "1");
    }

    @Override
    public void stop() {
        if (INSTANCE != null) INSTANCE.stop();
    }
}
