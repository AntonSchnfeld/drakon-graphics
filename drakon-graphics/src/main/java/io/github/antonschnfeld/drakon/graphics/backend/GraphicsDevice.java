package io.github.antonschnfeld.drakon.graphics.backend;

import io.github.antonschnfeld.drakon.graphics.command.CommandEncoder;
import io.github.antonschnfeld.drakon.graphics.command.CommandList;
import io.github.antonschnfeld.drakon.graphics.resource.BindingSet;
import io.github.antonschnfeld.drakon.graphics.resource.BindingSetDescriptor;
import io.github.antonschnfeld.drakon.graphics.resource.Buffer;
import io.github.antonschnfeld.drakon.graphics.resource.BufferDescriptor;
import io.github.antonschnfeld.drakon.graphics.resource.GraphicsState;
import io.github.antonschnfeld.drakon.graphics.resource.GraphicsStateDescriptor;
import io.github.antonschnfeld.drakon.graphics.resource.RenderTarget;
import io.github.antonschnfeld.drakon.graphics.resource.RenderTargetDescriptor;
import io.github.antonschnfeld.drakon.graphics.resource.ResourceState;
import io.github.antonschnfeld.drakon.graphics.resource.Sampler;
import io.github.antonschnfeld.drakon.graphics.resource.SamplerDescriptor;
import io.github.antonschnfeld.drakon.graphics.resource.Shader;
import io.github.antonschnfeld.drakon.graphics.resource.Texture;
import io.github.antonschnfeld.drakon.graphics.resource.TextureDescriptor;
import io.github.antonschnfeld.drakon.graphics.shader.ShaderDescriptor;
import io.github.antonschnfeld.drakon.graphics.shader.ShaderTarget;

import java.nio.ByteBuffer;

/**
 * Logical GPU device and owner of backend resources created through it.
 *
 * <p>A resource created by one device must only be passed back to that same
 * device and to command encoders created by that device. Cross-device resource
 * use is invalid.</p>
 *
 * <p>Submission uses one logical ordered queue in the current API. Command lists
 * submitted through {@link #submit(CommandList)} execute in submission order.
 * Concrete backends may use more complex native scheduling internally as long
 * as the observable ordering contract is preserved.</p>
 *
 * <p>Closing the device waits for or otherwise safely retires outstanding work,
 * releases still-live child resources, and prevents further API use. Individual
 * resources remain {@link AutoCloseable}; closing a resource may defer native
 * destruction until previously submitted work no longer references it.</p>
 *
 * <p>No general thread-safety guarantee is made by the current API. Callers must
 * externally synchronize access to a device unless a concrete backend explicitly
 * documents stronger guarantees.</p>
 */
public interface GraphicsDevice extends AutoCloseable {
    /**
     * Creates an uninitialized buffer.
     *
     * @param descriptor buffer size and intended usages
     * @return a new buffer owned by this device
     * @throws NullPointerException if {@code descriptor} is {@code null}
     * @throws IllegalStateException if this device is closed
     */
    Buffer createBuffer(BufferDescriptor descriptor);

    /**
     * Creates a buffer and initializes its leading bytes from {@code initialData}.
     *
     * <p>The bytes in {@code initialData} from its current position (inclusive)
     * to its limit (exclusive) are copied. Implementations must not modify the
     * buffer's position or limit. The supplied data must fit within the created
     * buffer.</p>
     *
     * <p>This operation deliberately models creation-time upload only. The core
     * API does not yet claim a complete contract for updating resources that may
     * already be in flight on the GPU.</p>
     *
     * @param descriptor buffer size and intended usages
     * @param initialData initial bytes to copy into the new buffer
     * @return a new initialized buffer owned by this device
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalArgumentException if the remaining data exceeds the buffer
     *         size
     * @throws IllegalStateException if this device is closed
     */
    Buffer createBuffer(BufferDescriptor descriptor, ByteBuffer initialData);

    /**
     * Creates an uninitialized texture.
     *
     * @param descriptor texture dimensions, format, and intended usages
     * @return a new texture owned by this device
     * @throws NullPointerException if {@code descriptor} is {@code null}
     * @throws IllegalStateException if this device is closed
     */
    Texture createTexture(TextureDescriptor descriptor);

    /**
     * Creates a texture, initializes all of mip level zero, and returns it in
     * {@code initialState}.
     *
     * <p>The bytes from {@code initialData}'s current position (inclusive) to
     * its limit (exclusive) must be the complete, tightly packed contents of
     * this two-dimensional texture. The required byte count is {@code width *
     * height * bytes-per-texel(format)}. There is no row padding, mip offset,
     * or array-layer offset in this creation-time-only operation.
     * Implementations must not modify the buffer's position or limit.</p>
     *
     * <p>{@code initialState} is the first portable GPU access state, rather
     * than an implementation upload state. It must be a texture state other
     * than {@link ResourceState#UNDEFINED}
     * and must be compatible with a usage declared by {@code descriptor}.
     * Internal upload mechanics do not require application-visible
     * {@code COPY_DST} usage. Depth-format textures cannot be initialized from
     * CPU data because the current portable API does not define a CPU byte
     * representation for depth texels.</p>
     *
     * @param descriptor texture dimensions, format, and intended usages
     * @param initialData complete tightly packed mip-level-zero texel bytes
     * @param initialState first portable GPU access state
     * @return a new initialized texture owned by this device
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalArgumentException if the byte count is not exact, the
     *         state is not a texture state, is {@code UNDEFINED}, or is
     *         incompatible with the descriptor usage, or the texture format is
     *         a depth format
     * @throws IllegalStateException if this device is closed
     */
    Texture createTexture(TextureDescriptor descriptor, ByteBuffer initialData, ResourceState initialState);

