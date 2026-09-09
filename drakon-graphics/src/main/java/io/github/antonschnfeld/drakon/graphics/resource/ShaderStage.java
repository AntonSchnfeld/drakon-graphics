package io.github.antonschnfeld.drakon.graphics.resource;

/** Shader execution stages exposed by the current graphics API. */
public enum ShaderStage {
    /** Vertex-processing stage. */
    VERTEX,
    /** Fragment/pixel-processing stage. */
    FRAGMENT,
    /** Compute stage. */
    COMPUTE
}
