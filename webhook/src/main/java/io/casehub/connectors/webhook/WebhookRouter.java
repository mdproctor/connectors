package io.casehub.connectors.webhook;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import io.casehub.connectors.HttpMethod;
import io.casehub.connectors.InboundConnectorService;
import io.casehub.connectors.WebhookInboundConnector;
import io.casehub.connectors.WebhookRequest;
import io.casehub.connectors.WebhookResult;
import io.quarkus.arc.All;

/**
 * JAX-RS entry point for all inbound webhook requests.
 *
 * <p>Dispatches {@code GET|POST /connectors/{id}/webhook} to the matching
 * {@link WebhookInboundConnector} and translates the result to an HTTP response.
 *
 * <h2>Response mapping</h2>
 * <ul>
 * <li>{@code Delivered} → 200 OK (after calling {@link InboundConnectorService#receive} for
 *     each message)
 * <li>{@code Challenged} → 200 OK with the challenge body
 * <li>{@code Ignored} → 200 OK (no event fired)
 * <li>{@code Unauthorized} from POST → 200 OK + SECURITY WARNING log (suppress retry storms)
 * <li>{@code Unauthorized} from GET → 403 Forbidden (admin needs a clear failure signal)
 * </ul>
 *
 * <h2>Exception handling</h2>
 * Any exception from {@code connector.handle()} is caught, logged at SEVERE, and mapped
 * to 200 OK to suppress platform retries. The router's try-catch is defense-in-depth;
 * connectors are still expected to catch their own exceptions.
 */
@Path("/connectors")
@ApplicationScoped
public class WebhookRouter {

    private static final Logger LOG = Logger.getLogger(WebhookRouter.class.getName());

    private final Map<String, WebhookInboundConnector> registry;
    private final InboundConnectorService service;

    @Inject
    WebhookRouter(@All final List<WebhookInboundConnector> connectors,
                  final InboundConnectorService service) {
        this.service = service;
        connectors.forEach(c -> validateId(c.id()));
        this.registry = connectors.stream().collect(Collectors.toMap(
                WebhookInboundConnector::id,
                Function.identity(),
                (a, b) -> {
                    throw new IllegalStateException(
                            "Duplicate webhook connector id: '" + a.id() + "'");
                }));
    }

    @POST
    @Path("/{id}/webhook")
    @Consumes({ "application/json", "application/x-www-form-urlencoded" })
    public Response post(@PathParam("id") final String id,
                         @Context final HttpHeaders httpHeaders,
                         @Context final UriInfo uriInfo,
                         final String body) {
        return dispatch(id, new WebhookRequest(
                body,
                lowerCaseKeys(httpHeaders),
                flatQueryParams(uriInfo),
                HttpMethod.POST,
                uriInfo.getAbsolutePath().toString()));
    }

    @GET
    @Path("/{id}/webhook")
    public Response get(@PathParam("id") final String id,
                        @Context final HttpHeaders httpHeaders,
                        @Context final UriInfo uriInfo) {
        return dispatch(id, new WebhookRequest(
                "",
                lowerCaseKeys(httpHeaders),
                flatQueryParams(uriInfo),
                HttpMethod.GET,
                uriInfo.getRequestUri().toString()));
    }

    private static Map<String, String> flatQueryParams(final UriInfo uriInfo) {
        return uriInfo.getQueryParameters().entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().isEmpty() ? "" : e.getValue().get(0)));
    }

    /** Returns the ids of all registered webhook connectors. */
    public Set<String> webhookIds() {
        return Set.copyOf(registry.keySet());
    }

    private Response dispatch(final String id, final WebhookRequest request) {
        final WebhookInboundConnector connector = registry.get(id);
        if (connector == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("No webhook connector registered for id '" + id + "'")
                    .build();
        }
        try {
            return switch (connector.handle(request)) {
                case WebhookResult.Delivered(final List<io.casehub.connectors.InboundMessage> msgs) -> {
                    msgs.forEach(service::receive);
                    yield Response.ok().build();
                }
                case WebhookResult.Challenged(final String responseBody, final String contentType) ->
                    Response.ok(responseBody).type(contentType).build();
                case WebhookResult.Ignored() -> Response.ok().build();
                case WebhookResult.Unauthorized() -> {
                    LOG.warning("SECURITY: rejected webhook request for connector '" + id
                            + "' — signature invalid or replay detected. method="
                            + request.method() + " url=" + request.requestUrl());
                    yield request.method() == HttpMethod.GET
                            ? Response.status(Response.Status.FORBIDDEN).build()
                            : Response.ok().build();
                }
            };
        } catch (final Exception e) {
            LOG.log(Level.SEVERE, "Unexpected exception in webhook connector '" + id
                    + "': " + e.getMessage(), e);
            return Response.ok().build();
        }
    }

    private static Map<String, List<String>> lowerCaseKeys(final HttpHeaders headers) {
        return headers.getRequestHeaders().entrySet().stream()
                .collect(Collectors.toMap(
                        e -> e.getKey().toLowerCase(Locale.ROOT),
                        Map.Entry::getValue,
                        (a, b) -> {
                            final List<String> merged = new ArrayList<>(a);
                            merged.addAll(b);
                            return merged;
                        }));
    }

    private static void validateId(final String id) {
        if (id == null || !id.matches("[a-z0-9][a-z0-9\\-]*")) {
            throw new IllegalStateException(
                    "Webhook connector id '" + id
                    + "' is invalid — must be lowercase, URL-safe, no slashes or spaces");
        }
    }
}
