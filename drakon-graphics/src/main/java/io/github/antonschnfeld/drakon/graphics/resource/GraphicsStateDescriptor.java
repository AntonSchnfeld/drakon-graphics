package io.github.antonschnfeld.drakon.graphics.resource;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Backend-agnostic description of graphics shader and fixed-function state.
 *
 * <p>Attachment formats are deliberately part of graphics state. APIs such as
 * Vulkan need them while creating a compatible graphics pipeline; making them
 * explicit avoids hidden backend-specific lazy specialization.</p>
 *
 * <p>The 0.1.0 contract requires exactly one color attachment and therefore
 * exactly one non-depth color format plus a fragment shader. An optional depth
 * format may additionally be declared. The list shape is retained so a future
 * release can relax the color-attachment limit additively. Binding layouts are
 * ordered by binding-group index.</p>
 */
public final class GraphicsStateDescriptor {
    private final Shader vertexShader;
    private final Shader fragmentShader;
    private final VertexLayout vertexLayout;
    private final PrimitiveTopology topology;
    private final DepthState depthState;
    private final BlendState blendState;
    private final RasterState rasterState;
    private final List<BindingLayout> bindingLayouts;
    private final List<TextureFormat> colorFormats;
    private final TextureFormat depthFormat;

    private GraphicsStateDescriptor(Builder b) {
        vertexShader = Objects.requireNonNull(b.vertexShader, "vertexShader");
        if (vertexShader.stage() != ShaderStage.VERTEX) {
            throw new IllegalArgumentException("vertexShader must be VERTEX stage");
        }

        fragmentShader = Objects.requireNonNull(b.fragmentShader, "fragmentShader");
        if (fragmentShader.stage() != ShaderStage.FRAGMENT) {
            throw new IllegalArgumentException("fragmentShader must be FRAGMENT stage");
        }

        vertexLayout = Objects.requireNonNull(b.vertexLayout, "vertexLayout");
        topology = Objects.requireNonNull(b.topology, "topology");
        depthState = Objects.requireNonNull(b.depthState, "depthState");
        blendState = Objects.requireNonNull(b.blendState, "blendState");
        rasterState = Objects.requireNonNull(b.rasterState, "rasterState");
        bindingLayouts = List.copyOf(b.bindingLayouts);
        colorFormats = List.copyOf(b.colorFormats);
        depthFormat = b.depthFormat;

        if (colorFormats.size() != 1) {
            throw new IllegalArgumentException("graphics state requires exactly one color format");
        }
        for (TextureFormat format : colorFormats) {
            if (format.isDepth()) {
                throw new IllegalArgumentException("color format must not be a depth format");
            }
        }
        if (depthFormat != null && !depthFormat.isDepth()) {
            throw new IllegalArgumentException("depthFormat must be a depth format");
        }
        if ((depthState.testEnabled() || depthState.writeEnabled()) && depthFormat == null) {
            throw new IllegalArgumentException("enabled depth state requires depthFormat");
        }
    }

    /** Returns the required vertex-stage shader.
     * @return required vertex-stage shader */
    public Shader vertexShader() {
        return vertexShader;
    }

    /**
     * Returns the required fragment shader.
     *
     * @return required fragment-stage shader
     */
    public Shader fragmentShader() {
        return fragmentShader;
    }

    /** Returns the immutable vertex-input layout.
     * @return immutable vertex-input layout */
    public VertexLayout vertexLayout() {
        return vertexLayout;
    }

    /** Returns the primitive assembly topology.
     * @return primitive assembly topology */
    public PrimitiveTopology topology() {
        return topology;
    }

    /** Returns the depth-test/write state.
     * @return depth-test/write state */
    public DepthState depthState() {
        return depthState;
    }

    /** Returns the color blending state.
     * @return color blending state */
    public BlendState blendState() {
        return blendState;
    }

    /** Returns the rasterization state.
     * @return rasterization state */
    public RasterState rasterState() {
        return rasterState;
    }

    /**
     * Returns binding layouts in group-index order.
     *
     * @return immutable ordered binding-layout list
     */
    public List<BindingLayout> bindingLayouts() {
        return bindingLayouts;
    }

