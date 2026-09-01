package com.aselsan.dkm.gateway.playback;

import com.aselsan.dkm.gateway.events.EventHub;
import com.aselsan.dkm.gateway.model.MessageEntry;
import com.aselsan.dkm.gateway.model.MessageSet;
import com.aselsan.dkm.gateway.net.Link;
import com.aselsan.dkm.gateway.net.LinkRegistry;
import com.aselsan.dkm.gateway.session.SessionService;
import com.aselsan.dkm.gateway.viz.VizPublisher;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.buffer.ByteBuf;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Sends the stimulus set to the DKM, paced off one shared replay clock.
 *
 * <h2>Why one clock</h2>
 * Some message types depend on state a <em>different</em> link established
 * earlier: an RSP {@code DetectionReport} names a {@code beam_id} the DKM only
 * knows about if an RSM {@code BeamReport} already announced it. Pacing each
 * link off "time since the last message on this link" lets a sparse link drift
 * ahead of a dense one and silently breaks that ordering -- the DKM just drops
 * the detection and the operator sees missing output with no error anywhere.
 * So all links share {@link ReplayClock} and every send time comes from the
 * message's own recorded timestamp (FR-13).
 *
 * <h2>Why it is fast</h2>
 * Messages are already encoded and already contiguous in the arena before the
 * run starts, so the pacer's inner loop does no encoding, no allocation and no
 * schema work -- it computes a deadline, then hands the socket one retained
 * slice covering as many adjacent due messages as fit. At the stimulus rates
 * this targets, write count is what decides throughput, and coalescing is what
 * gets it down.
 *
 * <h2>Threading</h2>
 * One dedicated platform thread does all the pacing for all links. Sharing the
 * clock across three event loops would need cross-loop coordination on every
 * message; one thread that owns the clock and writes into three sockets needs
 * none. Vert.x makes writes from a foreign thread safe, and backpressure is
 * handled by parking on the socket's drain handler rather than by unbounded
 * buffering.
 */
@ApplicationScoped
public class PlaybackEngine {

    private static final Logger LOG = Logger.getLogger(PlaybackEngine.class);
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    @Inject
    SessionService session;

    @Inject
    LinkRegistry linkRegistry;

    @Inject
    EventHub events;

    @Inject
    PlaybackConfig config;

    @Inject
    VizPublisher viz;

    private volatile PlaybackState state = PlaybackState.IDLE;
    private volatile ReplayClock clock = new ReplayClock(System.nanoTime(), 0, 1.0);
    private volatile PaceMode mode = PaceMode.TIMESTAMP;
    private volatile double speed = 1.0;
    private volatile ReplayPlan plan;
    private volatile Thread pacer;
    private volatile boolean pacing;
    private volatile String lastError;

    /**
     * Publication barrier. The pacer writes {@link MessageEntry#sent} as plain
     * fields (a volatile store per message would cost real throughput at these
     * rates) and then publishes this counter; any reader that loads this first
     * is guaranteed to see the sent markers behind it.
     */
    private final AtomicLong sentCounter = new AtomicLong();
    private final AtomicLong sentBytes = new AtomicLong();
    private volatile long runStartedWallClock;
    private volatile long runFinishedWallClock;
    /**
     * Where the replay reached, in recorded-timeline milliseconds. Kept
     * separately because the clock's anchor only describes where a run
     * <em>started</em> -- reading it once the pacer has stopped would report a
     * finished run as sitting at its own start.
     */
    private volatile double lastVirtualMillis;

    /**
     * How far behind schedule the pacer is, in recorded-timeline milliseconds.
     *
     * <p>This is the single number that says whether a given speed is actually
     * sustainable. If it hovers at zero the run is keeping up and the recorded
     * timing is being reproduced faithfully; if it climbs, messages are going
     * out later than they were recorded and the replay is no longer a truthful
     * reproduction -- which matters far more than raw throughput, because a
     * replay that silently drifts breaks exactly the cross-link ordering the
     * shared clock exists to protect.
     */
    private volatile double lagMillis;

