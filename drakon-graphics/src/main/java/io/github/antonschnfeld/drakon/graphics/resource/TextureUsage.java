package io.github.antonschnfeld.drakon.graphics.resource;

/** Declares the ways a {@link Texture} may be used during its lifetime. */
public enum TextureUsage {
    /** Texture may be attached as a color render target. */
    COLOR_ATTACHMENT,
    /** Texture may be attached as a depth render target. */
    DEPTH_ATTACHMENT,
    /** Texture may be sampled by shaders. */
    SAMPLED,
    /** Texture may be the source of a copy operation. */
    COPY_SRC,
    /** Texture may be the destination of a copy operation. */
    COPY_DST
}
