package io.github.antonschnfeld.drakon.graphics.opengl;

import io.github.antonschnfeld.drakon.graphics.command.*;
import io.github.antonschnfeld.drakon.graphics.resource.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL15C.*;
import static org.lwjgl.opengl.GL20C.glUseProgram;
import static org.lwjgl.opengl.GL30C.*;
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
        OpenGLRenderTarget target = owned(info.target(), OpenGLRenderTarget.class, "render target");
        validateAttachmentStates(target);
        rendering = true;
        commands.add(context -> beginRenderingNow(context, target, info));
    }

    private void validateAttachmentStates(OpenGLRenderTarget target) {
        if (!validation || target.presentable()) return;
        for (OpenGLTexture color : target.colors()) {
            if (color.state != ResourceState.COLOR_ATTACHMENT_WRITE) {
                throw new IllegalStateException("color attachment is not in COLOR_ATTACHMENT_WRITE");
            }
        }
        if (target.depth() != null && target.depth().state != ResourceState.DEPTH_ATTACHMENT_WRITE) {
            throw new IllegalStateException("depth attachment is not in DEPTH_ATTACHMENT_WRITE");
        }
    }

    private static void beginRenderingNow(OpenGLExecutionContext context, OpenGLRenderTarget target, RenderingInfo info) {
        context.renderTarget = target;
        context.renderingInfo = info;
        glBindFramebuffer(GL_FRAMEBUFFER, target.framebuffer);

        Viewport viewport = info.viewport();
        int viewportY = Math.round(target.height() - (viewport.y() + viewport.height()));
        glViewport(Math.round(viewport.x()), viewportY, Math.round(viewport.width()), Math.round(viewport.height()));
        glDepthRange(viewport.minDepth(), viewport.maxDepth());

        ScissorRect scissor = info.scissor();
        int scissorY = target.height() - (scissor.y() + scissor.height());
        glEnable(GL_SCISSOR_TEST);
        glScissor(scissor.x(), scissorY, scissor.width(), scissor.height());

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
                glClearBufferfv(GL_DEPTH, 0, new float[]{ops.clearDepth()});
            } else if (ops.loadOp() == LoadOp.DONT_CARE) {
                invalidateAtStart.add(GL_DEPTH_ATTACHMENT);
            }
        });
        invalidate(target, invalidateAtStart);
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

    private static void invalidate(OpenGLRenderTarget target, List<Integer> attachments) {
        if (attachments.isEmpty()) return;
        int[] nativeAttachments = attachments.stream().mapToInt(Integer::intValue).toArray();
        if (target.framebuffer == 0) {
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
        OpenGLGraphicsState next = owned(state, OpenGLGraphicsState.class, "graphics state");
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
        if (binding < 0 || offset < 0) throw new IllegalArgumentException("binding and offset must be non-negative");
        OpenGLBuffer glBuffer = owned(buffer, OpenGLBuffer.class, "vertex buffer");
        if (!glBuffer.usage().contains(BufferUsage.VERTEX)) throw new IllegalArgumentException("buffer lacks VERTEX usage");
        if (offset >= glBuffer.size()) throw new IllegalArgumentException("vertex buffer offset outside buffer");
        commands.add(context -> context.vertexBuffers.put(binding, new OpenGLExecutionContext.VertexBufferBinding(glBuffer, offset)));
    }

    @Override
    public void setIndexBuffer(Buffer buffer, IndexType indexType, long offset) {
        requireRecording();
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
        OpenGLBindingPlan plan = context.graphicsState.bindings;
        if (set.layout() != plan.layout(group)) throw new IllegalStateException("binding layout changed incompatibly");
        for (Binding<?> binding : set.layout().bindings()) {
            Object value = set.descriptor.values().get(binding);
            int slot = plan.slot(binding);
            switch (binding.type()) {
                case SAMPLED_TEXTURE -> {
                    TextureBinding sampled = (TextureBinding) value;
                    OpenGLTexture texture = (OpenGLTexture) sampled.texture();
                    OpenGLSampler sampler = (OpenGLSampler) sampled.sampler();
                    requireExactState(texture.state, ResourceState.SAMPLED_READ, "sampled texture");
                    glActiveTexture(GL_TEXTURE0 + slot);
                    glBindTexture(GL_TEXTURE_2D, texture.handle);
                    glBindSampler(slot, sampler.handle);
                }
                case UNIFORM_BUFFER -> {
                    BufferBinding range = (BufferBinding) value;
                    OpenGLBuffer buffer = (OpenGLBuffer) range.buffer();
                    requireExactState(buffer.state, ResourceState.UNIFORM_READ, "uniform buffer");
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

    private static void requireExactState(ResourceState actual, ResourceState expected, String label) {
        if (actual != expected) {
            throw new IllegalStateException(label + " requires " + expected + " but is " + actual);
        }
    }

    private static void requireState(ResourceState actual, ResourceState expected, String label) {
        if (actual != ResourceState.UNDEFINED && actual != expected) {
            throw new IllegalStateException(label + " requires " + expected + " but is " + actual);
        }
    }

    @Override
    public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        requireRecording();
        if (!rendering || graphicsState == null) throw new IllegalStateException("draw requires rendering and graphics state");
        if (vertexCount <= 0 || instanceCount <= 0 || firstVertex < 0 || firstInstance < 0) throw new IllegalArgumentException("invalid draw arguments");
        commands.add(context -> {
            validateDrawContext(context);
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
            validateDrawContext(context);
            if (context.indexBuffer == null) throw new IllegalStateException("no index buffer is bound");
            if (context.indexBuffer.state != ResourceState.UNDEFINED && context.indexBuffer.state != ResourceState.INDEX_READ) {
                throw new IllegalStateException("index buffer is not in INDEX_READ");
            }
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

    private static void validateDrawContext(OpenGLExecutionContext context) {
        if (context.renderTarget == null || context.graphicsState == null) throw new IllegalStateException("draw state missing at execution");
        if (!context.graphicsState.descriptor.colorFormats().equals(context.renderTarget.colorFormats())) {
            throw new IllegalStateException("graphics-state color formats do not match render target");
        }
        if (!context.graphicsState.descriptor.depthFormat().equals(java.util.Optional.ofNullable(context.renderTarget.depthFormat()))) {
            throw new IllegalStateException("graphics-state depth format does not match render target");
        }
    }

    private static void bindVertexBuffers(OpenGLExecutionContext context) {
        for (VertexBinding binding : context.graphicsState.descriptor.vertexLayout().bindings()) {
            OpenGLExecutionContext.VertexBufferBinding value = context.vertexBuffers.get(binding.binding());
            if (value == null) throw new IllegalStateException("vertex binding " + binding.binding() + " has no buffer");
            if (value.buffer().state != ResourceState.UNDEFINED && value.buffer().state != ResourceState.VERTEX_READ) {
                throw new IllegalStateException("vertex buffer is not in VERTEX_READ");
            }
            glBindVertexBuffer(binding.binding(), value.buffer().handle, value.offset(), binding.stride());
        }
    }

    @Override
    public void copyTexture(Texture source, Texture destination) {
        requireRecording();
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
            requireState(src.state, ResourceState.COPY_SRC, "copy source");
            requireState(dst.state, ResourceState.COPY_DST, "copy destination");
            glCopyImageSubData(
                    src.handle, GL_TEXTURE_2D, 0, 0, 0, 0,
                    dst.handle, GL_TEXTURE_2D, 0, 0, 0, 0,
                    src.width(), src.height(), 1);
        });
    }

    @Override
    public void transition(Texture texture, ResourceState from, ResourceState to) {
        requireRecording();
        if (rendering) throw new IllegalStateException("transitions are not allowed inside rendering");
        OpenGLTexture resource = owned(texture, OpenGLTexture.class, "texture");
        validateTextureState(resource, from);
        validateTextureState(resource, to);
        if (to == ResourceState.UNDEFINED) throw new IllegalArgumentException("UNDEFINED cannot be a transition destination");
        commands.add(context -> {
            if (validation && resource.state != from) {
                throw new IllegalStateException("texture transition expected " + from + " but current state is " + resource.state);
            }
            issueBarrier(resource.state, to);
            resource.state = to;
        });
    }

    @Override
    public void transition(Buffer buffer, ResourceState from, ResourceState to) {
        requireRecording();
        if (rendering) throw new IllegalStateException("transitions are not allowed inside rendering");
        OpenGLBuffer resource = owned(buffer, OpenGLBuffer.class, "buffer");
        validateBufferState(resource, from);
        validateBufferState(resource, to);
        if (to == ResourceState.UNDEFINED) throw new IllegalArgumentException("UNDEFINED cannot be a transition destination");
        commands.add(context -> {
            if (validation && resource.state != from) {
                throw new IllegalStateException("buffer transition expected " + from + " but current state is " + resource.state);
            }
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