    /**
     * How long the last plan build took. Reported separately because it is
     * inside the wall clock between "start" and "finished" but is not send
     * throughput -- on a multi-million-message set it is the difference between
     * a defensible number and a flattering one.
     */
    /** How big this run was when it began, so progress keeps one denominator. */
    private volatile int runPlannedMessages;
    private volatile long runPlannedBytes;

    private volatile long planBuildMillis;

    void onStop(@Observes ShutdownEvent event) {
        stopPacer();
    }

    // ---- control (FR-11, FR-12) -----------------------------------------

    public synchronized void start() {
        start(0);
    }

    /**
     * Starts a run, optionally from a chosen message rather than the first
     * (FR-11). Everything before it is marked as already sent, which is the
     * same state a partially-completed run leaves behind -- so pausing,
     * editing and resuming all behave exactly as they do mid-run.
     */
    public synchronized void start(long fromMessageId) {
        if (state == PlaybackState.RUNNING) {
            return;
        }
        if (state == PlaybackState.PAUSED) {
            resume();
            return;
        }
        session.resetSentMarkers(fromMessageId);
        sentCounter.set(0);
        sentBytes.set(0);
        lastVirtualMillis = 0;
        lastError = null;
        runStartedWallClock = System.currentTimeMillis();
        runFinishedWallClock = 0;
        launch("started", true);
    }

    public synchronized void resume() {
        if (state != PlaybackState.PAUSED) {
            return;
        }
        launch("resumed", false);
    }

    /**
     * FR-14: build a fresh plan over whatever is still unsent, then anchor the
     * clock at this instant. Everything about "where the run is" lives in the
     * sent markers, so an edit made while paused is picked up here with no
     * special case.
     */
    private void launch(String verb, boolean fresh) {
        long planStarted = System.currentTimeMillis();
        ReplayPlan built = buildPlan();
        planBuildMillis = System.currentTimeMillis() - planStarted;
        if (built.totalMessages == 0) {
            state = PlaybackState.FINISHED;
            runFinishedWallClock = System.currentTimeMillis();
            events.warn("playback", "nothing left to send -- the set is empty, already sent, or every message is flagged unsendable");
            publishState();
            return;
        }
        if (linkRegistry.links().stream().noneMatch(Link::isConnected)) {
            lastError = "no link is connected -- the DKM connects out once at its own startup and never retries, "
                    + "so start it after this simulator is listening";
            events.error("playback", lastError);
            publishState();
            throw new IllegalStateException(lastError);
        }

        if (fresh) {
            // See prepareForStepping: the denominator belongs to the run, not to
            // the plan, which is rebuilt over the remainder on every resume.
            runPlannedMessages = built.totalMessages;
            runPlannedBytes = built.totalBytes;
        }
        plan = built;
        long anchor = Long.MAX_VALUE;
        for (ReplayPlan.Track t : built.tracks) {
            if (t.size > 0) {
                anchor = Math.min(anchor, t.offsetMillis[0]);
            }
        }
        clock = ReplayClock.startingAt(System.nanoTime(), anchor == Long.MAX_VALUE ? 0 : anchor, speed);
        state = PlaybackState.RUNNING;

        for (String note : built.excluded) {
            events.warn("playback", note);
        }
        events.info("playback", verb + ": " + built.totalMessages + " message(s), "
                + built.totalBytes + " bytes, spanning " + built.spanMillis + " ms of recorded time at "
                + speed + "x (" + mode + "); plan built in " + planBuildMillis + " ms");

        pacing = true;
        Thread thread = new Thread(this::pace, "dkm-pacer");
        thread.setDaemon(true);
        thread.setPriority(Thread.MAX_PRIORITY);
        pacer = thread;
        thread.start();
        publishState();
    }

