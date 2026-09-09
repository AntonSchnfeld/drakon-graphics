package io.github.antonschnfeld.drakon.graphics.resource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable values used to create one {@link BindingSet}.
 *
 * <p>Exactly one value must be supplied for every declaration in the associated
 * {@link BindingLayout}; partial binding sets are not supported by the current
 * API. Resource-usage flags are validated when the descriptor is built.</p>
 */
public final class BindingSetDescriptor {
    private final BindingLayout layout;
    private final Map<Binding<?>, Object> values;

    private BindingSetDescriptor(BindingLayout layout, Map<Binding<?>, Object> values) {
        this.layout = Objects.requireNonNull(layout, "layout");
        this.values = Map.copyOf(values);

        if (this.values.size() != layout.bindings().size()) {
            throw new IllegalArgumentException("binding set must provide exactly one value for every layout binding");
        }

        for (Binding<?> binding : layout.bindings()) {
            Object value = this.values.get(binding);
            if (value == null) {
                throw new IllegalArgumentException("missing value for binding " + binding.name());
            }
            if (!binding.valueType().isInstance(value)) {
                throw new IllegalArgumentException("wrong value type for binding " + binding.name());
            }
            validateUsage(binding, value);
        }
    }

    private static void validateUsage(Binding<?> binding, Object value) {
        switch (binding.type()) {
            case SAMPLED_TEXTURE -> {
                TextureBinding sampled = (TextureBinding) value;
                if (!sampled.texture().usage().contains(TextureUsage.SAMPLED)) {
                    throw new IllegalArgumentException("texture for binding " + binding.name() + " lacks SAMPLED usage");
                }
            }
            case UNIFORM_BUFFER -> {
                BufferBinding buffer = (BufferBinding) value;
                if (!buffer.buffer().usage().contains(BufferUsage.UNIFORM)) {
                    throw new IllegalArgumentException("buffer for binding " + binding.name() + " lacks UNIFORM usage");
                }
            }
            case STORAGE_BUFFER -> {
                BufferBinding buffer = (BufferBinding) value;
                if (!buffer.buffer().usage().contains(BufferUsage.STORAGE)) {
                    throw new IllegalArgumentException("buffer for binding " + binding.name() + " lacks STORAGE usage");
                }
            }
        }
    }

    /**
     * Returns the layout whose bindings are populated by this descriptor.
     *
     * @return binding layout
     */
    public BindingLayout layout() {
        return layout;
    }

    /**
     * Returns the immutable binding-to-value map.
     *
     * <p>Keys are the exact {@link Binding} instances from {@link #layout()}.</p>
     *
     * @return immutable binding values
     */
    public Map<Binding<?>, Object> values() {
        return values;
    }

    /**
     * Creates a builder for a binding layout.
     *
     * @param layout layout to populate
     * @return new binding-set descriptor builder
     * @throws NullPointerException if {@code layout} is null
     */
    public static Builder builder(BindingLayout layout) {
        return new Builder(layout);
    }

    /** Builder for a complete {@link BindingSetDescriptor}. */
    public static final class Builder {
        private final BindingLayout layout;
        private final Map<Binding<?>, Object> values = new LinkedHashMap<>();

        private Builder(BindingLayout layout) {
            this.layout = Objects.requireNonNull(layout, "layout");
        }

        /**
         * Assigns a typed value to one binding in this builder's layout.
         *
         * <p>The exact binding instance must belong to the layout. Rebinding the
         * same binding replaces its previous value.</p>
         *
         * @param <T> Java value type accepted by the binding
         * @param binding binding key from this builder's layout
         * @param value non-null value matching the binding's type
         * @return this builder
         * @throws NullPointerException if either argument is null
         * @throws IllegalArgumentException if the binding does not belong to the
         *         layout or the runtime value type is incompatible
         */
        public <T> Builder bind(Binding<T> binding, T value) {
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(value, "value");
            if (!layout.bindings().contains(binding)) {
                throw new IllegalArgumentException("binding does not belong to layout: " + binding);
            }
            if (!binding.valueType().isInstance(value)) {
                throw new IllegalArgumentException("wrong value type for binding " + binding.name());
            }
            values.put(binding, value);
            return this;
        }

        /**
         * Builds and validates a complete descriptor.
         *
         * @return immutable binding-set descriptor
         * @throws IllegalArgumentException if any layout binding is missing or a
         *         bound resource lacks the usage required by its binding type
         */
        public BindingSetDescriptor build() {
            return new BindingSetDescriptor(layout, values);
        }
    }
}
