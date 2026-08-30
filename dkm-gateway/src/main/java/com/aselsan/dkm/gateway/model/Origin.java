package com.aselsan.dkm.gateway.model;

/** Where a message in the working set came from. Purely for the UI. */
public enum Origin {
    /** Parsed out of a loaded binary file (FR-6). */
    FILE,
    /** Built from scratch in the UI (FR-9). */
    NEW,
    /** Pulled from the message library (FR-23). */
    LIBRARY,
    /** Received from the DKM (FR-19). */
    CAPTURE
}
