package com.aselsan.dkm.gateway.schema;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The whole compiled interface: modules, the shared header layout, and every
 * message type indexed the two ways the runtime actually looks them up.
 *
 * <p>The decode key is <em>(module, msg_id)</em>, not msg_id alone -- msg_id is
 * only unique within a link (RSM's BeamReport and CRM's Prediction are both
 * msg_id 1), which is exactly how each {@code xxx_comm.cpp}'s {@code dispatch()}
 * resolves it on the DKM side.
 */
public final class SchemaModel {

    private final String version;
    private final String hash;
    private final int sizeTBytes;
    private final boolean littleEndian;
    private final CompiledStruct header;
    private final int senderIdOffset;
    private final int receiverIdOffset;
    private final int msgIdOffset;
    private final int timestampOffset;
    private final int msgLengthOffset;

    private final List<ModuleDef> modules;
    private final ModuleDef dkmModule;
    private final Map<String, ModuleDef> modulesByName;
    private final Map<Long, ModuleDef> modulesById;

    private final Map<String, CompiledStruct> structs;
    private final List<CompiledMessage> messages;
    private final Map<String, CompiledMessage> messagesByQualifiedName;
    /** (moduleId << 32) | msgId -> message. Long key keeps the hot lookup allocation-free. */
    private final Map<Long, CompiledMessage> messagesByModuleAndId;
    private final Map<String, Integer> constants;

    SchemaModel(String version, String hash, int sizeTBytes, boolean littleEndian,
                CompiledStruct header, List<ModuleDef> modules,
                Map<String, CompiledStruct> structs, List<CompiledMessage> messages,
                Map<String, Integer> constants) {
        this.version = version;
        this.hash = hash;
        this.sizeTBytes = sizeTBytes;
        this.littleEndian = littleEndian;
        this.header = header;
        this.senderIdOffset = header.requireField("sender_id").offset;
        this.receiverIdOffset = header.requireField("receiver_id").offset;
        this.msgIdOffset = header.requireField("msg_id").offset;
        this.timestampOffset = header.requireField("timestamp").offset;
        this.msgLengthOffset = header.requireField("msg_length").offset;

        this.modules = List.copyOf(modules);
        this.structs = Map.copyOf(structs);
        this.messages = List.copyOf(messages);
        this.constants = Map.copyOf(constants);

        Map<String, ModuleDef> byName = new LinkedHashMap<>();
        Map<Long, ModuleDef> byId = new LinkedHashMap<>();
        ModuleDef dkm = null;
        for (ModuleDef m : modules) {
            byName.put(m.name(), m);
            byId.put(m.id(), m);
            if (m.dkm()) {
                if (dkm != null) {
                    throw new SchemaException("more than one module declares role DKM");
                }
                dkm = m;
            }
        }
        if (dkm == null) {
            throw new SchemaException("no module declares role DKM");
        }
        this.modulesByName = Map.copyOf(byName);
        this.modulesById = Map.copyOf(byId);
        this.dkmModule = dkm;

        Map<String, CompiledMessage> byQName = new LinkedHashMap<>();
        Map<Long, CompiledMessage> byKey = new LinkedHashMap<>();
        for (CompiledMessage m : messages) {
            if (byQName.put(m.qualifiedName, m) != null) {
                throw new SchemaException("duplicate message type " + m.qualifiedName);
            }
            long key = key(m.module.id(), m.msgId);
            CompiledMessage clash = byKey.put(key, m);
            if (clash != null) {
                throw new SchemaException("msg_id " + m.msgId + " is used by both "
                        + clash.qualifiedName + " and " + m.qualifiedName
                        + " on the same link -- msg_id must be unique per link");
            }
        }
        this.messagesByQualifiedName = Map.copyOf(byQName);
        this.messagesByModuleAndId = Map.copyOf(byKey);
    }

    private static long key(long moduleId, long msgId) {
        return (moduleId << 32) ^ (msgId & 0xFFFF_FFFFL);
    }

    public String version() { return version; }

    /** SHA-256 of the schema source, used to flag stale library entries (FR-24). */
    public String hash() { return hash; }

    public int sizeTBytes() { return sizeTBytes; }

    public boolean littleEndian() { return littleEndian; }

    public CompiledStruct header() { return header; }

    public int headerSize() { return header.size; }

    public int senderIdOffset() { return senderIdOffset; }
    public int receiverIdOffset() { return receiverIdOffset; }
    public int msgIdOffset() { return msgIdOffset; }
    public int timestampOffset() { return timestampOffset; }
    public int msgLengthOffset() { return msgLengthOffset; }

    public List<ModuleDef> modules() { return modules; }

    public ModuleDef dkmModule() { return dkmModule; }

    public List<ModuleDef> peerModules() {
        List<ModuleDef> peers = new ArrayList<>(modules.size());
        for (ModuleDef m : modules) {
            if (!m.dkm()) {
                peers.add(m);
            }
        }
        return peers;
    }

    public ModuleDef moduleByName(String name) { return modulesByName.get(name); }

    public ModuleDef moduleById(long id) { return modulesById.get(id); }

    public Collection<CompiledStruct> structs() { return structs.values(); }

    public List<CompiledMessage> messages() { return messages; }

    public CompiledMessage message(String qualifiedName) {
        return messagesByQualifiedName.get(qualifiedName);
    }

    /** The decode lookup. Returns null for an msg_id this link doesn't define. */
    public CompiledMessage message(long moduleId, long msgId) {
        return messagesByModuleAndId.get(key(moduleId, msgId));
    }

    public Map<String, Integer> constants() { return constants; }

    /**
     * Which peer link a message belongs to, from its header alone.
     *
     * <p>Stimulus messages carry {@code sender_id = <peer>}; captured output
     * carries {@code sender_id = RDP, receiver_id = <peer>}. Both directions of
     * a link therefore resolve to the same peer, which is what makes one code
     * path able to load both {@code input.bin} and {@code output.bin}.
     */
    public ModuleDef resolvePeer(long senderId, long receiverId) {
        if (senderId == dkmModule.id()) {
            ModuleDef m = modulesById.get(receiverId);
            return (m != null && !m.dkm()) ? m : null;
        }
        ModuleDef m = modulesById.get(senderId);
        return (m != null && !m.dkm()) ? m : null;
    }
}
