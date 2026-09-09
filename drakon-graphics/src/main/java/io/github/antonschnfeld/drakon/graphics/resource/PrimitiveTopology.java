package io.github.antonschnfeld.drakon.graphics.resource;

/** Primitive assembly topology for graphics draws. */
public enum PrimitiveTopology {
    /** Every three vertices/indices form an independent triangle. */
    TRIANGLES,
    /** Every two vertices/indices form an independent line. */
    LINES,
    /** Every vertex/index forms one point primitive. */
    POINTS
}
