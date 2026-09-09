package io.github.antonschnfeld.drakon.graphics.resource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable mapping from one or more vertex buffers to shader input attributes.
 *
 * <p>An empty layout is valid for shaders that synthesize vertices from built-in
 * vertex/instance indices, such as full-screen triangle passes.</p>
 *
 * @param bindings vertex-buffer binding declarations
 * @param attributes shader attributes sourced from those bindings
 */
public record VertexLayout(List<VertexBinding> bindings, List<VertexAttribute> attributes) {
    /**
     * Validates and defensively copies a vertex layout.
     *
     * @param bindings vertex-buffer declarations
     * @param attributes shader input declarations
     * @throws NullPointerException if either list or an element is null
     * @throws IllegalArgumentException for duplicate binding indices, duplicate
     *         attribute locations, missing referenced bindings, or attributes
     *         extending beyond a binding stride
     */
    public VertexLayout {
        bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings"));
        attributes = List.copyOf(Objects.requireNonNull(attributes, "attributes"));

        Map<Integer, VertexBinding> bindingsById = new HashMap<>();
        for (VertexBinding binding : bindings) {
            if (bindingsById.putIfAbsent(binding.binding(), binding) != null) {
                throw new IllegalArgumentException("duplicate vertex binding " + binding.binding());
            }
        }

        Set<Integer> locations = new HashSet<>();
        for (VertexAttribute attribute : attributes) {
            if (!locations.add(attribute.location())) {
                throw new IllegalArgumentException("duplicate vertex attribute location " + attribute.location());
            }
            VertexBinding binding = bindingsById.get(attribute.binding());
            if (binding == null) {
                throw new IllegalArgumentException("attribute references missing binding " + attribute.binding());
            }
            if (attribute.offset() + attribute.format().bytes() > binding.stride()) {
                throw new IllegalArgumentException(
                        "attribute at location " + attribute.location() + " exceeds binding " + binding.binding() + " stride");
            }
        }
    }

    /**
     * Creates an empty vertex-layout builder.
     *
     * @return new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for {@link VertexLayout}. */
    public static final class Builder {
        private final List<VertexBinding> bindings = new ArrayList<>();
        private final List<VertexAttribute> attributes = new ArrayList<>();

        private Builder() {}

        /**
         * Appends a vertex-buffer binding declaration.
         *
         * @param binding non-negative binding index
         * @param stride positive byte stride
         * @param rate input advancement rate
         * @return this builder
         */
        public Builder binding(int binding, int stride, VertexInputRate rate) {
            bindings.add(new VertexBinding(binding, stride, rate));
            return this;
        }

        /**
         * Appends a shader attribute declaration.
         *
         * @param location non-negative shader location
         * @param binding vertex binding containing the attribute
         * @param format attribute format
         * @param offset byte offset within each binding record
         * @return this builder
         */
        public Builder attribute(int location, int binding, VertexFormat format, int offset) {
            attributes.add(new VertexAttribute(location, binding, format, offset));
            return this;
        }

        /**
         * Builds and validates an immutable vertex layout.
         *
         * @return completed vertex layout
         * @throws IllegalArgumentException if declarations conflict or are inconsistent
         */
        public VertexLayout build() {
            return new VertexLayout(bindings, attributes);
        }
    }
}
