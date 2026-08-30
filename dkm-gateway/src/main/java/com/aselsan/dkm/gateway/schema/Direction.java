package com.aselsan.dkm.gateway.schema;

/**
 * Which way a message type normally travels. Purely descriptive: decoding keys
 * off (link, msg_id) and works identically in both directions, but the UI needs
 * to know which types belong in the editable stimulus list and which are
 * read-only capture (FR-31).
 */
public enum Direction {
    /** Simulator -> DKM. Editable stimulus. */
    TO_DKM,
    /** DKM -> simulator. Read-only capture. */
    FROM_DKM,
    /** Legitimately seen in both directions. */
    BIDIRECTIONAL;

}
