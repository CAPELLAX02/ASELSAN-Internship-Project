package com.aselsan.dkm.gateway.viz;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Most-recent heading per beam id.
 *
 * <p>A {@code JammerReport} carries no bearing of its own -- it names a beam.
 * Drawing it therefore needs the same lookup the DKM itself does against its
 * {@code BeamReportBuffer}, so this mirrors that: a beam id with no announced
 * heading yields nothing, which is exactly what mock_r does with a detection
 * whose beam it has never heard of. Inventing a bearing would show the operator
 * something the DKM does not believe.
 */
public final class BeamHeadingIndex {

    private final ConcurrentHashMap<Long, Double> headings = new ConcurrentHashMap<>();

    public void put(long beamId, double heading) {
        headings.put(beamId, heading);
    }

    /** Returns NaN when the beam has not been announced. */
    public double get(long beamId) {
        Double value = headings.get(beamId);
        return value == null ? Double.NaN : value;
    }

    public int size() {
        return headings.size();
    }

    public void clear() {
        headings.clear();
    }
}
