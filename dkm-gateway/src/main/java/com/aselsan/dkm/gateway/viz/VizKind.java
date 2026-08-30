package com.aselsan.dkm.gateway.viz;

/**
 * The visual vocabulary from FR-25. Owned entirely by the simulator: neither
 * the DKM nor its interface headers carry any of this, which is why it lives in
 * its own config file rather than in the interface schema.
 */
public enum VizKind {
    /** Not drawn. */
    NONE,
    /** A single position. */
    POINT,
    /** A position that joins a connected track by correlation id (G9/FR-27). */
    TRACK,
    /** A bearing from the origin -- a beam, or a jammer strobe with no range. */
    RAY,
    /** A segment between two positions. */
    LINE,
    /** A polar annulus sector: a distance band crossed with a heading band. */
    CIRCULAR_AREA,
    /** An axis-aligned rectangle in Cartesian metres. */
    RECT_AREA;

    public int code() {
        return ordinal();
    }
}