    /**
     * Sends the next {@code count} messages and stops there (FR-11).
     *
     * <p>Some messages are small and cost the DKM minutes of work. Watching what
     * one of them does means sending exactly that one and then waiting as long
     * as it takes -- which a paced run cannot do, because the clock keeps moving
     * and the next message comes due whether the module is ready or not.
     *
     * <p>Stepping deliberately ignores the recorded timing: it sends the next
     * message due on whichever link is earliest, so cross-link order is still
     * the recording's order, but the gaps between them are the operator's to
     * decide. The run stays paused throughout, so everything still unsent stays
     * editable between steps.
     *
     * @return how many actually went out, which is fewer than asked when the
     *         set runs out or a link is down
     */
    public synchronized int step(int count, long fromMessageId) {
        if (state == PlaybackState.RUNNING) {
            throw new IllegalStateException("pause the run before stepping through it");
        }
        // A run that has not begun, or one being repositioned, starts over; one
        // already paused keeps its counters so the progress readout stays
        // cumulative across steps.
        boolean fresh = state == PlaybackState.IDLE || state == PlaybackState.FINISHED
                || fromMessageId > 0;
        if (!prepareForStepping(fromMessageId, fresh)) {
            return 0;
        }
        ReplayPlan p = plan;
        if (p == null) {
            return 0;
        }
        int sent = 0;
        for (int i = 0; i < Math.max(1, count); i++) {
            if (!stepOnce(p)) {
                break;
            }
            sent++;
        }
        if (sent > 0) {
            publishProgress(p);
            events.info("playback", "stepped " + sent + " message(s); " + sentCounter.get()
                    + " sent so far", "log.playback.stepped",
                    java.util.Map.of("count", sent, "total", sentCounter.get()));
        }
        if (p.complete()) {
            finish(p);
        } else {
            publishState();
        }
        return sent;
    }

    /**
     * Builds a plan over whatever is still unsent and parks the run at its
     * start, without a pacer thread.
     *
     * <p>Rebuilt on every step, exactly as {@link #resume()} rebuilds on every
     * resume. The plan is not state -- it is derived from the sent markers, and
     * holding one across a step meant an edit made between two steps, or a
     * different file loaded entirely, left it pointing at an arena that no
     * longer existed.
     *
     * @param fromMessageId step from this message on, or 0 to continue
     * @param fresh         reset the run's counters, because this is its start
     * @return whether there is anything to step onto
     */
    private boolean prepareForStepping(long fromMessageId, boolean fresh) {
        if (fresh) {
            session.resetSentMarkers(fromMessageId);
            sentCounter.set(0);
            sentBytes.set(0);
            lastVirtualMillis = 0;
            lastError = null;
            runStartedWallClock = System.currentTimeMillis();
            runFinishedWallClock = 0;
        }
        ReplayPlan built = buildPlan();
        if (built.totalMessages == 0) {
            plan = null;
            state = PlaybackState.FINISHED;
            runFinishedWallClock = System.currentTimeMillis();
            events.warn("playback", "nothing left to send");
            publishState();
            return false;
        }
        if (linkRegistry.links().stream().noneMatch(Link::isConnected)) {
            lastError = "no link is connected -- the DKM connects out once at its own startup and never retries";
            events.error("playback", lastError);
            publishState();
            throw new IllegalStateException(lastError);
        }
        if (fresh) {
            // The size of the whole run, fixed here so the progress readout keeps
            // one denominator: every re-plan sees only what is left, and reporting
            // that would make the total shrink as the operator advances.
            runPlannedMessages = built.totalMessages;
            runPlannedBytes = built.totalBytes;
        }
        plan = built;
        clock = ReplayClock.startingAt(System.nanoTime(), 0, speed);
        state = PlaybackState.PAUSED;
        lagMillis = 0;
        return true;
    }

