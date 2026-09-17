package io.github.antonschnfeld.drakon.graphics.opengl;

import io.github.antonschnfeld.drakon.graphics.command.*;
import io.github.antonschnfeld.drakon.graphics.resource.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL15C.*;
import static org.lwjgl.opengl.GL20C.glUseProgram;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL31C.*;
import static org.lwjgl.opengl.GL42C.*;
import static org.lwjgl.opengl.GL43C.*;

/** Records portable commands and replays them against one OpenGL context on submit. */
final class OpenGLCommandEncoder implements CommandEncoder {
    private final OpenGLDevice device;
    private final boolean validation;
    private final List<OpenGLCommand> commands = new ArrayList<>();
    private boolean rendering;
    private boolean finished;
    private OpenGLGraphicsState graphicsState;

    OpenGLCommandEncoder(OpenGLDevice device, boolean validation) {
        this.device = device;
        this.validation = validation;
    }

    private void requireRecording() {
        if (finished) throw new IllegalStateException("command encoder is finished");
        device.requireOpen();
    }

    private <T extends OpenGLResource> T owned(Object resource, Class<T> type, String label) {
        if (!type.isInstance(resource)) throw new IllegalArgumentException(label + " is not an OpenGL backend resource");
        T result = type.cast(resource);
        if (result.device != device) throw new IllegalArgumentException(label + " belongs to another device");
        result.requireAlive();
        return result;
    }

    @Override
    public void beginRendering(RenderingInfo info) {
        requireRecording();
        Objects.requireNonNull(info, "info");
        if (rendering) throw new IllegalStateException("rendering scope is already active");
        OpenGLRenderTargetAccess target = device.ownedTarget(info.target(), "render target");
        rendering = true;
        commands.add(context -> beginRenderingNow(context, target, info));
    }

    private static void validateAttachmentStates(OpenGLRenderTargetAccess target) {
        if (!(target instanceof OpenGLRenderTarget internal)) return;
        internal.requireAttachmentsAlive();
        for (OpenGLTexture color : internal.colors()) {
            if (color.state != ResourceState.COLOR_ATTACHMENT_WRITE) {
                throw new IllegalStateException("color attachment is not in COLOR_ATTACHMENT_WRITE");
            }
        }
        if (internal.depth() != null && internal.depth().state != ResourceState.DEPTH_ATTACHMENT_WRITE) {
            throw new IllegalStateException("depth attachment is not in DEPTH_ATTACHMENT_WRITE");
        }
    }

    private static void beginRenderingNow(
            OpenGLExecutionContext context,
            OpenGLRenderTargetAccess target,
            RenderingInfo info) {
        validateAttachmentStates(target);
        int targetWidth = target.width();
        int targetHeight = target.height();
        OpenGLValidation.ClippedScissor effective = OpenGLValidation.clipScissor(
                info.scissor(), targetWidth, targetHeight);
        context.renderTarget = target;
        context.renderingInfo = info;
        glBindFramebuffer(GL_FRAMEBUFFER, target.framebuffer());

        Viewport viewport = info.viewport();
        int viewportY = Math.round(targetHeight - (viewport.y() + viewport.height()));
        glViewport(Math.round(viewport.x()), viewportY, Math.round(viewport.width()), Math.round(viewport.height()));
        glDepthRange(viewport.minDepth(), viewport.maxDepth());

        int scissorY = targetHeight - (effective.y() + effective.height());
        glEnable(GL_SCISSOR_TEST);
        glScissor(effective.x(), scissorY, effective.width(), effective.height());

        List<Integer> invalidateAtStart = new ArrayList<>();
        for (int i = 0; i < info.colors().size(); i++) {
            ColorAttachmentOps ops = info.colors().get(i);
            if (ops.loadOp() == LoadOp.CLEAR) {
                Color c = ops.clearColor();
                glClearBufferfv(GL_COLOR, i, new float[]{c.r(), c.g(), c.b(), c.a()});
            } else if (ops.loadOp() == LoadOp.DONT_CARE) {
                invalidateAtStart.add(GL_COLOR_ATTACHMENT0 + i);
            }
        }
        info.depth().ifPresent(ops -> {
            if (ops.loadOp() == LoadOp.CLEAR) {
                clearDepth(ops);
            } else if (ops.loadOp() == LoadOp.DONT_CARE) {
                invalidateAtStart.add(GL_DEPTH_ATTACHMENT);
            }
        });
        invalidate(target, invalidateAtStart);
    }

