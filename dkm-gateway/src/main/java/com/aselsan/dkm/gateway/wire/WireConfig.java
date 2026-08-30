package com.aselsan.dkm.gateway.wire;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Target data model. These are properties of the machine the DKM runs on, not
 * of the message definitions, which is why they live in config rather than in
 * the schema file.
 */
@ConfigMapping(prefix = "dkm.wire")
public interface WireConfig {

    /** {@code sizeof(std::size_t)} on the target. 8 for the current 64-bit DKM. */
    @WithDefault("8")
    int sizeTBytes();

    /** LITTLE_ENDIAN or BIG_ENDIAN. */
    @WithDefault("LITTLE_ENDIAN")
    String byteOrder();

    default boolean littleEndian() {
        return !"BIG_ENDIAN".equalsIgnoreCase(byteOrder());
    }
}
