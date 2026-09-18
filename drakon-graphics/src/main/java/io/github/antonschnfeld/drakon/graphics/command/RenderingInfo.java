package io.github.antonschnfeld.drakon.graphics.command;

import io.github.antonschnfeld.drakon.graphics.resource.RenderTarget;
import io.github.antonschnfeld.drakon.graphics.resource.RenderView;
import io.github.antonschnfeld.drakon.graphics.resource.ScissorRect;
import io.github.antonschnfeld.drakon.graphics.resource.Viewport;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Complete description of one graphics rendering scope.
 *
 * <p>The render target identifies the render destination and attachment formats.
 * This object adds per-use state: viewport, scissor, and attachment load/store
 * behavior. For caller-owned texture attachments, this object does not perform
 * resource-state transitions; those textures must be transitioned explicitly
 * before {@link CommandEncoder#beginRendering(RenderingInfo)}. Backend-owned
 * presentation attachments are different: backend-owned presentation color and
 * depth storage is not exposed as application textures, so its native layout
 * and synchronization are managed internally. Attachment load, store, and clear
 * behavior still comes from this rendering description.</p>
 *
 * <p>Viewport and scissor values are snapshots. Rebuild an instance that was
 * derived from a full presentation target after that target changes size.</p>
 */
public final class RenderingInfo {
    private final RenderTarget target;
    private final Viewport viewport;
    private final ScissorRect scissor;
    private final List<ColorAttachmentOps> colors;
    private final DepthAttachmentOps depth;

    private RenderingInfo(Builder b) {
        target = Objects.requireNonNull(b.target, "target");
        viewport = b.viewport != null ? b.viewport : Viewport.full(target);
        scissor = b.scissor != null ? b.scissor : ScissorRect.full(target);
        colors = List.copyOf(b.colors);
        if (colors.size() != target.colorFormats().size()) {
            throw new IllegalArgumentException("one ColorAttachmentOps is required per color attachment");
        }
        depth = b.depth;
        if ((target.depthFormat() == null) != (depth == null)) {
            throw new IllegalArgumentException("depth ops must be present iff target has a depth attachment");
        }
    }

    /**
     * Returns the attachment target used by the rendering scope.
     *
     * @return render target
     */
    public RenderTarget target() {
        return target;
    }

    /**
     * Returns the viewport used for rasterization.
     *
     * @return rendering viewport
     */
    public Viewport viewport() {
        return viewport;
    }

    /**
     * Returns the scissor rectangle applied during rasterization.
     *
     * @return rendering scissor rectangle
     */
    public ScissorRect scissor() {
        return scissor;
    }

    /**
     * Returns color attachment operations in the same order as the target's
     * color formats.
     *
     * @return immutable color-operation list
     */
    public List<ColorAttachmentOps> colors() {
        return colors;
    }

    /**
     * Returns depth operations when the target has a depth attachment.
     *
     * @return depth operations, or an empty optional for a color-only target
     */
    public Optional<DepthAttachmentOps> depth() {
        return Optional.ofNullable(depth);
    }

    /**
     * Creates a builder for a target using full-target viewport/scissor defaults.
     *
     * @param target rendering target
     * @return new builder
     * @throws NullPointerException if {@code target} is {@code null}
     */
    public static Builder builder(RenderTarget target) {
        return new Builder(target);
    }

    /**
     * Creates a builder initialized from a {@link RenderView}.
     *
     * @param view target, viewport, and scissor source
     * @return new builder initialized from the view
     * @throws NullPointerException if {@code view} is {@code null}
     */
    public static Builder builder(RenderView view) {
        Objects.requireNonNull(view, "view");
        return new Builder(view.target()).viewport(view.viewport()).scissor(view.scissor());
    }

    /** Builder for immutable {@link RenderingInfo} instances. */
    public static final class Builder {
        private final RenderTarget target;
        private Viewport viewport;
        private ScissorRect scissor;
        private final List<ColorAttachmentOps> colors = new ArrayList<>();
        private DepthAttachmentOps depth;

        private Builder(RenderTarget target) {
            this.target = Objects.requireNonNull(target, "target");
        }

        /**
         * Overrides the viewport; otherwise the full target is used.
         *
         * @param value viewport to use
         * @return this builder
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public Builder viewport(Viewport value) {
            viewport = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Overrides the scissor rectangle; otherwise the full target is used.
         *
         * @param value scissor rectangle to use
         * @return this builder
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public Builder scissor(ScissorRect value) {
            scissor = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Appends operations for the next color attachment.
         *
         * <p>Operations must be appended in the same order as
         * {@link RenderTarget#colorFormats()}.</p>
         *
         * @param value operations for one color attachment
         * @return this builder
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public Builder color(ColorAttachmentOps value) {
            colors.add(Objects.requireNonNull(value, "value"));
            return this;
        }

        /**
         * Sets operations for the target's depth attachment.
         *
         * @param value depth operations
         * @return this builder
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public Builder depth(DepthAttachmentOps value) {
            depth = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Builds and validates the rendering-scope description.
         *
         * @return immutable rendering info
         * @throws IllegalArgumentException if the number of color operations
         *         does not match the target or depth operations do not match the
         *         target's depth-attachment presence
         */
        public RenderingInfo build() {
            return new RenderingInfo(this);
        }
    }
}
