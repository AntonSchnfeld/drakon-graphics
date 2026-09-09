package io.github.antonschnfeld.drakon.graphics.resource;

/**
 * Integer rasterization scissor rectangle in framebuffer pixels.
 *
 * <p>The coordinate origin is the upper-left of the render target, matching
 * {@link Viewport}. Negative origins are allowed; the effective scissor is
 * clipped to the render target by the backend.</p>
 *
 * @param x upper-left horizontal coordinate
 * @param y upper-left vertical coordinate
 * @param width positive scissor width
 * @param height positive scissor height
 */
public record ScissorRect(int x, int y, int width, int height) {
    /**
     * Validates scissor dimensions.
     *
     * @param x horizontal origin
     * @param y vertical origin
     * @param width positive width
     * @param height positive height
     * @throws IllegalArgumentException if width or height is non-positive
     */
    public ScissorRect {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("scissor dimensions must be > 0");
        }
    }

    /**
     * Creates a scissor rectangle covering the complete target.
     *
     * @param target target whose dimensions should be used
     * @return full-target scissor rectangle
     * @throws NullPointerException if {@code target} is null
     */
    public static ScissorRect full(RenderTarget target) {
        java.util.Objects.requireNonNull(target, "target");
        return new ScissorRect(0, 0, target.width(), target.height());
    }
}
