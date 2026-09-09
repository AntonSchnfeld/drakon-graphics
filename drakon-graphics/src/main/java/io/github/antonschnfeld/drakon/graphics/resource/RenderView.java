package io.github.antonschnfeld.drakon.graphics.resource;

import java.util.Objects;

/**
 * Convenience grouping of a render target with viewport and scissor state.
 *
 * <p>This is intentionally a graphics-level view, not a camera. It contains no
 * projection matrix, position, scene, or other engine semantics.</p>
 *
 * <p>The viewport and scissor are value snapshots. If a presentation-backed
 * target changes size, a full-target view created before the resize does not
 * automatically change and should be rebuilt.</p>
 *
 * @param target render target
 * @param viewport rasterization viewport
 * @param scissor rasterization scissor rectangle
 */
public record RenderView(RenderTarget target, Viewport viewport, ScissorRect scissor) {
    /**
     * Validates non-null view components.
     *
     * @param target render target
     * @param viewport viewport
     * @param scissor scissor rectangle
     * @throws NullPointerException if any argument is null
     */
    public RenderView {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(viewport, "viewport");
        Objects.requireNonNull(scissor, "scissor");
    }

    /**
     * Creates a view covering the full target.
     *
     * @param target target to cover
     * @return full-target render view
     * @throws NullPointerException if {@code target} is null
     */
    public static RenderView fullTarget(RenderTarget target) {
        Objects.requireNonNull(target, "target");
        return new RenderView(target, Viewport.full(target), ScissorRect.full(target));
    }
}