    private static void clearDepth(DepthAttachmentOps ops) {
        // Clear commands must not inherit a disabled write mask from a
        // previously bound depth-disabled graphics state.
        boolean depthWriteMask = glGetBoolean(GL_DEPTH_WRITEMASK);
        boolean overrideMask = depthClearRequiresMaskOverride(ops, depthWriteMask);
        if (overrideMask) glDepthMask(true);
        try {
            glClearBufferfv(GL_DEPTH, 0, new float[]{ops.clearDepth()});
        } finally {
            if (overrideMask) glDepthMask(false);
        }
    }

    static boolean depthClearRequiresMaskOverride(DepthAttachmentOps ops, boolean depthWriteMask) {
        return ops.loadOp() == LoadOp.CLEAR && !depthWriteMask;
    }

    @Override
    public void endRendering() {
        requireRecording();
        if (!rendering) throw new IllegalStateException("no rendering scope is active");
        rendering = false;
        commands.add(context -> {
            List<Integer> invalidates = new ArrayList<>();
            RenderingInfo info = context.renderingInfo;
            for (int i = 0; i < info.colors().size(); i++) {
                if (info.colors().get(i).storeOp() == StoreOp.DONT_CARE) invalidates.add(GL_COLOR_ATTACHMENT0 + i);
            }
            info.depth().ifPresent(ops -> {
                if (ops.storeOp() == StoreOp.DONT_CARE) invalidates.add(GL_DEPTH_ATTACHMENT);
            });
            invalidate(context.renderTarget, invalidates);
            glDisable(GL_SCISSOR_TEST);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            context.renderTarget = null;
            context.renderingInfo = null;
        });
    }

    private static void invalidate(OpenGLRenderTargetAccess target, List<Integer> attachments) {
        if (attachments.isEmpty()) return;
        int[] nativeAttachments = attachments.stream().mapToInt(Integer::intValue).toArray();
        if (target.framebuffer() == 0) {
            // Default-framebuffer invalidation uses GL_COLOR/GL_DEPTH rather than FBO attachment enums.
            for (int i = 0; i < nativeAttachments.length; i++) {
                if (nativeAttachments[i] >= GL_COLOR_ATTACHMENT0) nativeAttachments[i] = GL_COLOR;
                else if (nativeAttachments[i] == GL_DEPTH_ATTACHMENT) nativeAttachments[i] = GL_DEPTH;
            }
        }
        glInvalidateFramebuffer(GL_FRAMEBUFFER, nativeAttachments);
    }

    @Override
    public void setGraphicsState(GraphicsState state) {
        requireRecording();
        OpenGLGraphicsState next = owned(
                Objects.requireNonNull(state, "state"), OpenGLGraphicsState.class, "graphics state");
        graphicsState = next;
        commands.add(context -> {
            next.requireAlive();
            context.graphicsState = next;
            glUseProgram(next.program);
            glBindVertexArray(next.vao);
            applyFixedState(next.descriptor);
        });
    }

    private static void applyFixedState(GraphicsStateDescriptor descriptor) {
        DepthState depth = descriptor.depthState();
        if (depth.testEnabled()) {
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(OpenGLMappings.compare(depth.compareOp()));
        } else {
            glDisable(GL_DEPTH_TEST);
        }
        glDepthMask(depth.writeEnabled());

        if (descriptor.blendState().enabled()) {
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        } else {
            glDisable(GL_BLEND);
        }

        CullMode cull = descriptor.rasterState().cullMode();
        if (cull == CullMode.NONE) {
            glDisable(GL_CULL_FACE);
        } else {
            glEnable(GL_CULL_FACE);
            glCullFace(OpenGLMappings.cull(cull));
        }
        glFrontFace(GL_CCW);
    }

    @Override
    public void setVertexBuffer(int binding, Buffer buffer, long offset) {
        requireRecording();
        Objects.requireNonNull(buffer, "buffer");
        if (binding < 0 || offset < 0) throw new IllegalArgumentException("binding and offset must be non-negative");
        OpenGLBuffer glBuffer = owned(buffer, OpenGLBuffer.class, "vertex buffer");
        if (!glBuffer.usage().contains(BufferUsage.VERTEX)) throw new IllegalArgumentException("buffer lacks VERTEX usage");
        if (offset >= glBuffer.size()) throw new IllegalArgumentException("vertex buffer offset outside buffer");
        commands.add(context -> context.vertexBuffers.put(binding, new OpenGLExecutionContext.VertexBufferBinding(glBuffer, offset)));
    }

