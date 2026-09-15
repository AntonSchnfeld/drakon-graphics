package io.github.antonschnfeld.drakon.graphics.resource;

import java.util.Objects;
import java.util.Set;

/**
 * Typed declaration of one resource slot within a {@link BindingLayout}.
 *
 * <p>A binding object acts as both schema and strongly typed key. The same
 * {@code Binding} instance used to construct a layout should be used when
 * populating a {@link BindingSetDescriptor}; bindings intentionally use object
 * identity rather than structural equality.</p>
 *
 * @param <T> Java value type accepted by this binding
 */
public final class Binding<T> {
    private final String name;
    private final int binding;
    private final BindingType type;
    private final Class<T> valueType;
    private final Set<ShaderStage> stages;

    private Binding(String name, int binding, BindingType type, Class<T> valueType, Set<ShaderStage> stages) {
        this.name = Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (binding < 0) {
            throw new IllegalArgumentException("binding must be >= 0");
        }
        this.binding = binding;
        this.type = Objects.requireNonNull(type, "type");
        this.valueType = Objects.requireNonNull(valueType, "valueType");
        this.stages = Set.copyOf(stages);
        if (this.stages.isEmpty()) {
            throw new IllegalArgumentException("stages must not be empty");
        }
    }

    /**
     * Declares a sampled-texture slot.
     *
     * <p>The value intentionally contains both the texture and its sampler.
     * Vulkan maps this naturally to a combined image-sampler descriptor, while
     * OpenGL binds the texture and sampler object to the same texture unit.
     * Keeping them together avoids exposing a Vulkan-shaped split that cannot
     * be represented independently by ordinary OpenGL sampler uniforms.</p>
     *
     * @param name shader resource name; must not be blank
     * @param binding non-negative backend-neutral binding number within its layout
     * @param stages shader stages allowed to access the binding
     * @return typed sampled-texture binding
     * @throws NullPointerException if {@code name}, {@code stages}, or a stage is null
     * @throws IllegalArgumentException if name is blank, binding is negative, or
     *         no stages are supplied
     */
    public static Binding<TextureBinding> sampledTexture(String name, int binding, ShaderStage... stages) {
        return new Binding<>(name, binding, BindingType.SAMPLED_TEXTURE, TextureBinding.class, Set.of(stages));
    }

    /**
     * Declares a uniform-buffer range slot.
     *
     * @param name shader resource name; must not be blank
     * @param binding non-negative binding number within its layout
     * @param stages shader stages allowed to read the range
     * @return typed uniform-buffer binding
     * @throws NullPointerException if {@code name}, {@code stages}, or a stage is null
     * @throws IllegalArgumentException if name is blank, binding is negative, or
     *         no stages are supplied
     */
    public static Binding<BufferBinding> uniformBuffer(String name, int binding, ShaderStage... stages) {
        return new Binding<>(name, binding, BindingType.UNIFORM_BUFFER, BufferBinding.class, Set.of(stages));
    }

    /** Returns the shader resource name associated with this binding.
     *
     * <p>Backends whose native binding model is not set/group based may use
     * this name to associate the logical binding with a reflected shader
     * resource. Authoring/compiler layers should therefore preserve it as a
     * stable resource identifier rather than treating it as debug-only text.
     * Absence from a linked shader's active-resource interface does not by
     * itself make the binding invalid because a native linker may have removed
     * an otherwise valid but unused declaration.</p>
     *
     * @return stable shader resource name */
    public String name() {
        return name;
    }

    /** Returns the non-negative binding number within the layout.
     * @return non-negative binding number within the layout */
    public int binding() {
        return binding;
    }

    /** Returns the resource kind represented by this binding.
     * @return resource kind represented by this binding */
    public BindingType type() {
        return type;
    }

    /** Returns the runtime Java type accepted as the binding value.
     * @return runtime Java type accepted as the binding value */
    public Class<T> valueType() {
        return valueType;
    }

    /** Returns the shader stages allowed to access the binding.
     * @return immutable set of shader stages allowed to access the binding */
    public Set<ShaderStage> stages() {
        return stages;
    }

    /**
     * Returns a concise debug representation.
     *
     * @return name, numeric slot, and binding type
     */
    @Override
    public String toString() {
        return name + "@" + binding + ":" + type;
    }
}
