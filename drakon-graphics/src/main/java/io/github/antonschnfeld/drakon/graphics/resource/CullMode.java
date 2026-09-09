package io.github.antonschnfeld.drakon.graphics.resource;

/** Triangle-face culling mode. Counter-clockwise winding is considered front-facing. */
public enum CullMode {
    /** Do not cull either face orientation. */
    NONE,
    /** Cull front-facing primitives. */
    FRONT,
    /** Cull back-facing primitives. */
    BACK
}
