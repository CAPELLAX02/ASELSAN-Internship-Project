package com.aselsan.dkm.gateway.viz;

import com.aselsan.dkm.gateway.events.EventHub;
import com.aselsan.dkm.gateway.model.MessageArena;
import com.aselsan.dkm.gateway.schema.MessageCodec;
import com.aselsan.dkm.gateway.schema.SchemaService;
import com.aselsan.dkm.gateway.ws.VizHub;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.netty.buffer.ByteBuf;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Turns messages into visualization samples and pushes them to the browser as
 * compact binary frames.
 *
 * <p>The path is deliberately three-staged, and each stage exists to protect the
 * one before it:
 * <ol>
 *   <li>A socket's event loop (or the replay pacer) extracts a few numbers from
 *       a message it already has in cache and drops a fixed-size record into a
 *       lock-free ring. No allocation, no locks, no I/O.</li>
 *   <li>A dedicated thread wakes on a fixed interval, drains the rings into one
 *       frame, and hands it to the WebSocket hub. Nothing on the receive path
 *       waits for this.</li>
 *   <li>A browser that falls behind gets frames skipped, not queued.</li>
 * </ol>
 * That is what makes G10/NFR-6 a property of the design rather than a hope: a
 * slow render frame cannot reach back and stall the socket read that fed it.
 */
@ApplicationScoped
public class VizPublisher {

    private static final Logger LOG = Logger.getLogger(VizPublisher.class);

    /** 'D''K''M''V' -- the browser checks this before trusting a frame's layout. */
    public static final int MAGIC = 0x444B_4D56;
    public static final int PROTOCOL_VERSION = 1;
    public static final int FRAME_HEADER_BYTES = 24;

    @Inject
    SchemaService schemaService;

    @Inject
    VizCatalog catalog;

    @Inject
    VizConfig config;

    @Inject
    VizHub hub;

    @Inject
    EventHub events;

    @Inject
    MeterRegistry registry;

    /** How long the oldest sample in a frame waited between the wire and the socket. */
    private Timer holdTimer;
    /** How long building one frame takes -- the part of the budget this service controls. */
    private Timer buildTimer;

    /**
     * Monotonic instant the first sample of the current frame was pushed, or 0
     * when the rings have been empty since the last frame. This is the server's
     * half of the wire-to-pixel budget, and the half that can actually be
     * measured here -- the rest is one browser animation frame.
     */
    private final AtomicLong oldestPendingNanos = new AtomicLong();

    private final BeamHeadingIndex beams = new BeamHeadingIndex();
    private final LongAdder samplesIn = new LongAdder();
    private final LongAdder samplesOut = new LongAdder();
    private final LongAdder stimulusThinned = new LongAdder();
    private final AtomicInteger stimulusBudget = new AtomicInteger();
    private final AtomicInteger sequence = new AtomicInteger();

    private VizRing[] inboundRings;
    private VizRing stimulusRing;
    private ScheduledExecutorService scheduler;
    private byte[] frameScratch;

