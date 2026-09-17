package io.github.antonschnfeld.drakon.graphics.vulkan;

import io.github.antonschnfeld.drakon.graphics.command.*;
import io.github.antonschnfeld.drakon.graphics.resource.*;
import org.lwjgl.vulkan.*;

import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/** Real Vulkan command encoder used by the backend spike. */
final class VulkanCommandEncoder implements CommandEncoder {
    private final VulkanDevice device;
    private final VkCommandBuffer commandBuffer;
    private final boolean validation;
    private boolean rendering;
    private boolean nativeRendering;
    private boolean presentationRenderingSkipped;
    private boolean finished;
    private VulkanGraphicsState graphicsState;
    private VulkanTarget renderTarget;
    private VulkanPresentationTarget presentationTarget;
    private VulkanPresentationState presentationState;
    private VulkanBuffer indexBuffer;
    private IndexType indexType;
    private long indexOffset;
    private final Map<Integer, VertexBufferBinding> vertexBuffers = new HashMap<>();
    private final Map<Integer, VulkanBindingSet> bindingSets = new HashMap<>();
    private final VulkanRecordingState states = new VulkanRecordingState();
    private final Set<VulkanResource> resources = Collections.newSetFromMap(new IdentityHashMap<>());

    VulkanCommandEncoder(VulkanDevice device, VkCommandBuffer commandBuffer, boolean validation) {
        this.device = device;
        this.commandBuffer = commandBuffer;
        this.validation = validation;
    }

    private void requireRecording() {
        device.requireOpen();
        if (finished) throw new IllegalStateException("command encoder is finished");
    }

    @Override
    public void beginRendering(RenderingInfo info) {
        Objects.requireNonNull(info, "info");
        requireRecording();
        if (rendering) throw new IllegalStateException("rendering scope already active");
        VulkanTarget target = device.owned(info.target(), VulkanTarget.class, "render target");
        reference(target);
        target.validateWritableStates(states);
        if (target instanceof VulkanPresentationTarget presentation) {
            if (presentationTarget != null && presentationTarget != presentation) {
                throw new IllegalStateException("one command list cannot render to multiple presentation targets");
            }
            presentationTarget = presentation;
        }
        try {
            presentationState = target.prepareForRendering(commandBuffer, presentationState);
            presentationRenderingSkipped = target instanceof VulkanPresentationTarget
                    && presentationState == null;
            VulkanValidation.ClippedScissor effective = VulkanValidation.clipScissor(
                    info.scissor(), target.width(), target.height());
            nativeRendering = !presentationRenderingSkipped && !effective.empty();

            if (nativeRendering) try (Arena arena = Arena.ofConfined()) {
                VkRenderingAttachmentInfo.Buffer colors = VulkanFfm.structBuffer(arena, VkRenderingAttachmentInfo.SIZEOF, VkRenderingAttachmentInfo.ALIGNOF, target.colorFormats().size(), VkRenderingAttachmentInfo::create);
                for (int i = 0; i < target.colorFormats().size(); i++) {
                    ColorAttachmentOps ops = info.colors().get(i);
                    VkRenderingAttachmentInfo attachment = colors.get(i)
                            .sType$Default()
                            .imageView(target.colorView(i))
                            .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                            .loadOp(loadOp(ops.loadOp()))
                            .storeOp(storeOp(ops.storeOp()));
                    if (ops.loadOp() == LoadOp.CLEAR) {
                        Color c = ops.clearColor();
                        attachment.clearValue().color().float32(0, c.r()).float32(1, c.g()).float32(2, c.b()).float32(3, c.a());
                    }
                }

                VkRenderingAttachmentInfo depth = null;
                if (target.depthFormat() != null) {
                    DepthAttachmentOps ops = info.depth().orElseThrow();
                    depth = VulkanFfm.struct(arena, VkRenderingAttachmentInfo.SIZEOF, VkRenderingAttachmentInfo.ALIGNOF, VkRenderingAttachmentInfo::create)
                            .sType$Default()
                            .imageView(target.depthView())
                            .imageLayout(VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL)
                            .loadOp(loadOp(ops.loadOp()))
                            .storeOp(storeOp(ops.storeOp()));
                    if (ops.loadOp() == LoadOp.CLEAR) depth.clearValue().depthStencil().depth(ops.clearDepth()).stencil(0);
                }

                VkRenderingInfo renderingInfo = VulkanFfm.struct(arena, VkRenderingInfo.SIZEOF, VkRenderingInfo.ALIGNOF, VkRenderingInfo::create)
                        .sType$Default()
                        .renderArea(a -> a.offset(o -> o.set(effective.x(), effective.y()))
                                .extent(e -> e.width(effective.width()).height(effective.height())))
                        .layerCount(1)
                        .pColorAttachments(colors);
                if (depth != null) renderingInfo.pDepthAttachment(depth);
                vkCmdBeginRendering(commandBuffer, renderingInfo);

                // Drakon defines viewport coordinates from the upper-left. A negative
                // Vulkan viewport height performs that Y inversion without changing
                // shader code. Paired with VK_FRONT_FACE_COUNTER_CLOCKWISE, this
                // preserves the portable counter-clockwise front-face contract.
                Viewport v = info.viewport();
                VkViewport.Buffer viewport = VulkanFfm.structBuffer(arena, VkViewport.SIZEOF, VkViewport.ALIGNOF, 1, VkViewport::create);
                viewport.get(0).x(v.x()).y(v.y() + v.height()).width(v.width()).height(-v.height()).minDepth(v.minDepth()).maxDepth(v.maxDepth());
                vkCmdSetViewport(commandBuffer, 0, viewport);
                VkRect2D.Buffer scissor = VulkanFfm.structBuffer(arena, VkRect2D.SIZEOF, VkRect2D.ALIGNOF, 1, VkRect2D::create);
                scissor.get(0).offset(o -> o.set(effective.x(), effective.y()))
                        .extent(e -> e.width(effective.width()).height(effective.height()));
                vkCmdSetScissor(commandBuffer, 0, scissor);
            }
        } catch (RuntimeException | Error failure) {
            failRecording();
            throw failure;
        }

        rendering = true;
        renderTarget = target;
    }

