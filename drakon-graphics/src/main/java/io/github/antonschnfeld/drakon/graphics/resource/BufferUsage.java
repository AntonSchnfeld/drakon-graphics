package io.github.antonschnfeld.drakon.graphics.resource;

/** Declares the ways a {@link Buffer} may be used during its lifetime. */
public enum BufferUsage {
    /** Buffer may be bound as vertex or per-instance input. */
    VERTEX,
    /** Buffer may be bound as index input. */
    INDEX,
    /** Buffer may be read as a uniform/constant buffer by shaders. */
    UNIFORM
}