    /**
     * Sends exactly one message: the next one due on whichever link is earliest
     * in the recording, so a step never reorders what a run would have sent.
     */
    private boolean stepOnce(ReplayPlan p) {
        ReplayPlan.Track chosen = null;
        for (ReplayPlan.Track track : p.tracks) {
            if (track.done()) {
                continue;
            }
            if (chosen == null || track.offsetMillis[track.cursor] < chosen.offsetMillis[chosen.cursor]) {
                chosen = track;
            }
        }
        if (chosen == null) {
            return false;
        }
        Link link = chosen.link;
        if (!link.isConnected()) {
            events.error(link.name(), "link is not connected -- nothing to step onto");
            return false;
        }
        if (link.writeQueueFull() && !link.awaitDrain(config.drainTimeoutMillis())) {
            return false;
        }

        int at = chosen.cursor;
        int length = chosen.lengths[at];
        ByteBuf slice = p.arena.retainedSlice(chosen.offsets[at], length);
        if (!link.write(slice, 1)) {
            return false;
        }

        MessageEntry entry = chosen.entries[at];
        entry.sent = true;
        entry.wallClock = System.currentTimeMillis();
        viz.onStimulus(link.index, link.moduleId(), entry.msgId,
                p.arena, chosen.offsets[at], length);
        chosen.cursor = at + 1;
        sentBytes.addAndGet(length);
        sentCounter.addAndGet(1);
        return true;
    }

    public synchronized void pause() {
        if (state != PlaybackState.RUNNING) {
            return;
        }
        stopPacer();
        state = PlaybackState.PAUSED;
        events.info("playback", "paused at " + sentCounter.get() + " message(s) sent"
                + " -- connections stay open and pending messages are editable");
        publishState();
    }

    /**
     * FR-11. The open question in the requirements is whether stop rewinds or
     * aborts in place; rather than guess, both are here and the caller chooses.
     * The UI's Stop button rewinds, because that is the one that makes the run
     * fully re-editable and re-runnable, which is what this tool is for.
     */
    public synchronized void stop(boolean rewind) {
        stopPacer();
        plan = null;
        if (rewind) {
            session.resetSentMarkers();
            sentCounter.set(0);
            sentBytes.set(0);
            lastVirtualMillis = 0;
            state = PlaybackState.IDLE;
            events.info("playback", "stopped and rewound to the start -- the whole set is editable again",
                    "log.playback.stoppedRewound", Map.of());
        } else {
            state = PlaybackState.FINISHED;
            runFinishedWallClock = System.currentTimeMillis();
            events.info("playback", "stopped in place after " + sentCounter.get()
                    + " message(s) -- already-sent messages stay as history");
        }
        publishState();
    }

    /** FR-12: live speed change, without the replay position jumping. */
    public synchronized void setSpeed(double newSpeed) {
        if (newSpeed <= 0 || Double.isNaN(newSpeed) || newSpeed > 10_000) {
            throw new IllegalArgumentException("speed must be a positive multiplier below 10000");
        }
        this.speed = newSpeed;
        if (state == PlaybackState.RUNNING) {
            clock = clock.reanchor(System.nanoTime(), newSpeed);
            LockSupport.unpark(pacer);
        }
        events.info("playback", "speed set to " + newSpeed + "x",
                "log.playback.speed", Map.of("speed", newSpeed));
        publishState();
    }

    public synchronized void setMode(PaceMode newMode) {
        this.mode = newMode;
        if (state == PlaybackState.RUNNING) {
            clock = clock.reanchor(System.nanoTime(), speed);
            LockSupport.unpark(pacer);
        }
        events.info("playback", "pacing mode set to " + newMode,
                "log.playback.mode", Map.of("mode", newMode.name()));
        publishState();
    }

