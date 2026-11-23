package io.casehub.connectors.voice.config;

import io.casehub.connectors.voice.spi.InMemoryVoiceRegistry;
import io.casehub.connectors.voice.spi.RoutingVoicePlatformService;
import io.casehub.connectors.voice.spi.VoicePlatform;
import io.casehub.connectors.voice.spi.VoicePlatformService;
import io.casehub.connectors.voice.spi.VoiceRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.util.Optional;

@ApplicationScoped
public class VoiceConfigProducer {

    @Inject
    Instance<VoicePlatform> platforms;

    @ConfigProperty(name = "casehub.voice.profile")
    Optional<String> profile;

    @ConfigProperty(name = "casehub.voice.project-dir")
    Optional<String> projectDir;

    private volatile VoiceManifestResult cachedResult;
    private volatile InMemoryVoiceRegistry cachedRegistry;
    private volatile VoiceManifest cachedManifest;

    private void ensureLoaded() {
        if (cachedRegistry != null) return;
        synchronized (this) {
            if (cachedRegistry != null) return;
            cachedRegistry = new InMemoryVoiceRegistry();
            var loader     = new VoiceManifestLoader();
            var dir        = projectDir.map(java.nio.file.Path::of).orElse(null);
            cachedManifest = loader.load(dir, profile.orElse(null));
            var processor  = new VoiceManifestProcessor(cachedRegistry);
            cachedResult   = processor.process(cachedManifest);
        }
    }

    @Produces
    @ApplicationScoped
    public VoiceRegistry voiceRegistry() {
        ensureLoaded();
        return cachedRegistry;
    }

    @Produces
    @ApplicationScoped
    public RoutingVoicePlatformService routingVoicePlatformService() {
        ensureLoaded();
        var platformService = new VoicePlatformService(platforms.stream().toList());
        return new RoutingVoicePlatformService(
                platformService, cachedRegistry, cachedResult.aliases(), cachedManifest.defaults());
    }
}
