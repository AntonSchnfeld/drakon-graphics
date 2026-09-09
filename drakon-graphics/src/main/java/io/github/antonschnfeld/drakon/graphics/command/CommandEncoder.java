package io.github.antonschnfeld.drakon.graphics.command;

import io.github.antonschnfeld.drakon.graphics.backend.GraphicsDevice;
import io.github.antonschnfeld.drakon.graphics.resource.BindingSet;
import io.github.antonschnfeld.drakon.graphics.resource.Buffer;
import io.github.antonschnfeld.drakon.graphics.resource.ComputeState;
import io.github.antonschnfeld.drakon.graphics.resource.GraphicsState;
import io.github.antonschnfeld.drakon.graphics.resource.IndexType;
import io.github.antonschnfeld.drakon.graphics.resource.ResourceState;
import io.github.antonschnfeld.drakon.graphics.resource.Texture;

/**
 * Records backend-agnostic GPU commands into one command list.
 *
 * <p>An encoder is mutable and single-thread confined. Calling {@link #finish()}
 * permanently ends recording; no other command may be recorded afterward.</p>
 *
 * <p>All GPU resources supplied to an encoder must originate from the same
 * {@link GraphicsDevice} that created the encoder.
 * Resource-state transitions for application-visible textures and buffers are
 * explicit. {@link #beginRendering(RenderingInfo)} does not silently transition
 * caller-owned attachments. Backend-owned presentation images are the deliberate
 * exception because they are not exposed as resources the application can
 * transition itself.</p>
 */
public interface CommandEncoder {
    /**
     * Begins a graphics rendering scope.
     *
     * <p>Every caller-owned texture attachment must already be in its corresponding
     * writable resource state: {@link ResourceState#COLOR_ATTACHMENT_WRITE} for
     * color and {@link ResourceState#DEPTH_ATTACHMENT_WRITE} for depth. A
     * presentation-backed target may instead use backend-owned images whose
     * acquire/layout transitions are handled internally.</p>
     *
     * @param info target, viewport/scissor, and per-attachment load/store operations
     * @throws NullPointerException if {@code info} is {@code null}
     * @throws IllegalStateException if a rendering scope is already active or
     *         recording has finished
     */
    void beginRendering(RenderingInfo info);

    /**
     * Ends the active graphics rendering scope.
     *
     * @throws IllegalStateException if no rendering scope is active or recording
     *         has finished
     */
    void endRendering();

    /**
     * Binds graphics state for subsequent draw commands.
     *
     * <p>The state remains active until replaced by another graphics or compute
     * state. At draw time its declared attachment formats must match the active
     * render target.</p>
     *
     * @param state graphics state created by this encoder's device
     * @throws NullPointerException if {@code state} is {@code null}
     * @throws IllegalArgumentException if the state belongs to another device
     * @throws IllegalStateException if recording has finished
     */
    void setGraphicsState(GraphicsState state);

    /**
     * Binds compute state for subsequent dispatch commands.
     *
     * @param state compute state created by this encoder's device
     * @throws NullPointerException if {@code state} is {@code null}
     * @throws IllegalArgumentException if the state belongs to another device
     * @throws IllegalStateException if called inside a rendering scope or after
     *         recording has finished
     */
    void setComputeState(ComputeState state);

    /**
     * Binds a vertex buffer to one vertex-layout binding index.
     *
     * @param binding non-negative vertex binding index
     * @param buffer buffer created with vertex usage
     * @param offset byte offset of the first vertex/instance record
     * @throws IllegalArgumentException if the binding, usage, ownership, or range
     *         is invalid
     * @throws IllegalStateException if recording has finished
     */
    void setVertexBuffer(int binding, Buffer buffer, long offset);

    /**
     * Binds the index buffer used by subsequent indexed draw commands.
     *
     * @param buffer buffer created with index usage
     * @param indexType integer type stored in the index buffer
     * @param offset byte offset of the first index; must be aligned to the index
     *        type's byte width
     * @throws NullPointerException if {@code buffer} or {@code indexType} is null
     * @throws IllegalArgumentException if usage, ownership, offset, or alignment
     *         is invalid
     * @throws IllegalStateException if recording has finished
     */
    void setIndexBuffer(Buffer buffer, IndexType indexType, long offset);

    /**
     * Binds one resource-binding group for the currently active graphics or
     * compute state.
     *
     * <p>Group {@code 0} corresponds to the first binding layout supplied to the
     * active state descriptor, group {@code 1} to the second, and so on.</p>
     *
     * @param group zero-based binding-layout group index
     * @param set binding set whose layout must match the active state's group
     * @throws IllegalArgumentException if the group, layout, or ownership is invalid
     * @throws IllegalStateException if no graphics/compute state is active or
     *         recording has finished
     */
    void bindSet(int group, BindingSet set);

