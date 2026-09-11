package io.github.antonschnfeld.drakon.graphics.resource;

/**
 * Portable resource access states used to express synchronization dependencies.
 *
 * <p>States describe the next class of GPU access, not a backend-native image
 * layout or barrier mask. A backend maps each transition onto the synchronization
 * primitives required by its graphics API.</p>
 *
 * <p>These states apply only to application-visible {@link Texture} and
 * {@link Buffer} resources. Presentation images hidden behind a
 * {@link RenderTarget} are backend-owned and intentionally do not expose a
 * public presentation state.</p>
 */
public enum ResourceState {
    /** Initial/discard state with no preserved contents or prior access dependency. */
    UNDEFINED,
    /** Texture is writable as a color attachment. */
    COLOR_ATTACHMENT_WRITE,
    /** Texture is writable as a depth attachment. */
    DEPTH_ATTACHMENT_WRITE,
    /** Texture is sampled read-only by shaders. */
    SAMPLED_READ,
    /** Uniform buffer is read by shaders. */
    UNIFORM_READ,
    /** Buffer is read by the vertex-input stage. */
    VERTEX_READ,
    /** Buffer is read by the index-input stage. */
    INDEX_READ,
    /** Texture is read as a copy source. This state is not valid for buffers. */
    COPY_SRC,
    /** Texture is written as a copy destination. This state is not valid for buffers. */
    COPY_DST
}
