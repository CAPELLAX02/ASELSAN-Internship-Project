package com.aselsan.dkm.gateway.metrics;

import com.aselsan.dkm.gateway.capture.CaptureService;
import com.aselsan.dkm.gateway.events.EventHub;
import com.aselsan.dkm.gateway.net.Link;
import com.aselsan.dkm.gateway.net.LinkRegistry;
import com.aselsan.dkm.gateway.net.LinkState;
import com.aselsan.dkm.gateway.playback.PlaybackEngine;
import com.aselsan.dkm.gateway.playback.PlaybackState;
import com.aselsan.dkm.gateway.viz.VizPublisher;
import com.aselsan.dkm.gateway.ws.VizHub;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.annotation.Priority;
import jakarta.enterprise.event.Observes;
import jakarta.interceptor.Interceptor;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.function.ToDoubleFunction;

/**
 * Publishes the gateway's internal counters as Prometheus metrics.
 *
 * <p>Every meter here is <em>pull-based</em>: it reads a value the service was
 * already maintaining for its own use, so scraping costs a few field reads and
 * nothing on the hot path changes whether anyone is watching or not. That is
 * deliberate -- instrumentation that slows down the thing it measures produces
 * numbers about itself rather than about the system.
 *
 * <p>The metric worth watching is {@code dkm_playback_lag_milliseconds}. Bytes
 * per second says how fast the wire is moving; lag says whether the replay is
 * still a faithful reproduction of the recorded timeline. A run can be moving a
 * gigabyte a second and still be wrong if it has fallen behind its own clock.
 */
@ApplicationScoped
public class GatewayMetrics {

    private static final Logger LOG = Logger.getLogger(GatewayMetrics.class);

    @Inject
    MeterRegistry registry;

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

    void onStart(@Observes @Priority(Interceptor.Priority.APPLICATION + 1000) StartupEvent event) {
        if (links.links().isEmpty()) {
            LOG.warn("no links were registered before metrics started; per-link metrics will be missing");
        }
        for (Link link : links.links()) {
            String name = link.name();
            counter("dkm.link.bytes", link, Link::bytesIn, "bytes",
                    "Bytes received from the DKM", "link", name, "direction", "in");
            counter("dkm.link.bytes", link, Link::bytesOut, "bytes",
                    "Bytes sent to the DKM", "link", name, "direction", "out");
            counter("dkm.link.messages", link, Link::messagesIn, "messages",
                    "Messages received from the DKM", "link", name, "direction", "in");
            counter("dkm.link.messages", link, Link::messagesOut, "messages",
                    "Messages sent to the DKM", "link", name, "direction", "out");
            counter("dkm.link.write.stalls", link, Link::writeStalls, "stalls",
                    "Times the pacer had to wait for the socket's write queue to drain",
                    "link", name);
            gauge("dkm.link.up", link, l -> l.state() == LinkState.CONNECTED ? 1 : 0, null,
                    "1 when the DKM is attached to this link", "link", name);
            gauge("dkm.link.inbound.pending", link, Link::pendingInboundBytes, "bytes",
                    "Bytes held back waiting for the rest of a partial message", "link", name);
        }

        gauge("dkm.playback.state", playback, p -> p.state().ordinal(), null,
                "Playback state as the ordinal of IDLE, RUNNING, PAUSED, FINISHED");
        gauge("dkm.playback.running", playback, p -> p.state() == PlaybackState.RUNNING ? 1 : 0, null,
                "1 while the pacer is running");
        gauge("dkm.playback.speed", playback, PlaybackEngine::speed, null,
                "Replay speed multiplier currently in force");
        gauge("dkm.playback.lag", playback, PlaybackEngine::lagMillis, "milliseconds",
                "How far behind its own recorded timeline the replay is. Zero means the "
                        + "current speed is being sustained; a climbing value means it is not.");
        gauge("dkm.playback.remaining", playback, PlaybackEngine::remaining, "messages",
                "Messages still to be sent in the current run");
        counter("dkm.playback.sent", playback, PlaybackEngine::sentCount, "messages",
                "Messages sent to the DKM since the run started");
        counter("dkm.playback.sent.bytes", playback, PlaybackEngine::sentBytes, "bytes",
                "Bytes sent to the DKM since the run started");

        gauge("dkm.capture.messages", capture, CaptureService::size, "messages",
                "Captured DKM output currently held in memory");
        counter("dkm.capture.overflowed", capture, CaptureService::overflowed, "messages",
                "Output messages that could not be kept because the capture buffer was full");

        counter("dkm.viz.samples", viz, VizPublisher::samplesFromDkm, "samples",
                "Visualization samples extracted from DKM output", "source", "dkm");
        counter("dkm.viz.samples", viz, VizPublisher::samplesFromStimulus, "samples",
                "Visualization samples extracted from stimulus", "source", "stimulus");
        counter("dkm.viz.dropped", viz, VizPublisher::dropped, "samples",
                "Visualization samples dropped because a ring was full");
        counter("dkm.viz.thinned", viz, VizPublisher::stimulusThinned, "samples",
                "Stimulus samples skipped because the per-frame budget was spent");
        counter("dkm.viz.frames.sent", vizHub, VizHub::framesSent, "frames",
                "Visualization frames delivered to a browser");
        counter("dkm.viz.frames.skipped", vizHub, VizHub::framesSkipped, "frames",
                "Visualization frames skipped for a browser that had fallen behind");
        gauge("dkm.viz.subscribers", vizHub, VizHub::subscriberCount, null,
                "Browsers currently receiving the visualization stream");

        gauge("dkm.events.subscribers", events, EventHub::subscriberCount, null,
                "Browsers currently receiving control events");
        counter("dkm.events.dropped", events, EventHub::droppedEvents, "events",
                "Control events dropped for a browser that had fallen behind");

        LOG.infof("metrics registered for %d link(s); scrape at /q/metrics", links.links().size());
    }

    private <T> void counter(String name, T target, ToDoubleFunction<T> value, String unit,
                             String description, String... tags) {
        FunctionCounter.builder(name, target, value)
                .baseUnit(unit)
                .description(description)
                .tags(tags)
                .register(registry);
    }

    private <T> void gauge(String name, T target, ToDoubleFunction<T> value, String unit,
                           String description, String... tags) {
        Gauge.builder(name, target, value)
                .baseUnit(unit)
                .description(description)
                .tags(tags)
                .strongReference(true)
                .register(registry);
    }
}