    /**
     * Records a non-indexed draw.
     *
     * @param vertexCount number of vertices per instance; must be positive
     * @param instanceCount number of instances; must be positive
     * @param firstVertex first vertex index; must be non-negative
     * @param firstInstance first instance index; must be non-negative
     * @throws IllegalStateException if no rendering scope or graphics state is active
     * @throws IllegalArgumentException if an argument is invalid
     */
    void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance);

    /**
     * Records an indexed draw using the currently bound index buffer.
     *
     * @param indexCount number of indices per instance; must be positive
     * @param instanceCount number of instances; must be positive
     * @param firstIndex first index within the bound index buffer; non-negative
     * @param vertexOffset signed base-vertex offset added to fetched indices
     * @param firstInstance first instance index; non-negative
     * @throws IllegalStateException if required rendering/graphics/index state is absent
     * @throws IllegalArgumentException if an argument is invalid
     */
    void drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance);

    /**
     * Records one or more indexed draws whose parameters are read from a GPU buffer.
     *
     * <p>The buffer must have indirect usage and be in
     * {@link ResourceState#INDIRECT_READ} before execution.</p>
     *
     * @param indirectBuffer buffer containing backend-neutral indexed-indirect
     *        command records
     * @param offset byte offset of the first command; must be 4-byte aligned
     * @param drawCount number of indirect draws; must be positive
     * @param stride byte stride between commands; must be positive and 4-byte aligned
     * @throws IllegalArgumentException if usage, ownership, or arguments are invalid
     * @throws IllegalStateException if required rendering/graphics state is absent
     */
    void drawIndexedIndirect(Buffer indirectBuffer, long offset, int drawCount, int stride);

    /**
     * Dispatches the active compute state.
     *
     * @param groupCountX positive X workgroup count
     * @param groupCountY positive Y workgroup count
     * @param groupCountZ positive Z workgroup count
     * @throws IllegalStateException if a rendering scope is active, no compute
     *         state is bound, or recording has finished
     * @throws IllegalArgumentException if any group count is non-positive
     */
    void dispatch(int groupCountX, int groupCountY, int groupCountZ);

    /**
     * Copies the complete contents of one texture into another texture.
     *
     * <p>Source and destination must have identical dimensions and formats. The
     * source must have copy-source usage and state; the destination must have
     * copy-destination usage and state. Copies are not allowed inside a graphics
     * rendering scope.</p>
     *
     * @param source source texture in {@link ResourceState#COPY_SRC}
     * @param destination destination texture in {@link ResourceState#COPY_DST}
     * @throws IllegalArgumentException if ownership, usage, dimensions, or formats
     *         are incompatible
     * @throws IllegalStateException if called inside a rendering scope or after finish
     */
    void copyTexture(Texture source, Texture destination);

    /**
     * Declares a texture state transition and the synchronization required to
     * make prior accesses visible to subsequent accesses.
     *
     * <p>Transitions are recorded outside graphics rendering scopes. With
     * validation enabled, {@code from} must match the device's currently tracked
     * state for the resource. A transition to {@link ResourceState#UNDEFINED} is
     * invalid because undefined is an initial/discard state, not a usable destination.</p>
     *
     * @param texture texture to transition
     * @param from expected state before the transition
     * @param to state required by subsequent work
     * @throws IllegalArgumentException if ownership, usage, or states are invalid
     * @throws IllegalStateException if called inside a rendering scope, after finish,
     *         or with validation enabled and {@code from} is not current
     */
    void transition(Texture texture, ResourceState from, ResourceState to);

    /**
     * Declares a buffer state transition and synchronization dependency.
     *
     * <p>Transitions are recorded outside graphics rendering scopes. With
     * validation enabled, {@code from} must match the device's currently tracked
     * state for the resource.</p>
     *
     * @param buffer buffer to transition
     * @param from expected state before the transition
     * @param to state required by subsequent work
     * @throws IllegalArgumentException if ownership, usage, or states are invalid
     * @throws IllegalStateException if called inside a rendering scope, after finish,
     *         or with validation enabled and {@code from} is not current
     */
    void transition(Buffer buffer, ResourceState from, ResourceState to);

    /**
     * Finishes recording and returns the resulting immutable command list.
     *
     * @return command list associated with this encoder's device
     * @throws IllegalStateException if a rendering scope is still active or this
     *         encoder was already finished
     */
    CommandList finish();
}
