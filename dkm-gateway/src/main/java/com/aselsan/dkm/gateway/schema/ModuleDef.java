package com.aselsan.dkm.gateway.schema;

/**
 * A module on the bus, as identified by the well-known {@code ModuleId} that
 * appears in every {@code MsgHeader}.
 *
 * @param name        wire name (RSP, RSM, CRM, RDP)
 * @param id          ModuleId value used in sender_id / receiver_id
 * @param dkm         true for the module under test itself, which is the peer
 *                    at the far end of every link rather than a link of its own
 * @param defaultPort TCP port this peer's link listens on; -1 for the DKM
 * @param description free text for the UI
 */
public record ModuleDef(String name, long id, boolean dkm, int defaultPort, String description) {
}
