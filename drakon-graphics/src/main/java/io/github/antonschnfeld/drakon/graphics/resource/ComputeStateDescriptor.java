package io.github.antonschnfeld.drakon.graphics.resource;

import io.github.antonschnfeld.drakon.graphics.command.CommandEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable description of compute shader state.
 *
 * <p>Binding layouts are ordered. Their list index is the binding-group index
 * used by {@link CommandEncoder#bindSet(int, BindingSet)}.</p>
 */
public final class ComputeStateDescriptor {
    private final Shader computeShader;
    private final List<BindingLayout> bindingLayouts;

    private ComputeStateDescriptor(Builder b) {
        computeShader = Objects.requireNonNull(b.computeShader, "computeShader");
        if (computeShader.stage() != ShaderStage.COMPUTE) {
            throw new IllegalArgumentException("computeShader must be COMPUTE stage");
        }
        bindingLayouts = List.copyOf(b.bindingLayouts);
    }

    /**
     * Returns the compute shader.
     *
     * @return compute-stage shader
     */
    public Shader computeShader() {
        return computeShader;
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
     * Creates an empty descriptor builder.
     *
     * @return new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for {@link ComputeStateDescriptor}. */
    public static final class Builder {
        private Shader computeShader;
        private final List<BindingLayout> bindingLayouts = new ArrayList<>();

        private Builder() {}

        /**
         * Sets the required compute-stage shader.
         *
         * @param value compute shader
         * @return this builder
         * @throws NullPointerException if {@code value} is null
         */
        public Builder computeShader(Shader value) {
            computeShader = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Appends one resource binding layout; append order defines its group index.
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
         * Builds and validates the compute-state descriptor.
         *
         * @return immutable descriptor
         * @throws NullPointerException if no compute shader was supplied
         * @throws IllegalArgumentException if the supplied shader is not a compute shader
         */
        public ComputeStateDescriptor build() {
            return new ComputeStateDescriptor(this);
        }
    }
}
