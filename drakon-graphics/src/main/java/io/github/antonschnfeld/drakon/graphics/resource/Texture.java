package io.github.antonschnfeld.drakon.graphics.resource;

import java.util.Set;

/** Opaque two-dimensional GPU texture resource. */
public interface Texture extends GpuResource {
    /** Returns the texture width.
     * @return texture width in texels */
    int width();

    /** Returns the texture height.
     * @return texture height in texels */
    int height();

    /** Returns the texture format.
     * @return immutable texture format */
    TextureFormat format();

    /** Returns the usages declared at creation.
     * @return immutable usage set declared at creation */
    Set<TextureUsage> usage();
}
