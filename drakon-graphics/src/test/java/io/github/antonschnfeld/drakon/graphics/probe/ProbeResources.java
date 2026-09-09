package io.github.antonschnfeld.drakon.graphics.probe;

import io.github.antonschnfeld.drakon.graphics.resource.*;

import java.util.*;

final class ProbeResources {
    private ProbeResources() {}

    abstract static class Resource implements GpuResource {
        private final ProbeGraphicsDevice owner;
        private final long debugId;
        private boolean closed;

        Resource(ProbeGraphicsDevice owner, long debugId) {
            this.owner = owner;
            this.debugId = debugId;
        }

        ProbeGraphicsDevice owner() { return owner; }
        long debugId() { return debugId; }
        boolean closed() { return closed; }

        @Override public void close() { closed = true; }
    }

    static final class ProbeBuffer extends Resource implements Buffer {
        private final long size;
        private final Set<BufferUsage> usage;

        ProbeBuffer(ProbeGraphicsDevice owner, long id, BufferDescriptor descriptor) {
            super(owner, id);
            size = descriptor.size();
            usage = descriptor.usage();
        }

        @Override public long size() { return size; }
        @Override public Set<BufferUsage> usage() { return usage; }
    }

    static final class ProbeTexture extends Resource implements Texture {
        private final int width, height;
        private final TextureFormat format;
        private final Set<TextureUsage> usage;

        ProbeTexture(ProbeGraphicsDevice owner, long id, TextureDescriptor descriptor) {
            super(owner, id);
            width = descriptor.width();
            height = descriptor.height();
            format = descriptor.format();
            usage = descriptor.usage();
        }

        @Override public int width() { return width; }
        @Override public int height() { return height; }
        @Override public TextureFormat format() { return format; }
        @Override public Set<TextureUsage> usage() { return usage; }
    }

    static final class ProbeShader extends Resource implements Shader {
        private final ShaderStage stage;

        ProbeShader(ProbeGraphicsDevice owner, long id, ShaderStage stage) {
            super(owner, id);
            this.stage = stage;
        }

        @Override public ShaderStage stage() { return stage; }
    }

    static final class ProbeSampler extends Resource implements Sampler {
        ProbeSampler(ProbeGraphicsDevice owner, long id) { super(owner, id); }
    }

    static final class ProbeGraphicsState extends Resource implements GraphicsState {
        private final GraphicsStateDescriptor descriptor;

        ProbeGraphicsState(ProbeGraphicsDevice owner, long id, GraphicsStateDescriptor descriptor) {
            super(owner, id);
            this.descriptor = descriptor;
        }

        GraphicsStateDescriptor descriptor() { return descriptor; }
    }

    static final class ProbeComputeState extends Resource implements ComputeState {
        private final ComputeStateDescriptor descriptor;

        ProbeComputeState(ProbeGraphicsDevice owner, long id, ComputeStateDescriptor descriptor) {
            super(owner, id);
            this.descriptor = descriptor;
        }

        ComputeStateDescriptor descriptor() { return descriptor; }
    }

    static final class ProbeBindingSet extends Resource implements BindingSet {
        private final BindingSetDescriptor descriptor;

        ProbeBindingSet(ProbeGraphicsDevice owner, long id, BindingSetDescriptor descriptor) {
            super(owner, id);
            this.descriptor = descriptor;
        }

        @Override public BindingLayout layout() { return descriptor.layout(); }
        BindingSetDescriptor descriptor() { return descriptor; }
    }

    static final class ProbeRenderTarget extends Resource implements RenderTarget {
        private final List<Texture> colors;
        private final Texture depth;
        private final List<TextureFormat> colorFormats;
        private final TextureFormat depthFormat;
        private final int width, height;
        private final boolean presentable;

        ProbeRenderTarget(ProbeGraphicsDevice owner, long id, RenderTargetDescriptor descriptor) {
            super(owner, id);
            colors = descriptor.colorAttachments();
            depth = descriptor.depthAttachment();
            colorFormats = colors.stream().map(Texture::format).toList();
            depthFormat = depth == null ? null : depth.format();
            Texture first = !colors.isEmpty() ? colors.get(0) : depth;
            width = first.width();
            height = first.height();
            presentable = false;
        }

        ProbeRenderTarget(
                ProbeGraphicsDevice owner,
                long id,
                int width,
                int height,
                List<TextureFormat> colorFormats,
                TextureFormat depthFormat) {
            super(owner, id);
            this.colors = List.of();
            this.depth = null;
            this.colorFormats = List.copyOf(colorFormats);
            this.depthFormat = depthFormat;
            this.width = width;
            this.height = height;
            this.presentable = true;
        }

        List<Texture> colors() { return colors; }
        Texture depth() { return depth; }
        boolean presentable() { return presentable; }

        @Override public int width() { return width; }
        @Override public int height() { return height; }
        @Override public List<TextureFormat> colorFormats() { return colorFormats; }
        @Override public TextureFormat depthFormat() { return depthFormat; }
    }
}
