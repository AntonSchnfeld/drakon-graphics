package io.github.antonschnfeld.drakon.graphics.vulkan;

import io.github.antonschnfeld.drakon.graphics.command.*;
import io.github.antonschnfeld.drakon.graphics.resource.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/** Real Vulkan command encoder used by the backend spike. */
final class VulkanCommandEncoder implements CommandEncoder {
    private final VulkanDevice device;
    private final VkCommandBuffer commandBuffer;
    private boolean rendering;
    private boolean finished;
    private VulkanGraphicsState graphicsState;
    private VulkanTarget renderTarget;
    private VulkanPresentationTarget presentationTarget;
    private VulkanPresentationState presentationState;
    private VulkanBuffer indexBuffer;
    private final Map<Integer, VulkanBuffer> vertexBuffers = new HashMap<>();
    private final VulkanRecordingState states = new VulkanRecordingState();
    private final Set<VulkanResource> resources = Collections.newSetFromMap(new IdentityHashMap<>());

    VulkanCommandEncoder(VulkanDevice device, VkCommandBuffer commandBuffer) {
        this.device = device;
        this.commandBuffer = commandBuffer;
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

            try (MemoryStack stack = stackPush()) {
                VkRenderingAttachmentInfo.Buffer colors = VkRenderingAttachmentInfo.calloc(target.colorFormats().size(), stack);
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
                    depth = VkRenderingAttachmentInfo.calloc(stack)
                            .sType$Default()
                            .imageView(target.depthView())
                            .imageLayout(VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL)
                            .loadOp(loadOp(ops.loadOp()))
                            .storeOp(storeOp(ops.storeOp()));
                    if (ops.loadOp() == LoadOp.CLEAR) depth.clearValue().depthStencil().depth(ops.clearDepth()).stencil(0);
                }

                VkRenderingInfo renderingInfo = VkRenderingInfo.calloc(stack)
                        .sType$Default()
                        .renderArea(a -> a.offset(o -> o.set(info.scissor().x(), info.scissor().y()))
                                .extent(e -> e.width(info.scissor().width()).height(info.scissor().height())))
                        .layerCount(1)
                        .pColorAttachments(colors);
                if (depth != null) renderingInfo.pDepthAttachment(depth);
                vkCmdBeginRendering(commandBuffer, renderingInfo);

                // Drakon defines viewport coordinates from the upper-left. A negative
                // Vulkan viewport height performs that Y inversion without changing
                // shader code. Paired with VK_FRONT_FACE_COUNTER_CLOCKWISE, this
                // preserves the portable counter-clockwise front-face contract.
                Viewport v = info.viewport();
                VkViewport.Buffer viewport = VkViewport.calloc(1, stack);
                viewport.get(0).x(v.x()).y(v.y() + v.height()).width(v.width()).height(-v.height()).minDepth(v.minDepth()).maxDepth(v.maxDepth());
                vkCmdSetViewport(commandBuffer, 0, viewport);
                VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
                scissor.get(0).offset(o -> o.set(info.scissor().x(), info.scissor().y()))
                        .extent(e -> e.width(info.scissor().width()).height(info.scissor().height()));
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
            vkCmdEndRendering(commandBuffer);
            renderTarget.finishRendering(commandBuffer, presentationState);
        } catch (RuntimeException | Error failure) {
            failRecording();
            throw failure;
        }
        rendering = false;
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
        ResourceState state = states.effectiveState(vkBuffer);
        if (state != ResourceState.UNDEFINED && state != ResourceState.VERTEX_READ) {
            throw new IllegalStateException("vertex buffer is not in VERTEX_READ");
        }
        reference(vkBuffer);
        recordCommand(() -> {
            try (MemoryStack stack = stackPush()) {
                vkCmdBindVertexBuffers(commandBuffer, binding, stack.longs(vkBuffer.handle), stack.longs(offset));
            }
        });
        vertexBuffers.put(binding, vkBuffer);
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
        ResourceState state = states.effectiveState(vkBuffer);
        if (state != ResourceState.UNDEFINED && state != ResourceState.INDEX_READ) {
            throw new IllegalStateException("index buffer is not in INDEX_READ");
        }
        reference(vkBuffer);
        recordCommand(() -> vkCmdBindIndexBuffer(
                commandBuffer, vkBuffer.handle, offset, VulkanMappings.indexType(indexType)));
        indexBuffer = vkBuffer;
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
        if (group >= graphicsState.setLayouts.size()) throw new IllegalArgumentException("binding group out of range");
        layout = graphicsState.pipelineLayout;
        expected = graphicsState.setLayouts.get(group).logical;
        if (bindingSet.layout() != expected) throw new IllegalArgumentException("binding set layout does not match active state group");
        reference(bindingSet);
        validateBindingStates(bindingSet);
        recordCommand(() -> {
            try (MemoryStack stack = stackPush()) {
                vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, layout, group,
                        stack.longs(bindingSet.descriptorSet), null);
            }
        });
    }

    private void validateBindingStates(VulkanBindingSet set) {
        for (Binding<?> binding : set.descriptor.layout().bindings()) {
            Object value = set.descriptor.values().get(binding);
            switch (binding.type()) {
                case SAMPLED_TEXTURE -> {
                    VulkanTexture texture = (VulkanTexture) ((TextureBinding) value).texture();
                    if (states.effectiveState(texture) != ResourceState.SAMPLED_READ) {
                        throw new IllegalStateException("sampled texture is not in SAMPLED_READ");
                    }
                }
                case UNIFORM_BUFFER -> {
                    VulkanBuffer buffer = (VulkanBuffer) ((BufferBinding) value).buffer();
                    if (states.effectiveState(buffer) != ResourceState.UNIFORM_READ) {
                        throw new IllegalStateException("uniform buffer is not in UNIFORM_READ");
                    }
                }
            }
        }
    }

    @Override
    public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        requireDraw(vertexCount, instanceCount, firstVertex, firstInstance);
        recordCommand(() -> vkCmdDraw(commandBuffer, vertexCount, instanceCount, firstVertex, firstInstance));
    }

    private void requireDraw(int count, int instances, int first, int firstInstance) {
        requireRecording();
        if (!rendering || graphicsState == null) throw new IllegalStateException("draw requires rendering and graphics state");
        validateTargetCompatibility(graphicsState, renderTarget);
        if (count <= 0 || instances <= 0 || first < 0 || firstInstance < 0) throw new IllegalArgumentException("invalid draw arguments");
    }

    @Override
    public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance) {
        requireDraw(indexCount, instanceCount, firstIndex, firstInstance);
        if (indexBuffer == null) throw new IllegalStateException("no index buffer bound");
        recordCommand(() -> vkCmdDrawIndexed(
                commandBuffer, indexCount, instanceCount, firstIndex, vertexOffset, firstInstance));
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
            try (MemoryStack stack = stackPush()) {
                VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
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
    public void transition(Texture texture, ResourceState from, ResourceState to) {
        Objects.requireNonNull(texture, "texture");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        requireRecording();
        if (rendering) throw new IllegalStateException("transitions are not allowed inside rendering");
        VulkanTexture vkTexture = device.owned(texture, VulkanTexture.class, "texture");
        validateTextureState(vkTexture, from);
        validateTextureState(vkTexture, to);
        validateTransition(states.effectiveState(vkTexture), from, to);
        reference(vkTexture);
        recordCommand(() -> {
            try (MemoryStack stack = stackPush()) {
                VkImageMemoryBarrier2.Buffer barrier = VkImageMemoryBarrier2.calloc(1, stack);
                barrier.get(0)
                        .sType$Default()
                        .srcStageMask(VulkanMappings.stageMask(from))
                        .srcAccessMask(VulkanMappings.accessMask(from))
                        .dstStageMask(VulkanMappings.stageMask(to))
                        .dstAccessMask(VulkanMappings.accessMask(to))
                        .oldLayout(VulkanMappings.imageLayout(from))
                        .newLayout(VulkanMappings.imageLayout(to))
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .image(vkTexture.image)
                        .subresourceRange(r -> r.aspectMask(VulkanMappings.imageAspect(vkTexture.format()))
                                .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
                VkDependencyInfo dependency = VkDependencyInfo.calloc(stack)
                        .sType$Default()
                        .pImageMemoryBarriers(barrier);
                vkCmdPipelineBarrier2(commandBuffer, dependency);
            }
        });
        states.transition(vkTexture, from, to);
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
        validateTransition(states.effectiveState(vkBuffer), from, to);
        reference(vkBuffer);
        recordCommand(() -> {
            try (MemoryStack stack = stackPush()) {
                VkBufferMemoryBarrier2.Buffer barrier = VkBufferMemoryBarrier2.calloc(1, stack);
                barrier.get(0)
                        .sType$Default()
                        .srcStageMask(VulkanMappings.stageMask(from))
                        .srcAccessMask(VulkanMappings.accessMask(from))
                        .dstStageMask(VulkanMappings.stageMask(to))
                        .dstAccessMask(VulkanMappings.accessMask(to))
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .buffer(vkBuffer.handle)
                        .offset(0)
                        .size(VK_WHOLE_SIZE);
                VkDependencyInfo dependency = VkDependencyInfo.calloc(stack)
                        .sType$Default()
                        .pBufferMemoryBarriers(barrier);
                vkCmdPipelineBarrier2(commandBuffer, dependency);
            }
        });
        states.transition(vkBuffer, from, to);
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

    private void validateTransition(ResourceState actual, ResourceState from, ResourceState to) {
        if (to == ResourceState.UNDEFINED) throw new IllegalArgumentException("cannot transition to UNDEFINED");
        if (actual != from) {
            // The core config currently does not expose validation() through the
            // interface, but this backend always enforces explicit state truth in
            // the spike because silent state mismatches invalidate barrier tests.
            throw new IllegalStateException("resource state is " + actual + " but transition expected " + from);
        }
    }

    @Override
    public CommandList finish() {
        requireRecording();
        if (rendering) throw new IllegalStateException("cannot finish while rendering is active");
        try {
            VulkanDevice.check(vkEndCommandBuffer(commandBuffer), "vkEndCommandBuffer");
            VulkanCommandState commandState = states.finish();
            VulkanPresentationState commandPresentationState = presentationState;
            List<VulkanResource> commandResources = new ArrayList<>(resources);
            finished = true;
            VulkanCommandList result = new VulkanCommandList(
                    device,
                    commandBuffer,
                    commandPresentationState,
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
        graphicsState = null;
        renderTarget = null;
        presentationTarget = null;
        presentationState = null;
        indexBuffer = null;
    }

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
