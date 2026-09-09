package io.github.antonschnfeld.drakon.graphics.resource;

/** Kind of resource represented by a {@link Binding}. */
public enum BindingType {
    /** Read-only uniform/constant-buffer range. */
    UNIFORM_BUFFER,
    /** Shader-readable and/or writable storage-buffer range. */
    STORAGE_BUFFER,
    /** Texture together with the sampler state used to sample it. */
    SAMPLED_TEXTURE
}
