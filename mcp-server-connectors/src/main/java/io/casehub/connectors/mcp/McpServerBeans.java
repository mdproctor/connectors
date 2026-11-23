package io.casehub.connectors.mcp;

import io.casehub.connectors.document.spi.DocumentPlatform;
import io.casehub.connectors.document.spi.DocumentPlatformService;
import io.casehub.connectors.voice.spi.RoutingVoicePlatformService;
import io.casehub.connectors.voice.spi.VoicePlatform;
import io.casehub.connectors.voice.spi.VoicePlatformService;
import io.casehub.connectors.voice.spi.VoiceRegistry;
import io.casehub.connectors.voice.spi.InMemoryVoiceRegistry;
import io.casehub.platform.api.delivery.DeliveryChannelRegistry;
import io.casehub.platform.delivery.NoOpDeliveryChannelRegistry;
import io.quarkus.arc.All;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import java.util.List;

@ApplicationScoped
public class McpServerBeans {

    @Produces @DefaultBean @ApplicationScoped
    public DocumentPlatformService documentPlatformService(@All List<DocumentPlatform> platforms) {
        return new DocumentPlatformService(platforms);
    }

    @Produces @ApplicationScoped
    public RoutingVoicePlatformService routingVoicePlatformService() {
        return new RoutingVoicePlatformService();
    }

    @Produces @ApplicationScoped
    public VoiceRegistry voiceRegistry(RoutingVoicePlatformService routing) {
        return routing.voiceRegistry();
    }

    @Produces @DefaultBean @ApplicationScoped
    public VoicePlatformService voicePlatformService(@All List<VoicePlatform> platforms) {
        return new VoicePlatformService(platforms);
    }

    @Produces @DefaultBean @ApplicationScoped
    public DeliveryChannelRegistry deliveryChannelRegistry() {
        return new NoOpDeliveryChannelRegistry();
    }
}
