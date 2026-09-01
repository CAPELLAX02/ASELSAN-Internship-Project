package com.aselsan.dkm.gateway.session;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Undo and redo for the stimulus set.
 *
 * <p>A step is a splice: the bytes that occupied one stretch of the list before
 * an edit, and the bytes that occupy it after. Undo puts the first set back,
 * redo puts the second. Recorded that way rather than as a log of inverse
 * commands because every operation here -- editing a field, inserting, deleting,
 * reordering, re-timing -- is a splice of some stretch, and one shape that
 * covers all of them cannot drift out of step with any of them.
 *
 * <p>The stretch is only as long as the operation touched, so editing one field
 * of one message costs one message's bytes. A reorder costs the range it moved
 * across. Nothing here scales with the length of the list.
 *
 * <p>Bounded on purpose: an operator who wants to get back to where a file
 * started has Revert for that, which is exact and costs nothing to keep.
 */
final class EditHistory {

    /** How many steps to keep. Deep enough for a session of edits, shallow enough to stay small. */
    private static final int LIMIT = 64;

    /** One message as it stood: its identity and its exact bytes. */
    record Entry(long id, byte[] bytes) { }

    /**
     * @param label     what the operator did, for the button's tooltip
     * @param at        index the stretch starts at
     * @param before    the messages that were there
     * @param after     the messages that are there now
     */
    record Step(String label, int at, List<Entry> before, List<Entry> after) {
        Step inverted() {
            return new Step(label, at, after, before);
        }
    }

    private final Deque<Step> undo = new ArrayDeque<>();
    private final Deque<Step> redo = new ArrayDeque<>();

    void record(Step step) {
        // A step that changed nothing would make the undo button lie about
        // there being something to undo.
        if (step.before().isEmpty() && step.after().isEmpty()) {
            return;
        }
        undo.push(step);
        while (undo.size() > LIMIT) {
            undo.removeLast();
        }
        // A new edit makes any redo branch unreachable, as it does everywhere.
        redo.clear();
    }

    boolean canUndo() {
        return !undo.isEmpty();
    }

    boolean canRedo() {
        return !redo.isEmpty();
    }

    String undoLabel() {
        return undo.isEmpty() ? null : undo.peek().label();
    }

    String redoLabel() {
        return redo.isEmpty() ? null : redo.peek().label();
    }

    /** Pops the next step to undo, and files its mirror for redo. */
    Step popUndo() {
        Step step = undo.poll();
        if (step != null) {
            redo.push(step);
        }
        return step;
    }

    /** Pops the next step to redo, and files it back for undo. */
    Step popRedo() {
        Step step = redo.poll();
        if (step != null) {
            undo.push(step);
        }
        return step;
    }

    void clear() {
        undo.clear();
        redo.clear();
    }

    /** Snapshots a stretch of the list, for use as either side of a step. */
    static List<Entry> snapshot(List<Entry> entries) {
        return new ArrayList<>(entries);
    }
}
