package io.github.antonschnfeld.drakon.graphics.probe;

import io.github.antonschnfeld.drakon.graphics.backend.*;
import io.github.antonschnfeld.drakon.graphics.command.*;
import io.github.antonschnfeld.drakon.graphics.resource.*;
import io.github.antonschnfeld.drakon.graphics.shader.*;
import io.github.antonschnfeld.drakon.graphics.backend.GraphicsCapabilities;
import io.github.antonschnfeld.drakon.graphics.backend.GraphicsDevice;
import io.github.antonschnfeld.drakon.graphics.command.CommandEncoder;
import io.github.antonschnfeld.drakon.graphics.command.CommandList;
import io.github.antonschnfeld.drakon.graphics.shader.ShaderDescriptor;
import io.github.antonschnfeld.drakon.graphics.shader.ShaderTarget;

import java.nio.ByteBuffer;
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

    @Override public Buffer createBuffer(BufferDescriptor descriptor) {
        ensureOpen();
        Objects.requireNonNull(descriptor, "descriptor");
        ProbeResources.ProbeBuffer buffer = own(new ProbeResources.ProbeBuffer(this, id(), descriptor));
        bufferStates.put(buffer, ResourceState.UNDEFINED);
        return buffer;
    }

    @Override public Buffer createBuffer(BufferDescriptor descriptor, ByteBuffer initialData) {
        ensureOpen();
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(initialData, "initialData");
        if (initialData.remaining() > descriptor.size()) {
            throw new IllegalArgumentException("initial data exceeds buffer size");
        }
        int position = initialData.position();
        int limit = initialData.limit();
        Buffer buffer = createBuffer(descriptor);
        if (initialData.position() != position || initialData.limit() != limit) {
            throw new AssertionError("probe unexpectedly modified ByteBuffer state");
        }
        return buffer;
    }

    @Override public Texture createTexture(TextureDescriptor descriptor) {
        ensureOpen();
        Objects.requireNonNull(descriptor, "descriptor");
        ProbeResources.ProbeTexture texture = own(new ProbeResources.ProbeTexture(this, id(), descriptor));
        textureStates.put(texture, ResourceState.UNDEFINED);
        return texture;
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
        descriptor.fragmentShader().ifPresent(this::requireOwned);
        return own(new ProbeResources.ProbeGraphicsState(this, id(), descriptor));
    }

    @Override public ComputeState createComputeState(ComputeStateDescriptor descriptor) {
        ensureOpen();
        Objects.requireNonNull(descriptor, "descriptor");
        requireOwned(descriptor.computeShader());
        return own(new ProbeResources.ProbeComputeState(this, id(), descriptor));
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
        if (colorFormats.isEmpty() && depthFormat == null) {
            throw new IllegalArgumentException("presentation target needs at least one attachment format");
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
        return new ProbeCommandEncoder(this, backendName, validation, textureStates, bufferStates);
    }

    @Override public void submit(CommandList commandList) {
        ensureOpen();
        Objects.requireNonNull(commandList, "commandList");
        if (!(commandList instanceof ProbeCommandList list) || list.owner() != this) {
            throw new IllegalArgumentException("foreign command list");
        }
        if (list.submitted()) throw new IllegalArgumentException("command list already submitted");
        if (validation && list.operations().isEmpty()) throw new IllegalStateException("empty command list");
        list.markSubmitted();
    }

    @Override public GraphicsCapabilities capabilities() {
        ensureOpen();
        return new GraphicsCapabilities(true, true, true);
    }

    @Override public void close() {
        if (closed) return;
        for (ProbeResources.Resource resource : resources) resource.close();
        closed = true;
    }
}
