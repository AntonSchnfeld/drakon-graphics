package io.github.antonschnfeld.drakon.graphics.resource;

import java.util.Set;

/** Opaque GPU buffer resource. */
public interface Buffer extends GpuResource {
    /**
     * Returns the allocated size in bytes.
     *
     * @return positive buffer size in bytes
     */
    long size();

    /**
     * Returns the immutable usage set declared when the buffer was created.
     *
     * @return declared buffer usages
     */
    Set<BufferUsage> usage();
}