    private void stopPacer() {
        pacing = false;
        Thread thread = pacer;
        pacer = null;
        if (thread != null) {
            LockSupport.unpark(thread);
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ---- plan construction ----------------------------------------------

    private ReplayPlan buildPlan() {
        session.lock().lock();
        try {
            MessageSet set = session.messages();
            set.compact();

            long epoch = Long.MAX_VALUE;
            long latest = Long.MIN_VALUE;
            for (MessageEntry e : set.entries()) {
                epoch = Math.min(epoch, e.timestamp);
                latest = Math.max(latest, e.timestamp);
            }
            if (epoch == Long.MAX_VALUE) {
                epoch = 0;
                latest = 0;
            }

            List<String> excluded = new ArrayList<>();
            int unsendable = 0;
            List<ReplayPlan.Track> tracks = new ArrayList<>();
            int total = 0;
            long totalBytes = 0;

            for (Link link : linkRegistry.links()) {
                List<MessageEntry> pending = new ArrayList<>();
                for (MessageEntry e : set.entries()) {
                    if (e.moduleId != link.moduleId()) {
                        continue;
                    }
                    if (e.sent) {
                        continue;
                    }
                    if (e.problem != null) {
                        unsendable++;
                        continue;
                    }
                    pending.add(e);
                }
                // Stable sort: a file whose messages are not in timestamp order
                // still replays monotonically, and equal timestamps keep file order.
                pending.sort(Comparator.comparingLong(e -> e.timestamp));

                int n = pending.size();
                int[] offsets = new int[n];
                int[] lengths = new int[n];
                long[] offsetMillis = new long[n];
                MessageEntry[] entries = new MessageEntry[n];
                for (int i = 0; i < n; i++) {
                    MessageEntry e = pending.get(i);
                    offsets[i] = e.offset;
                    lengths[i] = e.length;
                    offsetMillis[i] = e.timestamp - epoch;
                    entries[i] = e;
                    totalBytes += e.length;
                }
                total += n;
                tracks.add(new ReplayPlan.Track(link, offsets, lengths, offsetMillis, entries));
            }

            if (unsendable > 0) {
                excluded.add(unsendable + " message(s) excluded from this run because they cannot be trusted: "
                        + "an unknown msg_id, or a length that disagrees with the schema. "
                        + "They are still in the list, still saved byte-exact, just not sent.");
            }
            for (MessageEntry e : set.entries()) {
                if (e.problem != null && excluded.size() < 12) {
                    excluded.add("#" + e.id + ": " + e.problem);
                }
            }

            return new ReplayPlan(set.arena(), tracks, epoch, Math.max(0, latest - epoch),
                    total, totalBytes, excluded);
        } finally {
            session.lock().unlock();
        }
    }

    // ---- the pacer -------------------------------------------------------

    private void pace() {
        ReplayPlan p = plan;
        long lastProgress = System.currentTimeMillis();
        try {
            while (pacing) {
                ReplayClock c = clock;
                long now = System.nanoTime();
                double virtualNow = mode == PaceMode.MAX_RATE
                        ? Double.POSITIVE_INFINITY
                        : c.virtualMillisAt(now);
                if (mode == PaceMode.TIMESTAMP) {
                    lastVirtualMillis = virtualNow;
                }

                long earliestDeadline = Long.MAX_VALUE;
                boolean progressed = false;
                double overdue = 0;

                for (ReplayPlan.Track track : p.tracks) {
                    if (track.done()) {
                        continue;
                    }
                    while (pacing && !track.done() && track.offsetMillis[track.cursor] <= virtualNow) {
                        int sent = flush(p, track, virtualNow);
                        if (sent <= 0) {
                            break;
                        }
                        progressed = true;
                    }
                    if (!track.done()) {
                        earliestDeadline = Math.min(earliestDeadline,
                                c.deadlineNanos(track.offsetMillis[track.cursor]));
                        if (mode == PaceMode.TIMESTAMP) {
                            overdue = Math.max(overdue, virtualNow - track.offsetMillis[track.cursor]);
                        }
                    }
                }
                lagMillis = Math.max(0, overdue);

                long wallNow = System.currentTimeMillis();
                if (progressed && wallNow - lastProgress >= config.progressIntervalMillis()) {
                    lastProgress = wallNow;
                    publishProgress(p);
                }

                // Completion is checked *after* the pass, not before it. Checking
                // first would miss the pass that sends the last message: every
                // track finishes, no deadline is left to wait for, and the pacer
                // would park indefinitely in a run that is actually over.
                if (p.complete()) {
                    finish(p);
                    return;
                }
                if (mode == PaceMode.MAX_RATE) {
                    if (!progressed) {
                        LockSupport.parkNanos(200_000L);
                    }
                    continue;
                }
                waitUntil(earliestDeadline);
            }
        } catch (RuntimeException e) {
            lastError = e.toString();
            LOG.error("pacer failed", e);
            events.error("playback", "replay aborted -- " + e);
            state = PlaybackState.PAUSED;
            publishState();
        }
    }

    /**
     * Sends as many due, arena-adjacent messages as fit in one write.
     *
     * @return number of messages written, 0 if backpressure blocked the write,
     *         -1 if the link is gone and the messages were abandoned
     */
    private int flush(ReplayPlan p, ReplayPlan.Track track, double virtualNow) {
        Link link = track.link;
        if (!link.isConnected()) {
            int abandoned = 0;
            while (!track.done() && track.offsetMillis[track.cursor] <= virtualNow) {
                track.cursor++;
                track.skipped++;
                abandoned++;
            }
            if (abandoned > 0 && track.skipped == abandoned) {
                events.error(link.name(), "link is not connected -- messages that come due are being abandoned; "
                        + "restart the DKM and the run to send them");
            }
            return -1;
        }

        if (link.writeQueueFull() && !link.awaitDrain(config.drainTimeoutMillis())) {
            return 0;
        }

        int start = track.cursor;
        int end = start;
        int bytes = 0;
        int expected = track.offsets[start];
        int maxBytes = config.maxBatchBytes();
        int maxMessages = config.maxBatchMessages();

        while (end < track.size
                && track.offsetMillis[end] <= virtualNow
                && track.offsets[end] == expected
                && bytes + track.lengths[end] <= maxBytes
                && (end - start) < maxMessages) {
            bytes += track.lengths[end];
            expected += track.lengths[end];
            end++;
        }
        if (end == start) {
            // A single message larger than the batch cap: send it on its own.
            bytes = track.lengths[start];
            end = start + 1;
        }

        ByteBuf slice = p.arena.retainedSlice(track.offsets[start], bytes);
        int count = end - start;
        if (!link.write(slice, count)) {
            return 0;
        }

        long wallClock = System.currentTimeMillis();
        long moduleId = link.moduleId();
        boolean sampling = true;
        for (int i = start; i < end; i++) {
            MessageEntry entry = track.entries[i];
            entry.sent = true;
            entry.wallClock = wallClock;
            // Stimulus is drawn the same way output is (FR-28), but on a budget:
            // past it the picture is thinned rather than the pacer slowed down.
            if (sampling) {
                sampling = viz.onStimulus(link.index, moduleId, entry.msgId,
                        p.arena, track.offsets[i], track.lengths[i]);
            }
        }
        track.cursor = end;
        sentBytes.addAndGet(bytes);
        sentCounter.addAndGet(count); // publishes the plain writes above
        return count;
    }

    /** Longest single park, so a stop or a speed change is noticed promptly. */
    private static final long MAX_PARK_NANOS = 50_000_000L;

    private void waitUntil(long deadlineNanos) {
        long spinThreshold = config.spinThresholdNanos();
        while (pacing) {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                return;
            }
            remaining = Math.min(remaining, MAX_PARK_NANOS);
            if (remaining > spinThreshold) {
                // Wake a little early and spin the rest: park granularity on a
                // general-purpose OS is around a millisecond, which would smear
                // millisecond-spaced message timing.
                LockSupport.parkNanos(remaining - spinThreshold);
                return;
            }
            Thread.onSpinWait();
        }
    }

    private void finish(ReplayPlan p) {
        state = PlaybackState.FINISHED;
        runFinishedWallClock = System.currentTimeMillis();
        lastVirtualMillis = Math.max(lastVirtualMillis, p.spanMillis);
        long skipped = p.tracks.stream().mapToLong(t -> t.skipped).sum();
        events.info("playback", "replay finished: " + sentCounter.get() + " message(s), "
                + sentBytes.get() + " bytes"
                + (skipped > 0 ? ", " + skipped + " abandoned on disconnected links" : ""));
        publishProgress(p);
        publishState();
    }

    // ---- state for the UI -------------------------------------------------

    public PlaybackState state() {
        return state;
    }

    public double speed() {
        return speed;
    }

    public PaceMode mode() {
        return mode;
    }

    public long sentCount() {
        return sentCounter.get();
    }

    public long sentBytes() {
        return sentBytes.get();
    }

    /** Recorded-timeline milliseconds the pacer is currently behind schedule. */
    public double lagMillis() {
        return state == PlaybackState.RUNNING ? lagMillis : 0;
    }

    /** Messages still to be sent across every link. */
    public long remaining() {
        ReplayPlan p = plan;
        if (p == null) {
            return 0;
        }
        long remaining = 0;
        for (ReplayPlan.Track track : p.tracks) {
            remaining += track.remaining();
        }
        return remaining;
    }

    public ObjectNode snapshot() {
        ObjectNode node = NODES.objectNode();
        ReplayPlan p = plan;
        node.put("state", state.name());
        node.put("speed", speed);
        node.put("mode", mode.name());
        node.put("sent", sentCounter.get());
        node.put("sentBytes", sentBytes.get());
        node.put("error", lastError);
        node.put("lagMillis", lagMillis());
        node.put("planBuildMillis", planBuildMillis);
        node.put("startedAt", runStartedWallClock);
        node.put("finishedAt", runFinishedWallClock);
        if (p != null) {
            node.put("planned", runPlannedMessages);
            node.put("plannedBytes", runPlannedBytes);
            node.put("spanMillis", p.spanMillis);
            node.put("epochMillis", p.epochMillis);
            node.put("virtualMillis", state == PlaybackState.RUNNING
                    ? clock.virtualMillisAt(System.nanoTime())
                    : lastVirtualMillis);
            ArrayNode tracks = node.putArray("tracks");
            for (ReplayPlan.Track track : p.tracks) {
                ObjectNode t = tracks.addObject();
                t.put("link", track.link.name());
                t.put("planned", track.size);
                t.put("sent", track.cursor);
                t.put("skipped", track.skipped);
            }
        } else {
            node.put("planned", 0);
            node.put("plannedBytes", 0);
            node.put("spanMillis", 0);
            node.put("virtualMillis", 0.0);
            node.putArray("tracks");
        }
        return node;
    }

    private void publishState() {
        ObjectNode event = events.newEvent("playback");
        event.set("data", snapshot());
        events.publish(event);
    }

    private void publishProgress(ReplayPlan p) {
        events.publish("playbackProgress", data -> {
            data.put("sent", sentCounter.get());
            data.put("sentBytes", sentBytes.get());
            data.put("planned", runPlannedMessages);
            data.put("virtualMillis", clock.virtualMillisAt(System.nanoTime()));
            data.put("lagMillis", lagMillis());
            ArrayNode tracks = data.putArray("tracks");
            for (ReplayPlan.Track track : p.tracks) {
                ObjectNode t = tracks.addObject();
                t.put("link", track.link.name());
                t.put("sent", track.cursor);
                t.put("planned", track.size);
                t.put("skipped", track.skipped);
            }
        });
    }
}
