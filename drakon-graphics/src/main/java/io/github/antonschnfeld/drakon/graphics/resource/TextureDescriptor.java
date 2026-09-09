package io.github.antonschnfeld.drakon.graphics.resource;

import java.util.Objects;
import java.util.Set;

/**
 * Immutable two-dimensional texture creation descriptor.
 *
 * @param width positive width in texels
 * @param height positive height in texels
 * @param format texture storage format
 * @param usage non-empty set of all usages required during the texture lifetime
 */
public record TextureDescriptor(
        int width,
        int height,
        TextureFormat format,
        Set<TextureUsage> usage
) {
    /**
     * Validates and defensively copies a texture descriptor.
     *
     * @param width positive width in texels
     * @param height positive height in texels
     * @param format texture format
     * @param usage non-empty usage set
     * @throws IllegalArgumentException if dimensions are non-positive or usage is empty
     * @throws NullPointerException if {@code format}, {@code usage}, or a usage is null
     */
    public TextureDescriptor {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("texture dimensions must be > 0");
        }
        Objects.requireNonNull(format, "format");
        usage = Set.copyOf(Objects.requireNonNull(usage, "usage"));
        if (usage.isEmpty()) {
            throw new IllegalArgumentException("usage must not be empty");
        }
    }
}