    @Override
    public void setIndexBuffer(Buffer buffer, IndexType indexType, long offset) {
        requireRecording();
        Objects.requireNonNull(buffer, "buffer");
        Objects.requireNonNull(indexType, "indexType");
        OpenGLBuffer glBuffer = owned(buffer, OpenGLBuffer.class, "index buffer");
        if (!glBuffer.usage().contains(BufferUsage.INDEX)) throw new IllegalArgumentException("buffer lacks INDEX usage");
        if (offset < 0 || offset >= glBuffer.size() || offset % indexType.bytes() != 0) {
            throw new IllegalArgumentException("invalid index-buffer offset");
        }
        commands.add(context -> {
            context.indexBuffer = glBuffer;
            context.indexType = indexType;
            context.indexOffset = offset;
        });
    }

    @Override
    public void bindSet(int group, BindingSet set) {
        requireRecording();
        Objects.requireNonNull(set, "set");
        if (group < 0) throw new IllegalArgumentException("group must be non-negative");
        OpenGLBindingSet bindingSet = owned(set, OpenGLBindingSet.class, "binding set");
        if (graphicsState == null) throw new IllegalStateException("bindSet requires an active graphics state");
        OpenGLBindingPlan plan = graphicsState.bindings;
        if (bindingSet.layout() != plan.layout(group)) {
            throw new IllegalArgumentException("binding set layout does not match active state group " + group);
        }
        commands.add(context -> bindSetNow(context, group, bindingSet));
    }

    private static void bindSetNow(OpenGLExecutionContext context, int group, OpenGLBindingSet set) {
        if (context.graphicsState == null) throw new IllegalStateException("graphics state missing at execution");
        context.graphicsState.requireAlive();
        set.requireAlive();
        OpenGLBindingPlan plan = context.graphicsState.bindings;
        if (set.layout() != plan.layout(group)) throw new IllegalStateException("binding layout changed incompatibly");
        validateBindingSetResources(set);
        applyNativeBindings(plan, group, set);
        context.bindingSets.put(group, set);
    }

    private static void applyNativeBindings(OpenGLBindingPlan plan, int group, OpenGLBindingSet set) {
        for (OpenGLBindingPlan.NativeBinding nativeBinding : plan.nativeBindings(group)) {
            Binding<?> binding = nativeBinding.binding();
            Object value = set.descriptor.values().get(binding);
            int slot = nativeBinding.slot();
            switch (binding.type()) {
                case SAMPLED_TEXTURE -> {
                    TextureBinding sampled = (TextureBinding) value;
                    OpenGLTexture texture = (OpenGLTexture) sampled.texture();
                    OpenGLSampler sampler = (OpenGLSampler) sampled.sampler();
                    glActiveTexture(GL_TEXTURE0 + slot);
                    glBindTexture(GL_TEXTURE_2D, texture.handle);
                    glBindSampler(slot, sampler.handle);
                }
                case UNIFORM_BUFFER -> {
                    BufferBinding range = (BufferBinding) value;
                    OpenGLBuffer buffer = (OpenGLBuffer) range.buffer();
                    glBindBufferRange(
                            OpenGLMappings.bufferTarget(binding.type()),
                            slot,
                            buffer.handle,
                            range.offset(),
                            range.size());
                }
            }
        }
    }

    private static void validateBindingSetResources(OpenGLBindingSet set) {
        for (Binding<?> binding : set.descriptor.layout().bindings()) {
            Object value = set.descriptor.values().get(binding);
            switch (binding.type()) {
                case SAMPLED_TEXTURE -> {
                    TextureBinding sampled = (TextureBinding) value;
                    OpenGLTexture texture = (OpenGLTexture) sampled.texture();
                    OpenGLSampler sampler = (OpenGLSampler) sampled.sampler();
                    texture.requireAlive();
                    sampler.requireAlive();
                    requireExactState(texture.state, ResourceState.SAMPLED_READ, "sampled texture");
                }
                case UNIFORM_BUFFER -> {
                    OpenGLBuffer buffer = (OpenGLBuffer) ((BufferBinding) value).buffer();
                    buffer.requireAlive();
                    requireExactState(buffer.state, ResourceState.UNIFORM_READ, "uniform buffer");
                }
            }
        }
    }

