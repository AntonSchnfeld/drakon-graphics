package io.github.antonschnfeld.drakon.graphics.resource;

import java.util.Objects;

/**
 * One shader vertex-input attribute.
 *
 * @param location non-negative shader input location
 * @param binding vertex-buffer binding containing the attribute
 * @param format packed attribute format
 * @param offset non-negative byte offset of the attribute within each record
 */
public record VertexAttribute(int location, int binding, VertexFormat format, int offset) {
    /**
     * Validates basic attribute coordinates.
     *
     * @param location non-negative shader location
     * @param binding non-negative vertex binding
     * @param format attribute format
     * @param offset non-negative byte offset
     * @throws IllegalArgumentException if location, binding, or offset is negative
     * @throws NullPointerException if {@code format} is null
     */
    public VertexAttribute {
        if (location < 0 || binding < 0 || offset < 0) {
            throw new IllegalArgumentException("location, binding and offset must be >= 0");
        }
        Objects.requireNonNull(format, "format");
    }
}
