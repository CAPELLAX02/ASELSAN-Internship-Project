package com.aselsan.dkm.gateway.events;

import com.aselsan.dkm.gateway.capture.CaptureService;
import com.aselsan.dkm.gateway.net.Link;
import com.aselsan.dkm.gateway.net.LinkRegistry;
import com.aselsan.dkm.gateway.playback.PlaybackEngine;
import com.aselsan.dkm.gateway.viz.VizPublisher;
import com.aselsan.dkm.gateway.ws.VizHub;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodic throughput sampling.
 *
 * <p>Rates are differentiated here, once, rather than in the browser: the
 * sampling interval is known exactly on this side, and a browser tab that gets
 * throttled in the background would otherwise compute nonsense rates from
 * irregular arrivals.
 */
@ApplicationScoped
public class TelemetryPublisher {

    @Inject
    LinkRegistry links;

    @Inject
    PlaybackEngine playback;

    @Inject
    CaptureService capture;

    @Inject
    VizPublisher viz;

    @Inject
    VizHub vizHub;

    @Inject
    EventHub events;

    @ConfigProperty(name = "dkm.telemetry.interval-millis", defaultValue = "500")
    long intervalMillis;

    private ScheduledExecutorService scheduler;
    private final Map<String, long[]> previous = new HashMap<>();
    private long previousNanos;

    void onStart(@Observes StartupEvent event) {
        previousNanos = System.nanoTime();
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "dkm-telemetry");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleAtFixedRate(this::sample, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    void onStop(@Observes ShutdownEvent event) {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private void sample() {
        if (events.subscriberCount() == 0) {
            return;
        }
        long now = System.nanoTime();
        double seconds = Math.max((now - previousNanos) / 1e9, 1e-6);
        previousNanos = now;

        ObjectNode event = events.newEvent("telemetry");
        ObjectNode data = event.putObject("data");
        ArrayNode array = data.putArray("links");
        for (Link link : links.links()) {
            long[] last = previous.computeIfAbsent(link.name(), key -> new long[4]);
            long bytesIn = link.bytesIn();
            long bytesOut = link.bytesOut();
            long messagesIn = link.messagesIn();
            long messagesOut = link.messagesOut();

            ObjectNode node = array.addObject();
            node.put("name", link.name());
            node.put("state", link.state().name());
            node.put("bytesIn", bytesIn);
            node.put("bytesOut", bytesOut);
            node.put("messagesIn", messagesIn);
            node.put("messagesOut", messagesOut);
            node.put("bytesInPerSecond", (bytesIn - last[0]) / seconds);
            node.put("bytesOutPerSecond", (bytesOut - last[1]) / seconds);
            node.put("messagesInPerSecond", (messagesIn - last[2]) / seconds);
            node.put("messagesOutPerSecond", (messagesOut - last[3]) / seconds);
            node.put("writeStalls", link.writeStalls());

            last[0] = bytesIn;
            last[1] = bytesOut;
            last[2] = messagesIn;
            last[3] = messagesOut;
        }
        data.put("captureMessages", capture.size());
        data.put("captureOverflowed", capture.overflowed());
        data.put("playbackState", playback.state().name());
        data.put("playbackSent", playback.sentCount());
        data.put("playbackSentBytes", playback.sentBytes());
        data.put("vizSubscribers", vizHub.subscriberCount());
        data.put("vizFramesSent", vizHub.framesSent());
        data.put("vizFramesSkipped", vizHub.framesSkipped());
        data.put("vizSamplesDropped", viz.dropped());
        data.put("vizStimulusThinned", viz.stimulusThinned());
        events.publish(event);
    }
}
