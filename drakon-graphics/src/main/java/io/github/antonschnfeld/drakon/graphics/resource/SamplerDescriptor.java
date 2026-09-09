package io.github.antonschnfeld.drakon.graphics.resource;

import java.util.Objects;

/**
 * Immutable texture sampler descriptor.
 *
 * @param minFilter filtering used when a texture is minified
 * @param magFilter filtering used when a texture is magnified
 * @param addressMode addressing behavior used outside normalized texture range
 */
public record SamplerDescriptor(Filter minFilter, Filter magFilter, AddressMode addressMode) {
    /** Texture filtering mode. */
    public enum Filter {
        /** Use the nearest texel/sample. */
        NEAREST,
        /** Linearly interpolate neighboring samples. */
        LINEAR
    }

    /** Texture coordinate addressing mode. */
    public enum AddressMode {
        /** Repeat normalized coordinates periodically. */
        REPEAT,
        /** Clamp normalized coordinates to the texture edge. */
        CLAMP_TO_EDGE
    }

    /**
     * Validates sampler state.
     *
     * @param minFilter minification filter
     * @param magFilter magnification filter
     * @param addressMode texture address mode
     * @throws NullPointerException if any argument is {@code null}
     */
    public SamplerDescriptor {
        Objects.requireNonNull(minFilter, "minFilter");
        Objects.requireNonNull(magFilter, "magFilter");
        Objects.requireNonNull(addressMode, "addressMode");
    }

    /**
     * Returns a linear sampler clamped at texture edges.
     *
     * @return linear-clamp sampler descriptor
     */
    public static SamplerDescriptor linearClamp() {
        return new SamplerDescriptor(Filter.LINEAR, Filter.LINEAR, AddressMode.CLAMP_TO_EDGE);
    }

    /**
     * Returns a linear repeating sampler.
     *
     * @return linear-repeat sampler descriptor
     */
    public static SamplerDescriptor linearRepeat() {
        return new SamplerDescriptor(Filter.LINEAR, Filter.LINEAR, AddressMode.REPEAT);
    }
}
