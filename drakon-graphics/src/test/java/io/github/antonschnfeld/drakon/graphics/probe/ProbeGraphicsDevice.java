package io.github.antonschnfeld.drakon.graphics.probe;

import io.github.antonschnfeld.drakon.graphics.backend.*;
import io.github.antonschnfeld.drakon.graphics.command.*;
import io.github.antonschnfeld.drakon.graphics.resource.*;
import io.github.antonschnfeld.drakon.graphics.shader.*;
import io.github.antonschnfeld.drakon.graphics.backend.GraphicsDevice;
import io.github.antonschnfeld.drakon.graphics.command.CommandEncoder;
import io.github.antonschnfeld.drakon.graphics.command.CommandList;
import io.github.antonschnfeld.drakon.graphics.shader.ShaderDescriptor;
import io.github.antonschnfeld.drakon.graphics.shader.ShaderTarget;

import java.lang.foreign.MemorySegment;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

final class ProbeGraphicsDevice implements GraphicsDevice {
    private final String backendName;
    private final boolean validation;
    private final ShaderTarget shaderTarget;
    private final AtomicLong ids = new AtomicLong(1);
    private final List<ProbeResources.Resource> resources = new ArrayList<>();

    /*
     * States live at device scope rather than encoder scope so validation also
     * catches mistakes that cross command-list/frame boundaries. A real Vulkan
     * backend will need more sophisticated synchronization tracking, but the
     * public contract explicitly does not reset resource state per encoder.
     */
    private final Map<Texture, ResourceState> textureStates = new IdentityHashMap<>();
    private final Map<Buffer, ResourceState> bufferStates = new IdentityHashMap<>();
    private ProbeCommandEncoder lastEncoder;
    private ProbeCommandList lastCommandList;
    private boolean closed;

    ProbeGraphicsDevice(String backendName, boolean validation, ShaderTarget shaderTarget) {
        this.backendName = backendName;
        this.validation = validation;
        this.shaderTarget = Objects.requireNonNull(shaderTarget, "shaderTarget");
    }

    private long id() { return ids.getAndIncrement(); }
    private void ensureOpen() { if (closed) throw new IllegalStateException("device is closed"); }

    private <T extends ProbeResources.Resource> T own(T resource) {
        resources.add(resource);
        return resource;
    }

    void requireOwned(GpuResource resource) {
        Objects.requireNonNull(resource, "resource");
        if (!(resource instanceof ProbeResources.Resource probe) || probe.owner() != this) {
            throw new IllegalArgumentException("foreign resource");
        }
        if (probe.closed()) throw new IllegalStateException("resource is closed");
    }

    static long debugId(GpuResource resource) {
        return ((ProbeResources.Resource) resource).debugId();
    }

    ProbeCommandEncoder lastEncoder() { return lastEncoder; }
    ProbeCommandList lastCommandList() { return lastCommandList; }
    void commandListCreated(ProbeCommandList list) { lastCommandList = list; }

    @Override public Buffer createBuffer(BufferDescriptor descriptor) {
        ensureOpen();
        Objects.requireNonNull(descriptor, "descriptor");
        ProbeResources.ProbeBuffer buffer = own(new ProbeResources.ProbeBuffer(this, id(), descriptor));
        bufferStates.put(buffer, ResourceState.UNDEFINED);
        return buffer;
    }

    @Override public Buffer createBuffer(BufferDescriptor descriptor, MemorySegment initialData) {
        ensureOpen();
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(initialData, "initialData");
        if (initialData.byteSize() > descriptor.size()) {
            throw new IllegalArgumentException("initial data exceeds buffer size");
        }
        return createBuffer(descriptor);
    }

    @Override public Texture createTexture(TextureDescriptor descriptor) {
        ensureOpen();
        Objects.requireNonNull(descriptor, "descriptor");
        ProbeResources.ProbeTexture texture = own(new ProbeResources.ProbeTexture(this, id(), descriptor));
        textureStates.put(texture, ResourceState.UNDEFINED);
        return texture;
    }

    @Override public Texture createTexture(
            TextureDescriptor descriptor, MemorySegment initialData, ResourceState initialState) {
        ensureOpen();
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(initialData, "initialData");
        Objects.requireNonNull(initialState, "initialState");
        if (descriptor.format().isDepth()) {
            throw new IllegalArgumentException("CPU initialization of depth textures is not supported");
        }
        long required = textureByteCount(descriptor);
        if (initialData.byteSize() != required) {
            throw new IllegalArgumentException("initial data must contain exactly " + required + " bytes");
        }
        validateTextureStateUsage(descriptor, initialState);
        ProbeResources.ProbeTexture texture = own(new ProbeResources.ProbeTexture(this, id(), descriptor));
        textureStates.put(texture, initialState);
        return texture;
    }

    ResourceState textureState(Texture texture) {
        return textureStates.getOrDefault(texture, ResourceState.UNDEFINED);
    }

    ResourceState bufferState(Buffer buffer) {
        return bufferStates.getOrDefault(buffer, ResourceState.UNDEFINED);
    }

