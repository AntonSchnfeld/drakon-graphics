package io.github.antonschnfeld.drakon.graphics.resource;

/**
 * Floating-point rasterization viewport.
 *
 * <p>{@code x} and {@code y} are expressed in framebuffer pixels from the
 * upper-left of the render target. Backends whose native viewport convention
 * differs are responsible for translating this portable convention.</p>
 *
 * @param x horizontal upper-left viewport coordinate in pixels
 * @param y vertical upper-left viewport coordinate in pixels
 * @param width positive viewport width
 * @param height positive viewport height
 * @param minDepth minimum depth-range value in {@code [0,1]}
 * @param maxDepth maximum depth-range value in {@code [0,1]}, not below minDepth
 */
public record Viewport(float x, float y, float width, float height, float minDepth, float maxDepth) {
    /**
     * Validates viewport extent and depth range.
     *
     * @param x horizontal origin
     * @param y vertical origin
     * @param width positive width
     * @param height positive height
     * @param minDepth minimum depth
     * @param maxDepth maximum depth
     * @throws IllegalArgumentException if dimensions are non-positive or the
     *         depth range does not satisfy {@code 0 <= min <= max <= 1}
     */
    public Viewport {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("viewport dimensions must be > 0");
        }
        if (minDepth < 0 || maxDepth > 1 || minDepth > maxDepth) {
            throw new IllegalArgumentException("depth range must satisfy 0 <= min <= max <= 1");
        }
    }

    /**
     * Creates a viewport covering the complete target with depth range [0,1].
     *
     * @param target target whose dimensions should be used
     * @return full-target viewport
     * @throws NullPointerException if {@code target} is null
     */
    public static Viewport full(RenderTarget target) {
        java.util.Objects.requireNonNull(target, "target");
        return new Viewport(0, 0, target.width(), target.height(), 0, 1);
    }
}
