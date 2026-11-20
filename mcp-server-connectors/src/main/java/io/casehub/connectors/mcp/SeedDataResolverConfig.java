package io.casehub.connectors.mcp;

import io.casehub.connectors.seed.SeedDataResolver;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

@ApplicationScoped
public class SeedDataResolverConfig {

    private static final Logger LOG = Logger.getLogger(SeedDataResolverConfig.class);

    @ConfigProperty(name = "casehub.ref.seed-dir")
    Optional<String> seedDir;

    void onStart(@Observes StartupEvent ev) {
        seedDir.map(Path::of).ifPresent(dir -> {
            SeedDataResolver.setSeedDir(dir);
            if (!Files.isDirectory(dir)) {
                LOG.warnf("casehub.ref.seed-dir=%s does not exist (CWD: %s). "
                        + "Seed data will fall back to classpath resources. "
                        + "Run from target/quarkus-app/ where the build copies seed YAML.",
                        dir, Path.of("").toAbsolutePath());
            }
        });
    }
}