    /**
     * Returns the shader target that code must be prepared for before it is
     * passed to {@link #createShader(ShaderDescriptor)}.
     *
     * <p>This object is intentionally exposed so a separate shader-toolchain
     * module can compile HLSL, Slang, or other authoring languages into the
     * representation and execution environment required by this device.</p>
     *
     * @return immutable shader target for this device
     * @throws IllegalStateException if this device is closed
     */
    ShaderTarget shaderTarget();

    /**
     * Creates a device shader resource from backend-targeted shader code.
     *
     * <p>The core graphics API does not cross-compile shader languages here.
     * The descriptor's code representation must be accepted by
     * {@link #shaderTarget()} and its contents must target that environment. A
     * backend may still perform native validation, driver compilation, or
     * module creation as part of this operation.</p>
     *
     * @param descriptor stage, entry point, and backend-targeted shader code
     * @return a shader resource owned by this device
     * @throws NullPointerException if {@code descriptor} is {@code null}
     * @throws IllegalArgumentException if the code representation is not
     *         accepted by this device target or the code itself is invalid
     * @throws IllegalStateException if this device is closed
     */
    Shader createShader(ShaderDescriptor descriptor);

    /**
     * Creates an immutable sampling-state resource.
     *
     * @param descriptor sampler filtering and addressing behavior
     * @return a new sampler owned by this device
     * @throws NullPointerException if {@code descriptor} is {@code null}
     * @throws IllegalStateException if this device is closed
     */
    Sampler createSampler(SamplerDescriptor descriptor);

    /**
     * Creates backend graphics state from a complete graphics-state descriptor.
     *
     * <p>Shaders supplied by the descriptor only need to remain valid until this
     * method returns successfully. The resulting state owns any backend
     * representation required for later command recording, so the input shader
     * resources may be closed afterward.</p>
     *
     * @param descriptor complete graphics state
     * @return a graphics-state resource compatible with the declared attachment
     *         formats and binding layouts
     * @throws NullPointerException if {@code descriptor} is {@code null}
     * @throws IllegalArgumentException if the requested state is unsupported or
     *         inconsistent
     * @throws IllegalStateException if this device is closed
     */
    GraphicsState createGraphicsState(GraphicsStateDescriptor descriptor);

    /**
     * Creates an immutable set of resource bindings.
     *
     * <p>The binding set does not own the textures, samplers, or buffers that it
     * references. Those resources must remain valid whenever this set is used by
     * submitted work.</p>
     *
     * @param descriptor layout and one value for every binding
     * @return a device binding-set resource
     * @throws NullPointerException if {@code descriptor} is {@code null}
     * @throws IllegalStateException if this device is closed
     */
    BindingSet createBindingSet(BindingSetDescriptor descriptor);

    /**
     * Creates an offscreen render target that groups existing attachment textures.
     *
     * <p>The target does not own its attachment textures. Closing the target
     * therefore does not close those textures. Presentation-backed targets are
     * created by backend/window integration rather than this descriptor-based
     * factory because their backing images are not necessarily public textures.</p>
     *
     * @param descriptor target attachments
     * @return a render target owned by this device
     * @throws NullPointerException if {@code descriptor} is {@code null}
     * @throws IllegalStateException if this device is closed
     */
    RenderTarget createRenderTarget(RenderTargetDescriptor descriptor);

    /**
     * Presents the latest submitted contents of a presentation-capable render target.
     *
     * <p>Presentation capability is intentionally not represented by a separate
     * public target subtype. Ordinary rendering code only needs {@link RenderTarget};
     * the backend or window integration that created a presentation-backed target
     * is responsible for supplying one that this operation accepts.</p>
     *
     * <p>This operation also deliberately hides backend-owned presentation
     * mechanics such as Vulkan swapchain image acquisition, image rotation,
     * presentation synchronization, and internal presentation-state transitions.
     * Those resources are not exposed as application {@link Texture} objects and
     * therefore do not participate in the caller-managed resource-state API. The
     * caller is responsible for submitting any rendering work intended for this
     * presentation before invoking this method.</p>
     *
     * @param target presentation-backed target owned by this device
     * @throws NullPointerException if {@code target} is {@code null}
     * @throws IllegalArgumentException if {@code target} belongs to another device
     *         or is not presentation-capable
     * @throws IllegalStateException if this device or the target is closed, or if
     *         the target cannot currently be presented
     */
    void present(RenderTarget target);

    /**
     * Creates a fresh command encoder associated with this device.
     *
     * <p>The caller owns the returned encoder and must close it. A successful
     * {@link CommandEncoder#finish()} transfers its backend command ownership to
     * the returned list.</p>
     *
     * @return an encoder in its initial recording state
     * @throws IllegalStateException if this device is closed
     */
    CommandEncoder createCommandEncoder();

    /**
     * Submits a finished command list to this device's ordered submission queue.
     *
     * <p>A command list must originate from this device and is consumed by a
     * submission attempt by its originating device. Successful native submission
     * transfers backend command ownership to the device; validation rejection or
     * native submission failure leaves the list terminal.
     * Submission may return before the GPU has completed the work, so resource
     * implementations defer native destruction when necessary. The caller must
     * still close the list; closing after successful submission is harmless.</p>
     *
     * @param commands finished command list created by this device
     * @throws NullPointerException if {@code commands} is {@code null}
     * @throws IllegalArgumentException if the command list belongs to another device
     * @throws IllegalStateException if this device is closed or the command list
     *         is not ready for its one submission attempt
     */
    void submit(CommandList commands);

    /**
     * Closes this device and safely releases all resources still owned by it.
     *
     * <p>This method is idempotent. After it returns, no other device operation
     * or child-resource use is valid.</p>
     */
    @Override
    void close();
}
