package com.aselsan.dkm.gateway.playback;

import com.aselsan.dkm.gateway.model.MessageArena;
import com.aselsan.dkm.gateway.model.MessageEntry;
import com.aselsan.dkm.gateway.net.Link;

import java.util.List;

/**
 * An immutable snapshot of what is still to be sent, with every message already
 * reduced to (arena offset, length, when).
 *
 * <p>Built fresh on every start and every resume. That is not laziness about
 * caching -- it is FR-14: pending messages are editable while paused, so the
 * only correct thing to hold across a pause is the recorded timeline, never a
 * precomputed set of absolute send times.
 */
public final class ReplayPlan {

    /** One link's share of the plan, in timestamp order. */
    public static final class Track {
        public final Link link;
        public final int[] offsets;
        public final int[] lengths;
        /** Milliseconds from the run's earliest recorded message. */
        public final long[] offsetMillis;
        public final MessageEntry[] entries;
        public final int size;

        /** Pacer-owned; not touched from any other thread while RUNNING. */
        public int cursor;
        /** Messages abandoned because the link was not connected when they came due. */
        public int skipped;

        Track(Link link, int[] offsets, int[] lengths, long[] offsetMillis, MessageEntry[] entries) {
            this.link = link;
            this.offsets = offsets;
            this.lengths = lengths;
            this.offsetMillis = offsetMillis;
            this.entries = entries;
            this.size = offsets.length;
        }

        public boolean done() {
            return cursor >= size;
        }

        public long remaining() {
            return size - cursor;
        }
    }

    public final MessageArena arena;
    public final List<Track> tracks;
    public final long epochMillis;
    public final long spanMillis;
    public final int totalMessages;
    public final long totalBytes;
    public final List<String> excluded;

    public ReplayPlan(MessageArena arena, List<Track> tracks, long epochMillis, long spanMillis,
                      int totalMessages, long totalBytes, List<String> excluded) {
        this.arena = arena;
        this.tracks = List.copyOf(tracks);
        this.epochMillis = epochMillis;
        this.spanMillis = spanMillis;
        this.totalMessages = totalMessages;
        this.totalBytes = totalBytes;
        this.excluded = List.copyOf(excluded);
    }

    public int sentCount() {
        int sent = 0;
        for (Track t : tracks) {
            sent += t.cursor;
        }
        return sent;
    }

    public boolean complete() {
        for (Track t : tracks) {
            if (!t.done()) {
                return false;
            }
        }
        return true;
    }
}
