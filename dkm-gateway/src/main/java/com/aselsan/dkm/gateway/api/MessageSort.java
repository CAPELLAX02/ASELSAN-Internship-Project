package com.aselsan.dkm.gateway.api;

import com.aselsan.dkm.gateway.model.MessageEntry;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Sorting for the message lists (FR-29).
 *
 * <p>Done here rather than in the browser because the browser only ever holds
 * one page: sorting client-side would sort the page, not the list, which is the
 * kind of control that looks like it works until the list is long enough to
 * matter.
 */
public enum MessageSort {

    /** List order -- for stimulus that is file order, for capture it is arrival order. */
    SEQUENCE,
    /** The recorded header timestamp that drives the replay clock. */
    TIMESTAMP,
    /** Qualified type name; unknown types sort last. */
    TYPE,
    LINK,
    LENGTH,
    /** When the message actually crossed the wire. */
    WALL_CLOCK;

    public static MessageSort parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return SEQUENCE;
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "timestamp", "time" -> TIMESTAMP;
            case "type" -> TYPE;
            case "link" -> LINK;
            case "length", "size" -> LENGTH;
            case "wallclock", "wall", "sent", "received" -> WALL_CLOCK;
            default -> SEQUENCE;
        };
    }

    /**
     * Sorts in place. SEQUENCE is left untouched rather than sorted by id, so
     * "no sort" costs nothing on a list of millions.
     */
    public void apply(List<MessageEntry> entries, boolean descending) {
        if (this == SEQUENCE && !descending) {
            return;
        }
        Comparator<MessageEntry> comparator = switch (this) {
            case SEQUENCE -> Comparator.comparingLong(e -> e.id);
            case TIMESTAMP -> Comparator.comparingLong(e -> e.timestamp);
            case LENGTH -> Comparator.comparingInt(e -> e.length);
            case WALL_CLOCK -> Comparator.comparingLong(e -> e.wallClock);
            case LINK -> Comparator.comparingLong(e -> e.moduleId);
            case TYPE -> Comparator.comparing(e -> e.typeName == null ? "￿" : e.typeName);
        };
        // Ties keep list order, so a sort by type still reads chronologically
        // within each type rather than shuffling.
        comparator = comparator.thenComparingLong(e -> e.id);
        entries.sort(descending ? comparator.reversed() : comparator);
    }
}
