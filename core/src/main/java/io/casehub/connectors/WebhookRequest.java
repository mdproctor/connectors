package io.casehub.connectors;

import java.util.List;
import java.util.Map;

/**
 * A transport-agnostic representation of an inbound HTTP webhook request.
 *
 * <p>Built by {@code WebhookRouter} from JAX-RS types at the HTTP boundary.
 * Connector implementations receive this record and have no dependency on JAX-RS.
 *
 * <h2>Header normalisation</h2>
 * The router normalises all header keys to lower-case before building this record.
 * Connectors use {@code request.headers().get("x-slack-signature")} without
 * per-site case handling.
 *
 * <h2>requestUrl</h2>
 * Required for Twilio's {@code X-Twilio-Signature} scheme, which signs the full
 * request URL. Populated by the router from {@code UriInfo.getAbsolutePath()} (POST)
 * or {@code UriInfo.getRequestUri()} (GET). If the service runs behind a reverse proxy,
 * configure {@code quarkus.http.proxy.proxy-address-forwarding=true} so the URL
 * reflects the public address Twilio used when signing.
 */
public record WebhookRequest(
        String body,
        Map<String, List<String>> headers,
        Map<String, String> queryParams,
        HttpMethod method,
        String requestUrl) {

    /** Returns the first value for the given (lower-cased) header name, or null. */
    public String header(final String name) {
        final List<String> values = headers.get(name);
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }
}