    /**
     * Returns expected color attachment formats in target attachment order.
     *
     * @return immutable list containing exactly one color format
     */
    public List<TextureFormat> colorFormats() {
        return colorFormats;
    }

    /**
     * Returns the expected depth attachment format.
     *
     * @return expected depth format, or empty when no depth attachment is used
     */
    public Optional<TextureFormat> depthFormat() {
        return Optional.ofNullable(depthFormat);
    }

    /**
     * Creates a builder with portable defaults: empty vertex layout, triangle
     * topology, disabled depth, opaque blending, and standard rasterization.
     *
     * @return new graphics-state descriptor builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for {@link GraphicsStateDescriptor}. */
    public static final class Builder {
        private Shader vertexShader;
        private Shader fragmentShader;
        private VertexLayout vertexLayout = new VertexLayout(List.of(), List.of());
        private PrimitiveTopology topology = PrimitiveTopology.TRIANGLES;
        private DepthState depthState = DepthState.disabled();
        private BlendState blendState = BlendState.opaque();
        private RasterState rasterState = RasterState.standard();
        private final List<BindingLayout> bindingLayouts = new ArrayList<>();
        private final List<TextureFormat> colorFormats = new ArrayList<>();
        private TextureFormat depthFormat;

        private Builder() {}

        /**
         * Sets the required vertex shader.
         *
         * @param value vertex-stage shader
         * @return this builder
         * @throws NullPointerException if {@code value} is null
         */
        public Builder vertexShader(Shader value) {
            vertexShader = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Sets the required fragment shader.
         *
         * @param value fragment-stage shader
         * @return this builder
         * @throws NullPointerException if {@code value} is null
         */
        public Builder fragmentShader(Shader value) {
            fragmentShader = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Sets vertex-buffer bindings and attributes.
         *
         * @param value vertex layout
         * @return this builder
         * @throws NullPointerException if {@code value} is null
         */
        public Builder vertexLayout(VertexLayout value) {
            vertexLayout = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Sets primitive topology.
         *
         * @param value topology
         * @return this builder
         * @throws NullPointerException if {@code value} is null
         */
        public Builder topology(PrimitiveTopology value) {
            topology = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Sets depth testing/writing behavior.
         *
         * @param value depth state
         * @return this builder
         * @throws NullPointerException if {@code value} is null
         */
        public Builder depth(DepthState value) {
            depthState = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Sets color blending behavior.
         *
         * @param value blend state
         * @return this builder
         * @throws NullPointerException if {@code value} is null
         */
        public Builder blend(BlendState value) {
            blendState = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Sets rasterization behavior.
         *
         * @param value raster state
         * @return this builder
         * @throws NullPointerException if {@code value} is null
         */
        public Builder raster(RasterState value) {
            rasterState = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Appends one resource binding layout; append order defines group index.
         *
         * @param value binding layout
         * @return this builder
         * @throws NullPointerException if {@code value} is null
         */
        public Builder bindingLayout(BindingLayout value) {
            bindingLayouts.add(Objects.requireNonNull(value, "value"));
            return this;
        }

        /**
         * Appends one expected color-attachment format in target attachment order.
         *
         * @param value non-depth texture format
         * @return this builder
         * @throws NullPointerException if {@code value} is null
         */
        public Builder colorFormat(TextureFormat value) {
            colorFormats.add(Objects.requireNonNull(value, "value"));
            return this;
        }

        /**
         * Sets the expected depth-attachment format.
         *
         * @param value depth texture format
         * @return this builder
         * @throws NullPointerException if {@code value} is null
         */
        public Builder depthFormat(TextureFormat value) {
            depthFormat = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Builds and validates the complete graphics-state descriptor.
         *
         * @return immutable graphics-state descriptor
         * @throws NullPointerException if a required shader or state is missing
         * @throws IllegalArgumentException if shader stages, formats, or depth
         *         configuration are inconsistent
         */
        public GraphicsStateDescriptor build() {
            return new GraphicsStateDescriptor(this);
        }
    }
}