    private static long textureByteCount(TextureDescriptor descriptor) {
        long texels = Math.multiplyExact((long) descriptor.width(), descriptor.height());
        int bytesPerTexel = switch (descriptor.format()) {
            case RGBA8_UNORM, BGRA8_UNORM, D32_FLOAT -> 4;
        };
        return Math.multiplyExact(texels, bytesPerTexel);
    }

    private static void validateTextureStateUsage(TextureDescriptor descriptor, ResourceState state) {
        TextureUsage requiredUsage = switch (state) {
            case COLOR_ATTACHMENT_WRITE -> TextureUsage.COLOR_ATTACHMENT;
            case DEPTH_ATTACHMENT_WRITE -> TextureUsage.DEPTH_ATTACHMENT;
            case SAMPLED_READ -> TextureUsage.SAMPLED;
            case COPY_SRC -> TextureUsage.COPY_SRC;
            case COPY_DST -> TextureUsage.COPY_DST;
            case UNDEFINED, UNIFORM_READ, VERTEX_READ, INDEX_READ ->
                    throw new IllegalArgumentException(state + " is not a valid initialized texture state");
        };
        if (!descriptor.usage().contains(requiredUsage)) {
            throw new IllegalArgumentException(state + " requires texture usage " + requiredUsage);
        }
    }

    @Override public ShaderTarget shaderTarget() {
        ensureOpen();
        return shaderTarget;
    }

    @Override public Shader createShader(ShaderDescriptor descriptor) {
        ensureOpen();
        Objects.requireNonNull(descriptor, "descriptor");
        if (!shaderTarget.accepts(descriptor.code())) {
            throw new IllegalArgumentException("shader code representation is incompatible with device target");
        }
        return own(new ProbeResources.ProbeShader(this, id(), descriptor.stage()));
    }

    @Override public Sampler createSampler(SamplerDescriptor descriptor) {
        ensureOpen();
        Objects.requireNonNull(descriptor, "descriptor");
        return own(new ProbeResources.ProbeSampler(this, id()));
    }

    @Override public GraphicsState createGraphicsState(GraphicsStateDescriptor descriptor) {
        ensureOpen();
        Objects.requireNonNull(descriptor, "descriptor");
        requireOwned(descriptor.vertexShader());
        requireOwned(descriptor.fragmentShader());
        return own(new ProbeResources.ProbeGraphicsState(this, id(), descriptor));
    }

    @Override public BindingSet createBindingSet(BindingSetDescriptor descriptor) {
        ensureOpen();
        Objects.requireNonNull(descriptor, "descriptor");
        for (Object value : descriptor.values().values()) {
            if (value instanceof GpuResource resource) requireOwned(resource);
            if (value instanceof BufferBinding range) requireOwned(range.buffer());
        }
        return own(new ProbeResources.ProbeBindingSet(this, id(), descriptor));
    }

    @Override public RenderTarget createRenderTarget(RenderTargetDescriptor descriptor) {
        ensureOpen();
        Objects.requireNonNull(descriptor, "descriptor");
        descriptor.colorAttachments().forEach(this::requireOwned);
        if (descriptor.depthAttachment() != null) requireOwned(descriptor.depthAttachment());
        return own(new ProbeResources.ProbeRenderTarget(this, id(), descriptor));
    }

    RenderTarget createPresentationTargetForTest(
            int width,
            int height,
            List<TextureFormat> colorFormats,
            TextureFormat depthFormat) {
        ensureOpen();
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("presentation extent must be positive");
        Objects.requireNonNull(colorFormats, "colorFormats");
        if (colorFormats.size() != 1) {
            throw new IllegalArgumentException("presentation target requires exactly one color format");
        }
        return own(new ProbeResources.ProbeRenderTarget(
                this, id(), width, height, colorFormats, depthFormat));
    }

    @Override public void present(RenderTarget target) {
        ensureOpen();
        Objects.requireNonNull(target, "target");
        requireOwned(target);
        if (!(target instanceof ProbeResources.ProbeRenderTarget probe) || !probe.presentable()) {
            throw new IllegalArgumentException("render target is not presentation-capable");
        }
    }

    @Override public CommandEncoder createCommandEncoder() {
        ensureOpen();
        lastEncoder = new ProbeCommandEncoder(this, backendName, validation, textureStates, bufferStates);
        return lastEncoder;
    }

    @Override public void submit(CommandList commandList) {
        ensureOpen();
        Objects.requireNonNull(commandList, "commandList");
        if (!(commandList instanceof ProbeCommandList list) || list.owner() != this) {
            throw new IllegalArgumentException("foreign command list");
        }
        list.beginSubmission();
        try {
            if (validation && list.operations().isEmpty()) throw new IllegalStateException("empty command list");
            for (ProbeCommandEncoder.ProbeBufferWrite write : list.bufferWrites()) {
                requireOwned(write.buffer());
            }
            list.markSubmitted();
        } catch (RuntimeException | Error failure) {
            list.markFailed();
            throw failure;
        }
    }

    @Override public void close() {
        if (closed) return;
        for (ProbeResources.Resource resource : resources) resource.close();
        closed = true;
    }
}