    private static void requireExactState(ResourceState actual, ResourceState expected, String label) {
        if (actual != expected) {
            throw new IllegalStateException(label + " requires " + expected + " but is " + actual);
        }
    }

    @Override
    public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        requireRecording();
        if (!rendering || graphicsState == null) throw new IllegalStateException("draw requires rendering and graphics state");
        if (vertexCount <= 0 || instanceCount <= 0 || firstVertex < 0 || firstInstance < 0) throw new IllegalArgumentException("invalid draw arguments");
        commands.add(context -> {
            validateDrawContext(
                    context, false, vertexCount, instanceCount, firstVertex, firstInstance);
            bindVertexBuffers(context);
            glDrawArraysInstancedBaseInstance(
                    OpenGLMappings.primitive(context.graphicsState.descriptor.topology()),
                    firstVertex,
                    vertexCount,
                    instanceCount,
                    firstInstance);
        });
    }

    @Override
    public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance) {
        requireRecording();
        if (!rendering || graphicsState == null) throw new IllegalStateException("indexed draw requires rendering and graphics state");
        if (indexCount <= 0 || instanceCount <= 0 || firstIndex < 0 || firstInstance < 0) throw new IllegalArgumentException("invalid indexed draw arguments");
        commands.add(context -> {
            validateDrawContext(
                    context, true, indexCount, instanceCount, firstIndex, firstInstance);
            if (context.indexBuffer == null) throw new IllegalStateException("no index buffer is bound");
            bindVertexBuffers(context);
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, context.indexBuffer.handle);
            long pointer = context.indexOffset + (long) firstIndex * context.indexType.bytes();
            glDrawElementsInstancedBaseVertexBaseInstance(
                    OpenGLMappings.primitive(context.graphicsState.descriptor.topology()),
                    indexCount,
                    OpenGLMappings.indexType(context.indexType),
                    pointer,
                    instanceCount,
                    vertexOffset,
                    firstInstance);
        });
    }

    private static void validateDrawContext(
            OpenGLExecutionContext context,
            boolean indexed,
            int count,
            int instanceCount,
            int first,
            int firstInstance) {
        if (context.renderTarget == null || context.graphicsState == null) throw new IllegalStateException("draw state missing at execution");
        context.graphicsState.requireAlive();
        context.renderTarget.width();
        if (context.renderTarget instanceof OpenGLRenderTarget internal) {
            internal.requireAttachmentsAlive();
        }
        if (!context.graphicsState.descriptor.colorFormats().equals(context.renderTarget.colorFormats())) {
            throw new IllegalStateException("graphics-state color formats do not match render target");
        }
        if (!context.graphicsState.descriptor.depthFormat().equals(java.util.Optional.ofNullable(context.renderTarget.depthFormat()))) {
            throw new IllegalStateException("graphics-state depth format does not match render target");
        }
        validateRequiredBindingSets(context);
        validateVertexBuffers(
                context, indexed, count, instanceCount, first, firstInstance);
        if (indexed) validateIndexBuffer(context, first, count);
        rebindRequiredBindingSets(context);
    }

    private static void validateRequiredBindingSets(OpenGLExecutionContext context) {
        OpenGLBindingPlan plan = context.graphicsState.bindings;
        for (int group = 0; group < plan.layoutCount(); group++) {
            OpenGLBindingSet set = context.bindingSets.get(group);
            if (set == null || set.layout() != plan.layout(group)) {
                throw new IllegalStateException("missing compatible binding set for group " + group);
            }
            set.requireAlive();
            validateBindingSetResources(set);
        }
    }

    private static void rebindRequiredBindingSets(OpenGLExecutionContext context) {
        OpenGLBindingPlan plan = context.graphicsState.bindings;
        for (int group = 0; group < plan.layoutCount(); group++) {
            applyNativeBindings(plan, group, context.bindingSets.get(group));
        }
    }

    private static void validateVertexBuffers(
            OpenGLExecutionContext context,
            boolean indexed,
            int count,
            int instanceCount,
            int first,
            int firstInstance) {
        VertexLayout layout = context.graphicsState.descriptor.vertexLayout();
        for (VertexBinding binding : layout.bindings()) {
            OpenGLExecutionContext.VertexBufferBinding value = context.vertexBuffers.get(binding.binding());
            if (value == null) throw new IllegalStateException("vertex binding " + binding.binding() + " has no buffer");
            value.buffer().requireAlive();
            requireExactState(value.buffer().state, ResourceState.VERTEX_READ, "vertex buffer");
            OpenGLValidation.validateVertexRange(
                    value.buffer().size(),
                    value.offset(),
                    binding,
                    layout.attributes(),
                    indexed,
                    indexed ? 1 : count,
                    instanceCount,
                    indexed ? 0 : first,
                    firstInstance);
        }
    }

    private static void validateIndexBuffer(OpenGLExecutionContext context, int firstIndex, int indexCount) {
        if (context.indexBuffer == null) throw new IllegalStateException("no index buffer is bound");
        context.indexBuffer.requireAlive();
        requireExactState(context.indexBuffer.state, ResourceState.INDEX_READ, "index buffer");
        OpenGLValidation.validateIndexRange(
                context.indexBuffer.size(),
                context.indexOffset,
                context.indexType,
                firstIndex,
                indexCount);
    }

    private static void bindVertexBuffers(OpenGLExecutionContext context) {
        for (VertexBinding binding : context.graphicsState.descriptor.vertexLayout().bindings()) {
            OpenGLExecutionContext.VertexBufferBinding value = context.vertexBuffers.get(binding.binding());
            glBindVertexBuffer(binding.binding(), value.buffer().handle, value.offset(), binding.stride());
        }
    }

    @Override
    public void copyTexture(Texture source, Texture destination) {
        requireRecording();
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "destination");
        if (rendering) throw new IllegalStateException("copyTexture is not allowed inside rendering");
        OpenGLTexture src = owned(source, OpenGLTexture.class, "source texture");
        OpenGLTexture dst = owned(destination, OpenGLTexture.class, "destination texture");
        if (!src.usage().contains(TextureUsage.COPY_SRC) || !dst.usage().contains(TextureUsage.COPY_DST)) {
            throw new IllegalArgumentException("copy usages are missing");
        }
        if (src.width() != dst.width() || src.height() != dst.height() || src.format() != dst.format()) {
            throw new IllegalArgumentException("texture copies require identical dimensions and format");
        }
        commands.add(context -> {
            src.requireAlive();
            dst.requireAlive();
            requireExactState(src.state, ResourceState.COPY_SRC, "copy source");
            requireExactState(dst.state, ResourceState.COPY_DST, "copy destination");
            glCopyImageSubData(
                    src.handle, GL_TEXTURE_2D, 0, 0, 0, 0,
                    dst.handle, GL_TEXTURE_2D, 0, 0, 0, 0,
                    src.width(), src.height(), 1);
        });
    }

    @Override
    public void writeBuffer(Buffer buffer, long offset, ByteBuffer data) {
        requireRecording();
        Objects.requireNonNull(buffer, "buffer");
        Objects.requireNonNull(data, "data");
        if (rendering) throw new IllegalStateException("writeBuffer is not allowed inside rendering");
        OpenGLBuffer destination = owned(buffer, OpenGLBuffer.class, "buffer");
        byte[] snapshot = OpenGLBufferUpdates.snapshot(destination.size(), offset, data);
        commands.add(context -> {
            destination.requireAlive();
            requireWritableBufferState(destination.state);
            int previous = glGetInteger(GL_COPY_WRITE_BUFFER_BINDING);
            try {
                glBindBuffer(GL_COPY_WRITE_BUFFER, destination.handle);
                OpenGLUploadMemory.withNativeBuffer(
                        ByteBuffer.wrap(snapshot),
                        upload -> glBufferSubData(GL_COPY_WRITE_BUFFER, offset, upload));
            } finally {
                glBindBuffer(GL_COPY_WRITE_BUFFER, previous);
            }
        });
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
        requireRecording();
        Objects.requireNonNull(texture, "texture");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (rendering) throw new IllegalStateException("transitions are not allowed inside rendering");
        OpenGLTexture resource = owned(texture, OpenGLTexture.class, "texture");
        validateTextureState(resource, from);
        validateTextureState(resource, to);
        if (to == ResourceState.UNDEFINED) throw new IllegalArgumentException("UNDEFINED cannot be a transition destination");
        commands.add(context -> {
            resource.requireAlive();
            OpenGLValidation.validateTransitionFrom(
                    validation, resource.state, from, "texture");
            issueBarrier(resource.state, to);
            resource.state = to;
        });
    }

    @Override
    public void transition(Buffer buffer, ResourceState from, ResourceState to) {
        requireRecording();
        Objects.requireNonNull(buffer, "buffer");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (rendering) throw new IllegalStateException("transitions are not allowed inside rendering");
        OpenGLBuffer resource = owned(buffer, OpenGLBuffer.class, "buffer");
        validateBufferState(resource, from);
        validateBufferState(resource, to);
        if (to == ResourceState.UNDEFINED) throw new IllegalArgumentException("UNDEFINED cannot be a transition destination");
        commands.add(context -> {
            resource.requireAlive();
            OpenGLValidation.validateTransitionFrom(
                    validation, resource.state, from, "buffer");
            issueBarrier(resource.state, to);
            resource.state = to;
        });
    }

    private static void validateTextureState(OpenGLTexture texture, ResourceState state) {
        switch (state) {
            case COLOR_ATTACHMENT_WRITE -> requireUsage(texture.usage().contains(TextureUsage.COLOR_ATTACHMENT), "COLOR_ATTACHMENT");
            case DEPTH_ATTACHMENT_WRITE -> requireUsage(texture.usage().contains(TextureUsage.DEPTH_ATTACHMENT), "DEPTH_ATTACHMENT");
            case SAMPLED_READ -> requireUsage(texture.usage().contains(TextureUsage.SAMPLED), "SAMPLED");
            case COPY_SRC -> requireUsage(texture.usage().contains(TextureUsage.COPY_SRC), "COPY_SRC");
            case COPY_DST -> requireUsage(texture.usage().contains(TextureUsage.COPY_DST), "COPY_DST");
            case UNDEFINED -> { }
            default -> throw new IllegalArgumentException(state + " is not a texture state");
        }
    }

    private static void validateBufferState(OpenGLBuffer buffer, ResourceState state) {
        switch (state) {
            case UNIFORM_READ -> requireUsage(buffer.usage().contains(BufferUsage.UNIFORM), "UNIFORM");
            case VERTEX_READ -> requireUsage(buffer.usage().contains(BufferUsage.VERTEX), "VERTEX");
            case INDEX_READ -> requireUsage(buffer.usage().contains(BufferUsage.INDEX), "INDEX");
            case UNDEFINED -> { }
            default -> throw new IllegalArgumentException(state + " is not a buffer state");
        }
    }

    private static void requireUsage(boolean condition, String usage) {
        if (!condition) throw new IllegalArgumentException("resource lacks " + usage + " usage");
    }

    private static void issueBarrier(ResourceState from, ResourceState to) {
        if (from != ResourceState.COPY_DST
                && from != ResourceState.COLOR_ATTACHMENT_WRITE && from != ResourceState.DEPTH_ATTACHMENT_WRITE) {
            return;
        }
        int bits = switch (to) {
            case SAMPLED_READ -> GL_TEXTURE_FETCH_BARRIER_BIT;
            case UNIFORM_READ -> GL_UNIFORM_BARRIER_BIT;
            case VERTEX_READ -> GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT;
            case INDEX_READ -> GL_ELEMENT_ARRAY_BARRIER_BIT;
            case COPY_SRC, COPY_DST -> GL_TEXTURE_UPDATE_BARRIER_BIT;
            case COLOR_ATTACHMENT_WRITE, DEPTH_ATTACHMENT_WRITE -> GL_FRAMEBUFFER_BARRIER_BIT;
            case UNDEFINED -> 0;
        };
        if (bits != 0) glMemoryBarrier(bits);
    }

    @Override
    public CommandList finish() {
        requireRecording();
        if (rendering) throw new IllegalStateException("cannot finish while rendering scope is active");
        finished = true;
        OpenGLCommandList result = new OpenGLCommandList(device, commands);
        commands.clear();
        return result;
    }

    @Override
    public void close() {
        if (finished) return;
        finished = true;
        commands.clear();
    }
}