    @Override
    public void endRendering() {
        requireRecording();
        if (!rendering) throw new IllegalStateException("no rendering scope is active");
        try {
            renderTarget.requireAlive();
            for (VulkanResource dependency : renderTarget.dependencies()) dependency.requireAlive();
            if (nativeRendering) vkCmdEndRendering(commandBuffer);
            if (!presentationRenderingSkipped) {
                renderTarget.finishRendering(commandBuffer, presentationState);
            }
        } catch (RuntimeException | Error failure) {
            failRecording();
            throw failure;
        }
        rendering = false;
        nativeRendering = false;
        presentationRenderingSkipped = false;
        renderTarget = null;
    }

    @Override
    public void setGraphicsState(GraphicsState state) {
        Objects.requireNonNull(state, "state");
        requireRecording();
        VulkanGraphicsState next = device.owned(state, VulkanGraphicsState.class, "graphics state");
        if (rendering) validateTargetCompatibility(next, renderTarget);
        reference(next);
        recordCommand(() -> vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, next.pipeline));
        graphicsState = next;
    }

    private static void validateTargetCompatibility(VulkanGraphicsState state, VulkanTarget target) {
        if (!state.descriptor.colorFormats().equals(target.colorFormats())) {
            throw new IllegalStateException("graphics state color formats do not match active render target");
        }
        TextureFormat expectedDepth = state.descriptor.depthFormat().orElse(null);
        if (expectedDepth != target.depthFormat()) {
            throw new IllegalStateException("graphics state depth format does not match active render target");
        }
    }

    @Override
    public void setVertexBuffer(int binding, Buffer buffer, long offset) {
        Objects.requireNonNull(buffer, "buffer");
        requireRecording();
        if (binding < 0 || offset < 0) throw new IllegalArgumentException("binding and offset must be non-negative");
        VulkanBuffer vkBuffer = device.owned(buffer, VulkanBuffer.class, "vertex buffer");
        if (!vkBuffer.usage().contains(BufferUsage.VERTEX)) throw new IllegalArgumentException("buffer lacks VERTEX usage");
        if (offset >= vkBuffer.size()) throw new IllegalArgumentException("vertex buffer offset outside buffer");
        reference(vkBuffer);
        recordCommand(() -> {
            try (Arena arena = Arena.ofConfined()) {
                vkCmdBindVertexBuffers(commandBuffer, binding, VulkanFfm.longs(arena, vkBuffer.handle), VulkanFfm.longs(arena, offset));
            }
        });
        vertexBuffers.put(binding, new VertexBufferBinding(vkBuffer, offset));
    }

    @Override
    public void setIndexBuffer(Buffer buffer, IndexType indexType, long offset) {
        Objects.requireNonNull(buffer, "buffer");
        Objects.requireNonNull(indexType, "indexType");
        requireRecording();
        VulkanBuffer vkBuffer = device.owned(buffer, VulkanBuffer.class, "index buffer");
        if (!vkBuffer.usage().contains(BufferUsage.INDEX)) throw new IllegalArgumentException("buffer lacks INDEX usage");
        if (offset < 0 || offset >= vkBuffer.size() || offset % indexType.bytes() != 0) {
            throw new IllegalArgumentException("invalid index-buffer offset");
        }
        reference(vkBuffer);
        recordCommand(() -> vkCmdBindIndexBuffer(
                commandBuffer, vkBuffer.handle, offset, VulkanMappings.indexType(indexType)));
        indexBuffer = vkBuffer;
        this.indexType = indexType;
        indexOffset = offset;
    }

    @Override
    public void bindSet(int group, BindingSet set) {
        Objects.requireNonNull(set, "set");
        requireRecording();
        if (group < 0) throw new IllegalArgumentException("group must be non-negative");
        VulkanBindingSet bindingSet = device.owned(set, VulkanBindingSet.class, "binding set");
        long layout;
        BindingLayout expected;
        if (graphicsState == null) throw new IllegalStateException("bindSet requires active graphics state");
        graphicsState.requireAlive();
        if (group >= graphicsState.setLayouts.size()) throw new IllegalArgumentException("binding group out of range");
        layout = graphicsState.pipelineLayout;
        expected = graphicsState.setLayouts.get(group).logical;
        if (bindingSet.layout() != expected) throw new IllegalArgumentException("binding set layout does not match active state group");
        reference(bindingSet);
        recordCommand(() -> {
            try (Arena arena = Arena.ofConfined()) {
                vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, layout, group,
                        VulkanFfm.longs(arena, bindingSet.descriptorSet), null);
            }
        });
        bindingSets.put(group, bindingSet);
    }

    private void validateBindingStates(VulkanBindingSet set) {
        set.requireAlive();
        for (Binding<?> binding : set.descriptor.layout().bindings()) {
            Object value = set.descriptor.values().get(binding);
            switch (binding.type()) {
                case SAMPLED_TEXTURE -> {
                    TextureBinding sampled = (TextureBinding) value;
                    VulkanTexture texture = (VulkanTexture) sampled.texture();
                    VulkanSampler sampler = (VulkanSampler) sampled.sampler();
                    texture.requireAlive();
                    sampler.requireAlive();
                    if (states.effectiveState(texture) != ResourceState.SAMPLED_READ) {
                        throw new IllegalStateException("sampled texture is not in SAMPLED_READ");
                    }
                }
                case UNIFORM_BUFFER -> {
                    VulkanBuffer buffer = (VulkanBuffer) ((BufferBinding) value).buffer();
                    buffer.requireAlive();
                    if (states.effectiveState(buffer) != ResourceState.UNIFORM_READ) {
                        throw new IllegalStateException("uniform buffer is not in UNIFORM_READ");
                    }
                }
            }
        }
    }

    private void validateRequiredBindingSets() {
        for (int group = 0; group < graphicsState.setLayouts.size(); group++) {
            VulkanBindingSet set = bindingSets.get(group);
            if (set == null || set.layout() != graphicsState.setLayouts.get(group).logical) {
                throw new IllegalStateException("missing compatible binding set for group " + group);
            }
            validateBindingStates(set);
        }
    }

    private void rebindRequiredBindingSets() {
        int setCount = graphicsState.setLayouts.size();
        if (setCount == 0) return;
        recordCommand(() -> {
            try (Arena arena = Arena.ofConfined()) {
                var descriptorSets = VulkanFfm.longs(arena, setCount);
                for (int group = 0; group < setCount; group++) {
                    descriptorSets.put(group, bindingSets.get(group).descriptorSet);
                }
                vkCmdBindDescriptorSets(
                        commandBuffer,
                        VK_PIPELINE_BIND_POINT_GRAPHICS,
                        graphicsState.pipelineLayout,
                        0,
                        descriptorSets,
                        null);
            }
        });
    }

    private void validateVertexBuffers(
            boolean indexed,
            int count,
            int instances,
            int first,
            int firstInstance) {
        VertexLayout layout = graphicsState.descriptor.vertexLayout();
        for (VertexBinding binding : layout.bindings()) {
            VertexBufferBinding value = vertexBuffers.get(binding.binding());
            if (value == null) {
                throw new IllegalStateException("missing vertex buffer binding " + binding.binding());
            }
            value.buffer().requireAlive();
            if (states.effectiveState(value.buffer()) != ResourceState.VERTEX_READ) {
                throw new IllegalStateException("vertex buffer requires VERTEX_READ");
            }
            VulkanValidation.validateVertexRange(
                    value.buffer().size(),
                    value.offset(),
                    binding,
                    layout.attributes(),
                    indexed,
                    indexed ? 1 : count,
                    instances,
                    indexed ? 0 : first,
                    firstInstance);
        }
    }

    @Override
    public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        requireDraw(false, vertexCount, instanceCount, firstVertex, firstInstance);
        rebindRequiredBindingSets();
        if (nativeRendering) {
            recordCommand(() -> vkCmdDraw(commandBuffer, vertexCount, instanceCount, firstVertex, firstInstance));
        }
    }

    private void requireDraw(boolean indexed, int count, int instances, int first, int firstInstance) {
        requireRecording();
        if (!rendering || graphicsState == null) throw new IllegalStateException("draw requires rendering and graphics state");
        graphicsState.requireAlive();
        renderTarget.requireAlive();
        for (VulkanResource dependency : renderTarget.dependencies()) dependency.requireAlive();
        validateTargetCompatibility(graphicsState, renderTarget);
        if (count <= 0 || instances <= 0 || first < 0 || firstInstance < 0) throw new IllegalArgumentException("invalid draw arguments");
        validateRequiredBindingSets();
        validateVertexBuffers(indexed, count, instances, first, firstInstance);
    }

    @Override
    public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance) {
        requireDraw(true, indexCount, instanceCount, firstIndex, firstInstance);
        if (indexBuffer == null) throw new IllegalStateException("no index buffer bound");
        indexBuffer.requireAlive();
        if (states.effectiveState(indexBuffer) != ResourceState.INDEX_READ) {
            throw new IllegalStateException("index buffer requires INDEX_READ");
        }
        VulkanValidation.validateIndexRange(
                indexBuffer.size(), indexOffset, indexType, firstIndex, indexCount);
        rebindRequiredBindingSets();
        if (nativeRendering) {
            recordCommand(() -> vkCmdDrawIndexed(
                    commandBuffer, indexCount, instanceCount, firstIndex, vertexOffset, firstInstance));
        }
    }

    @Override
    public void copyTexture(Texture source, Texture destination) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "destination");
        requireRecording();
        if (rendering) throw new IllegalStateException("texture copy is not allowed inside rendering");
        VulkanTexture src = device.owned(source, VulkanTexture.class, "source texture");
        VulkanTexture dst = device.owned(destination, VulkanTexture.class, "destination texture");
        if (!src.usage().contains(TextureUsage.COPY_SRC) || !dst.usage().contains(TextureUsage.COPY_DST)) {
            throw new IllegalArgumentException("copy usages are missing");
        }
        if (src.width() != dst.width() || src.height() != dst.height() || src.format() != dst.format()) {
            throw new IllegalArgumentException("whole-texture copy requires matching dimensions and format");
        }
        if (states.effectiveState(src) != ResourceState.COPY_SRC
                || states.effectiveState(dst) != ResourceState.COPY_DST) {
            throw new IllegalStateException("copy textures are not in COPY_SRC/COPY_DST");
        }
        reference(src);
        reference(dst);
        recordCommand(() -> {
            try (Arena arena = Arena.ofConfined()) {
                VkImageCopy.Buffer region = VulkanFfm.structBuffer(arena, VkImageCopy.SIZEOF, VkImageCopy.ALIGNOF, 1, VkImageCopy::create);
                region.get(0)
                        .srcSubresource(s -> s.aspectMask(VulkanMappings.imageAspect(src.format())).mipLevel(0).baseArrayLayer(0).layerCount(1))
                        .dstSubresource(s -> s.aspectMask(VulkanMappings.imageAspect(dst.format())).mipLevel(0).baseArrayLayer(0).layerCount(1))
                        .extent(e -> e.width(src.width()).height(src.height()).depth(1));
                vkCmdCopyImage(commandBuffer,
                        src.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        dst.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        region);
            }
        });
    }

    @Override
    public void writeBuffer(Buffer buffer, long offset, ByteBuffer data) {
        Objects.requireNonNull(buffer, "buffer");
        Objects.requireNonNull(data, "data");
        requireRecording();
        if (rendering) throw new IllegalStateException("writeBuffer is not allowed inside rendering");
        VulkanBuffer destination = device.owned(buffer, VulkanBuffer.class, "buffer");
        int byteCount = data.remaining();
        VulkanBufferUpdates.validateRange(destination.size(), offset, byteCount);
        ResourceState state = states.effectiveState(destination);
        requireWritableBufferState(state);
        reference(destination);

        ByteBuffer selected = data.slice();
        recordCommand(() -> {
            recordBufferWriteBarrier(
                    destination, offset, byteCount,
                    VulkanMappings.stageMask(state), VulkanMappings.accessMask(state),
                    VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT);
            VulkanBufferUpdates.forEachChunk(offset, byteCount, (chunkOffset, chunkSize) -> {
                int sourceOffset = Math.toIntExact(chunkOffset - offset);
                ByteBuffer chunk = selected.duplicate();
                chunk.position(sourceOffset).limit(sourceOffset + chunkSize);
                try (Arena arena = Arena.ofConfined()) {
                    vkCmdUpdateBuffer(
                            commandBuffer,
                            destination.handle,
                            chunkOffset,
                            VulkanFfm.nativeCopy(arena, chunk, Integer.BYTES));
                }
            });
            recordBufferWriteBarrier(
                    destination, offset, byteCount,
                    VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                    VulkanMappings.stageMask(state), VulkanMappings.accessMask(state));
        });
    }

    private void recordBufferWriteBarrier(
            VulkanBuffer buffer,
            long offset,
            long size,
            long sourceStage,
            long sourceAccess,
            long destinationStage,
            long destinationAccess) {
        try (Arena arena = Arena.ofConfined()) {
            VkBufferMemoryBarrier2.Buffer barrier = VulkanFfm.structBuffer(
                    arena,
                    VkBufferMemoryBarrier2.SIZEOF,
                    VkBufferMemoryBarrier2.ALIGNOF,
                    1,
                    VkBufferMemoryBarrier2::create);
            barrier.get(0)
                    .sType$Default()
                    .srcStageMask(sourceStage)
                    .srcAccessMask(sourceAccess)
                    .dstStageMask(destinationStage)
                    .dstAccessMask(destinationAccess)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .buffer(buffer.handle)
                    .offset(offset)
                    .size(size);
            VkDependencyInfo dependency = VulkanFfm.struct(
                    arena,
                    VkDependencyInfo.SIZEOF,
                    VkDependencyInfo.ALIGNOF,
                    VkDependencyInfo::create)
                    .sType$Default()
                    .pBufferMemoryBarriers(barrier);
            vkCmdPipelineBarrier2(commandBuffer, dependency);
        }
    }

    private static void requireWritableBufferState(ResourceState state) {
        if (state != ResourceState.VERTEX_READ
                && state != ResourceState.INDEX_READ
                && state != ResourceState.UNIFORM_READ) {
            throw new IllegalStateException(
                    "buffer write requires VERTEX_READ, INDEX_READ, or UNIFORM_READ");
        }
    }

    @Override
    public void transition(Texture texture, ResourceState from, ResourceState to) {
        Objects.requireNonNull(texture, "texture");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        requireRecording();
        if (rendering) throw new IllegalStateException("transitions are not allowed inside rendering");
        VulkanTexture vkTexture = device.owned(texture, VulkanTexture.class, "texture");
        validateTextureState(vkTexture, from);
        validateTextureState(vkTexture, to);
        ResourceState actual = states.effectiveState(vkTexture);
        validateTransition(actual, from, to, "texture");
        reference(vkTexture);
        recordCommand(() -> {
            try (Arena arena = Arena.ofConfined()) {
                VkImageMemoryBarrier2.Buffer barrier = VulkanFfm.structBuffer(arena, VkImageMemoryBarrier2.SIZEOF, VkImageMemoryBarrier2.ALIGNOF, 1, VkImageMemoryBarrier2::create);
                barrier.get(0)
                        .sType$Default()
                        .srcStageMask(VulkanMappings.stageMask(actual))
                        .srcAccessMask(VulkanMappings.accessMask(actual))
                        .dstStageMask(VulkanMappings.stageMask(to))
                        .dstAccessMask(VulkanMappings.accessMask(to))
                        .oldLayout(VulkanMappings.imageLayout(actual))
                        .newLayout(VulkanMappings.imageLayout(to))
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .image(vkTexture.image)
                        .subresourceRange(r -> r.aspectMask(VulkanMappings.imageAspect(vkTexture.format()))
                                .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
                VkDependencyInfo dependency = VulkanFfm.struct(arena, VkDependencyInfo.SIZEOF, VkDependencyInfo.ALIGNOF, VkDependencyInfo::create)
                        .sType$Default()
                        .pImageMemoryBarriers(barrier);
                vkCmdPipelineBarrier2(commandBuffer, dependency);
            }
        });
        states.transition(vkTexture, to);
    }

    @Override
    public void transition(Buffer buffer, ResourceState from, ResourceState to) {
        Objects.requireNonNull(buffer, "buffer");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        requireRecording();
        if (rendering) throw new IllegalStateException("transitions are not allowed inside rendering");
        VulkanBuffer vkBuffer = device.owned(buffer, VulkanBuffer.class, "buffer");
        validateBufferState(vkBuffer, from);
        validateBufferState(vkBuffer, to);
        ResourceState actual = states.effectiveState(vkBuffer);
        validateTransition(actual, from, to, "buffer");
        reference(vkBuffer);
        recordCommand(() -> {
            try (Arena arena = Arena.ofConfined()) {
                VkBufferMemoryBarrier2.Buffer barrier = VulkanFfm.structBuffer(arena, VkBufferMemoryBarrier2.SIZEOF, VkBufferMemoryBarrier2.ALIGNOF, 1, VkBufferMemoryBarrier2::create);
                barrier.get(0)
                        .sType$Default()
                        .srcStageMask(VulkanMappings.stageMask(actual))
                        .srcAccessMask(VulkanMappings.accessMask(actual))
                        .dstStageMask(VulkanMappings.stageMask(to))
                        .dstAccessMask(VulkanMappings.accessMask(to))
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .buffer(vkBuffer.handle)
                        .offset(0)
                        .size(VK_WHOLE_SIZE);
                VkDependencyInfo dependency = VulkanFfm.struct(arena, VkDependencyInfo.SIZEOF, VkDependencyInfo.ALIGNOF, VkDependencyInfo::create)
                        .sType$Default()
                        .pBufferMemoryBarriers(barrier);
                vkCmdPipelineBarrier2(commandBuffer, dependency);
            }
        });
        states.transition(vkBuffer, to);
    }

    private static void validateTextureState(VulkanTexture texture, ResourceState state) {
        boolean valid = switch (state) {
            case UNDEFINED -> true;
            case COLOR_ATTACHMENT_WRITE -> texture.usage().contains(TextureUsage.COLOR_ATTACHMENT);
            case DEPTH_ATTACHMENT_WRITE -> texture.usage().contains(TextureUsage.DEPTH_ATTACHMENT);
            case SAMPLED_READ -> texture.usage().contains(TextureUsage.SAMPLED);
            case COPY_SRC -> texture.usage().contains(TextureUsage.COPY_SRC);
            case COPY_DST -> texture.usage().contains(TextureUsage.COPY_DST);
            default -> false;
        };
        if (!valid) throw new IllegalArgumentException(state + " is not valid for this texture");
    }

    private static void validateBufferState(VulkanBuffer buffer, ResourceState state) {
        boolean valid = switch (state) {
            case UNDEFINED -> true;
            case UNIFORM_READ -> buffer.usage().contains(BufferUsage.UNIFORM);
            case VERTEX_READ -> buffer.usage().contains(BufferUsage.VERTEX);
            case INDEX_READ -> buffer.usage().contains(BufferUsage.INDEX);
            default -> false;
        };
        if (!valid) throw new IllegalArgumentException(state + " is not valid for this buffer");
    }

    private void validateTransition(
            ResourceState actual, ResourceState from, ResourceState to, String resource) {
        if (to == ResourceState.UNDEFINED) throw new IllegalArgumentException("cannot transition to UNDEFINED");
        VulkanValidation.validateTransitionFrom(validation, actual, from, resource);
    }

    @Override
    public CommandList finish() {
        requireRecording();
        if (rendering) throw new IllegalStateException("cannot finish while rendering is active");
        try {
            VulkanDevice.check(vkEndCommandBuffer(commandBuffer), "vkEndCommandBuffer");
            VulkanCommandState commandState = states.finish();
            VulkanPresentationState commandPresentationState = presentationState;
            VulkanPresentationTarget skippedPresentationTarget = commandPresentationState == null
                    ? presentationTarget : null;
            List<VulkanResource> commandResources = new ArrayList<>(resources);
            finished = true;
            VulkanCommandList result = new VulkanCommandList(
                    device,
                    commandBuffer,
                    commandPresentationState,
                    skippedPresentationTarget,
                    commandState,
                    commandResources);
            clearCapturedState();
            return result;
        } catch (RuntimeException | Error failure) {
            finished = true;
            device.freeCommandBuffer(commandBuffer);
            clearCapturedState();
            throw failure;
        }
    }

    @Override
    public void close() {
        if (finished) return;
        finished = true;
        device.freeCommandBufferIfOpen(commandBuffer);
        clearCapturedState();
    }

    private void reference(VulkanResource resource) {
        resource.requireAlive();
        if (!resources.add(resource)) return;
        for (VulkanResource dependency : resource.dependencies()) reference(dependency);
    }

    private void recordCommand(Runnable operation) {
        try {
            operation.run();
        } catch (RuntimeException | Error failure) {
            failRecording();
            throw failure;
        }
    }

    private void failRecording() {
        if (finished) return;
        finished = true;
        device.freeCommandBufferIfOpen(commandBuffer);
        clearCapturedState();
    }

    private void clearCapturedState() {
        states.clear();
        resources.clear();
        vertexBuffers.clear();
        bindingSets.clear();
        graphicsState = null;
        renderTarget = null;
        presentationTarget = null;
        presentationState = null;
        indexBuffer = null;
        indexType = null;
        indexOffset = 0L;
        nativeRendering = false;
        presentationRenderingSkipped = false;
    }

    private record VertexBufferBinding(VulkanBuffer buffer, long offset) {}

    private static int loadOp(LoadOp op) {
        return switch (op) {
            case LOAD -> VK_ATTACHMENT_LOAD_OP_LOAD;
            case CLEAR -> VK_ATTACHMENT_LOAD_OP_CLEAR;
            case DONT_CARE -> VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        };
    }

    private static int storeOp(StoreOp op) {
        return switch (op) {
            case STORE -> VK_ATTACHMENT_STORE_OP_STORE;
            case DONT_CARE -> VK_ATTACHMENT_STORE_OP_DONT_CARE;
        };
    }
}
