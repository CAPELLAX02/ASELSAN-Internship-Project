package com.aselsan.dkm.gateway.session;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.aselsan.dkm.gateway.model.MessageSet;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * Loads a stimulus file at startup, when one is configured.
 *
 * <p>The requirements put loading in the operator's hands: FR-6 has them open a
 * file, and FR-11 has them press Start. That is the right default -- which
 * stimulus goes down a live link, and when, is a decision, not a side effect of
 * booting a service. So this only fills the working set; it never starts
 * playback, and the operator still presses Start.
 *
 * <p>What it buys is repeatability: a demo, a CI run, or a bench pass can bring
 * the gateway up with a known set already loaded instead of scripting an upload
 * first. Off unless {@code dkm.session.preload} names a file.
 */
@ApplicationScoped
public class SessionPreload {

    private static final Logger LOG = Logger.getLogger(SessionPreload.class);

    @Inject
    SessionService session;

    @ConfigProperty(name = "dkm.session.preload")
    Optional<String> preload;

    void onStart(@Observes StartupEvent event) {
        if (preload.isEmpty() || preload.get().isBlank()) {
            return;
        }
        Path path = Path.of(preload.get().trim()).toAbsolutePath().normalize();
        if (!Files.isReadable(path)) {
            // Not fatal: a missing demo file must not stop the gateway from
            // listening, which is the one thing the DKM needs it to do.
            LOG.warnf("dkm.session.preload names a file that cannot be read: %s", path);
            return;
        }
        try {
            MessageSet.ParseResult result = session.loadFile(path);
            LOG.infof("Preloaded %d messages (%d bytes) from %s; press Start to send",
                    result.messages(), result.bytesConsumed(), path);
        } catch (Exception e) {
            LOG.warnf(e, "Could not preload %s", path);
        }
    }
}
