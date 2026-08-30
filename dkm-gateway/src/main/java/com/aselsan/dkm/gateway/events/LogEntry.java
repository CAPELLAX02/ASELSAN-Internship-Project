package com.aselsan.dkm.gateway.events;

/**
 * One line of the session log (FR-32).
 *
 * @param seq       monotonic, so the UI can detect a gap after a reconnect
 * @param wallClock epoch millis
 * @param level     INFO / WARN / ERROR
 * @param source    which subsystem emitted it (link name, "playback", "schema", ...)
 * @param message   human-readable text
 */
public record LogEntry(long seq, long wallClock, String level, String source, String message) {
}
