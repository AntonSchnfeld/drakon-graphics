package io.github.antonschnfeld.drakon.graphics.backend;

import io.github.antonschnfeld.drakon.graphics.command.CommandEncoder;
import io.github.antonschnfeld.drakon.graphics.command.CommandList;
import io.github.antonschnfeld.drakon.graphics.resource.BindingSet;
import io.github.antonschnfeld.drakon.graphics.resource.BindingSetDescriptor;
import io.github.antonschnfeld.drakon.graphics.resource.Buffer;
import io.github.antonschnfeld.drakon.graphics.resource.BufferDescriptor;
import io.github.antonschnfeld.drakon.graphics.resource.ComputeState;
import io.github.antonschnfeld.drakon.graphics.resource.ComputeStateDescriptor;
import io.github.antonschnfeld.drakon.graphics.resource.GraphicsState;
import io.github.antonschnfeld.drakon.graphics.resource.GraphicsStateDescriptor;
import io.github.antonschnfeld.drakon.graphics.resource.RenderTarget;
import io.github.antonschnfeld.drakon.graphics.resource.RenderTargetDescriptor;
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
     * Creates backend compute state.
     *
     * <p>The compute shader only needs to remain valid until this method returns
     * successfully; the resulting state is self-contained for later command
     * recording.</p>
     *
     * @param descriptor compute shader and binding layouts
     * @return a compute-state resource
     * @throws NullPointerException if {@code descriptor} is {@code null}
     * @throws UnsupportedOperationException if compute is not supported by this
     *         device
     * @throws IllegalStateException if this device is closed
     */
    ComputeState createComputeState(ComputeStateDescriptor descriptor);

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
     * @return an encoder in its initial recording state
     * @throws IllegalStateException if this device is closed
     */
    CommandEncoder createCommandEncoder();

    /**
     * Submits a finished command list to this device's ordered submission queue.
     *
     * <p>A command list must originate from this device and is single-submit in
     * the current contract. Submission may return before the GPU has completed
     * the work; resource implementations are responsible for deferring native
     * destruction when necessary.</p>
     *
     * @param commands finished command list created by this device
     * @throws NullPointerException if {@code commands} is {@code null}
     * @throws IllegalArgumentException if the command list belongs to another
     *         device or has already been submitted
     * @throws IllegalStateException if this device is closed
     */
    void submit(CommandList commands);

    /**
     * Returns a snapshot of optional features supported by this device.
     *
     * @return device capabilities
     * @throws IllegalStateException if this device is closed
     */
    GraphicsCapabilities capabilities();

    /**
     * Closes this device and safely releases all resources still owned by it.
     *
     * <p>This method is idempotent. After it returns, no other device operation
     * or child-resource use is valid.</p>
     */
    @Override
    void close();
}
