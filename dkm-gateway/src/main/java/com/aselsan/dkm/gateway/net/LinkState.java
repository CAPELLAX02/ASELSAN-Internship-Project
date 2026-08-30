package com.aselsan.dkm.gateway.net;

/** Lifecycle of one peer link, as surfaced to the operator (FR-15). */
public enum LinkState {
    /** Port not yet bound. */
    DOWN,
    /** Bound and waiting. The DKM connects out exactly once and never retries, so this must be reached before it starts (FR-17). */
    LISTENING,
    /** The DKM has connected. */
    CONNECTED,
    /** The DKM connected and then went away. */
    CLOSED,
    /** Bind failed, or the stream desynchronised. */
    FAILED
}
