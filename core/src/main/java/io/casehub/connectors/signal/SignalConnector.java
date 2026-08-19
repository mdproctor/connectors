package io.casehub.connectors.signal;

import java.util.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.casehub.connectors.Connector;
import io.casehub.connectors.ConnectorMessage;
import io.casehub.connectors.http.HttpHelper;

@ApplicationScoped
public class SignalConnector implements Connector {

    private static final Logger LOG = Logger.getLogger(SignalConnector.class.getName());

    @ConfigProperty(name = "casehub.signal.api-url", defaultValue = "")
    String apiUrl;

    @ConfigProperty(name = "casehub.signal.number", defaultValue = "")
    String number;

    @Override
    public String id() {
        return "signal";
    }

    @Override
    public boolean send(final ConnectorMessage message) {
        if (apiUrl.isBlank() || number.isBlank()) {
            LOG.warning("Signal connector not configured");
            return false;
        }

        final String dest = message.destination();
        final String text = message.title() != null && !message.title().isBlank()
                ? "*" + message.title() + "*\n" + (message.body() != null ? message.body() : "")
                : message.body();

        final StringBuilder json = new StringBuilder("{");
        json.append("\"number\":").append(HttpHelper.jsonQuote(number));
        json.append(",\"message\":").append(HttpHelper.jsonQuote(text));

        if (dest.startsWith("+")) {
            json.append(",\"recipients\":[").append(HttpHelper.jsonQuote(dest)).append("]");
        } else {
            json.append(",\"base64_group_id\":").append(HttpHelper.jsonQuote(dest));
        }
        json.append("}");

        return HttpHelper.postJson(apiUrl + "/v2/send", json.toString());
    }
}