    void onStart(@Observes StartupEvent event) {
        int links = schemaService.model().peerModules().size();
        inboundRings = new VizRing[links];
        for (int i = 0; i < links; i++) {
            inboundRings[i] = new VizRing(config.ringCapacity());
        }
        stimulusRing = new VizRing(config.ringCapacity());
        stimulusBudget.set(config.stimulusBudgetPerFrame());
        frameScratch = new byte[FRAME_HEADER_BYTES + config.maxRecordsPerFrame() * VizRing.RECORD_BYTES];

        holdTimer = Timer.builder("dkm.viz.hold")
                .description("Time the oldest sample in a frame waited between arriving and being sent")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        buildTimer = Timer.builder("dkm.viz.build")
                .description("Time spent draining the rings and building one visualization frame")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "dkm-viz");
            thread.setDaemon(true);
            return thread;
        });
        long interval = config.frameIntervalMillis();
        scheduler.scheduleAtFixedRate(this::tick, interval, interval, TimeUnit.MILLISECONDS);
        LOG.infof("visualization publisher started: %d ms frames, %d-sample rings, %d records/frame",
                interval, config.ringCapacity(), config.maxRecordsPerFrame());
    }

    void onStop(@Observes ShutdownEvent event) {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    // ---- producers -------------------------------------------------------

    /** Called on a link's event loop for every message the DKM sent us. */
    public void onInbound(int linkIndex, long moduleId, long msgId, ByteBuf buf, int offset) {
        MessageCodec codec = schemaService.codec();
        feedBeamIndex(codec, moduleId, msgId, buf, offset);
        VizExtractor extractor = catalog.extractor(moduleId, msgId);
        if (extractor == null || extractor.kind == VizKind.NONE) {
            return;
        }
        int emitted = extractor.extract(codec, buf, offset, sequence.incrementAndGet(), linkIndex,
                true, beams, inboundRings[linkIndex]);
        if (emitted > 0) {
            markPending();
        }
        samplesIn.add(emitted);
    }

    /**
     * Called by the replay pacer for messages it has just sent, so stimulus is
     * drawn the same way output is (FR-28).
     *
     * @return false once the per-frame budget is spent, which is the pacer's cue
     *         to stop sampling this batch rather than slow down for the picture
     */
    public boolean onStimulus(int linkIndex, long moduleId, long msgId, MessageArena arena,
                              int offset, int length) {
        if (stimulusBudget.get() <= 0) {
            stimulusThinned.increment();
            return false;
        }
        VizExtractor extractor = catalog.extractor(moduleId, msgId);
        MessageCodec codec = schemaService.codec();
        ByteBuf view = arena.slice(offset, length);
        feedBeamIndex(codec, moduleId, msgId, view, 0);
        if (extractor == null || extractor.kind == VizKind.NONE) {
            return true;
        }
        stimulusBudget.decrementAndGet();
        int emitted = extractor.extract(codec, view, 0, sequence.incrementAndGet(), linkIndex,
                false, beams, stimulusRing);
        if (emitted > 0) {
            markPending();
        }
        samplesOut.add(emitted);
        return true;
    }

    /** Records when this frame's oldest sample arrived; only the first write per frame does work. */
    private void markPending() {
        oldestPendingNanos.compareAndSet(0, System.nanoTime());
    }

    private void feedBeamIndex(MessageCodec codec, long moduleId, long msgId, ByteBuf buf, int offset) {
        VizCatalog.BeamIndexSpec spec = catalog.beamIndexSpec();
        if (spec == null || spec.type().module.id() != moduleId || spec.type().msgId != msgId) {
            return;
        }
        long beamId = codec.readInteger(spec.keyType(), buf, offset + spec.keyOffset());
        double heading = codec.readNumeric(spec.valueType(), buf, offset + spec.valueOffset());
        beams.put(beamId, heading);
    }

    public void resetScene() {
        beams.clear();
        events.publish("vizReset", data -> data.put("reason", "scene cleared"));
    }

    // ---- consumer --------------------------------------------------------

    private void tick() {
        long startedNanos = System.nanoTime();
        long pendingSince = oldestPendingNanos.getAndSet(0);
        try {
            stimulusBudget.set(config.stimulusBudgetPerFrame());
            if (hub.subscriberCount() == 0) {
                drainAndDiscard();
                return;
            }
            ByteBuffer out = ByteBuffer.wrap(frameScratch).order(ByteOrder.LITTLE_ENDIAN);
            out.position(FRAME_HEADER_BYTES);

            int max = config.maxRecordsPerFrame();
            int records = 0;
            long dropped = 0;
            for (VizRing ring : inboundRings) {
                records += ring.drain(out, max - records);
                dropped += ring.droppedCount();
            }
            records += stimulusRing.drain(out, max - records);
            dropped += stimulusRing.droppedCount();

            if (records == 0) {
                return;
            }

            out.putInt(0, MAGIC);
            out.putShort(4, (short) PROTOCOL_VERSION);
            out.putShort(6, (short) 0);
            out.putInt(8, records);
            out.putInt(12, (int) Math.min(dropped, Integer.MAX_VALUE));
            out.putDouble(16, System.currentTimeMillis());

            int frameBytes = FRAME_HEADER_BYTES + records * VizRing.RECORD_BYTES;
            byte[] frame = new byte[frameBytes];
            System.arraycopy(frameScratch, 0, frame, 0, frameBytes);
            hub.broadcast(frame, config.maxInFlightFrames());

            if (pendingSince != 0) {
                holdTimer.record(System.nanoTime() - pendingSince, TimeUnit.NANOSECONDS);
            }
        } catch (RuntimeException e) {
            LOG.error("visualization frame failed", e);
        } finally {
            buildTimer.record(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS);
        }
    }

    private void drainAndDiscard() {
        ByteBuffer sink = ByteBuffer.wrap(frameScratch).order(ByteOrder.LITTLE_ENDIAN);
        for (VizRing ring : inboundRings) {
            sink.clear();
            while (ring.drain(sink, config.maxRecordsPerFrame()) > 0) {
                sink.clear();
            }
        }
        sink.clear();
        while (stimulusRing.drain(sink, config.maxRecordsPerFrame()) > 0) {
            sink.clear();
        }
    }

    // ---- telemetry -------------------------------------------------------

    public long samplesFromDkm() {
        return samplesIn.sum();
    }

    public long samplesFromStimulus() {
        return samplesOut.sum();
    }

    public long stimulusThinned() {
        return stimulusThinned.sum();
    }

    public long dropped() {
        long total = stimulusRing == null ? 0 : stimulusRing.droppedCount();
        if (inboundRings != null) {
            for (VizRing ring : inboundRings) {
                total += ring.droppedCount();
            }
        }
        return total;
    }

    public int knownBeams() {
        return beams.size();
    }
}
