package io.github.antonschnfeld.drakon.graphics.probe;

import io.github.antonschnfeld.drakon.graphics.command.*;
import io.github.antonschnfeld.drakon.graphics.resource.*;
import io.github.antonschnfeld.drakon.graphics.command.CommandEncoder;
import io.github.antonschnfeld.drakon.graphics.command.CommandList;
import io.github.antonschnfeld.drakon.graphics.command.RenderingInfo;

import java.util.*;
import java.nio.ByteBuffer;

final class ProbeCommandEncoder implements CommandEncoder {
    private final ProbeGraphicsDevice owner;
    private final String backendName;
    private final boolean validation;
    private final Map<Texture, ResourceState> textureStates;
    private final Map<Buffer, ResourceState> bufferStates;
    private final List<String> ops = new ArrayList<>();
    private final List<ProbeBufferWrite> bufferWrites = new ArrayList<>();
    private final Map<Integer, Buffer> vertexBuffers = new HashMap<>();
    private final Map<Integer, ProbeResources.ProbeBindingSet> boundSets = new HashMap<>();

    private RenderingInfo activeRendering;
    private boolean finished;
    private ProbeResources.ProbeGraphicsState graphicsState;
    private Buffer indexBuffer;

    ProbeCommandEncoder(
            ProbeGraphicsDevice owner,
            String backendName,
            boolean validation,
            Map<Texture, ResourceState> textureStates,
            Map<Buffer, ResourceState> bufferStates) {
        this.owner = owner;
        this.backendName = backendName;
        this.validation = validation;
        this.textureStates = textureStates;
        this.bufferStates = bufferStates;
    }

    private void open() {
        if (finished) throw new IllegalStateException("encoder already finished");
    }

    private void outsideRendering(String operation) {
        if (activeRendering != null) throw new IllegalStateException(operation + " is not allowed inside a rendering scope");
    }

    private void owned(GpuResource resource) {
        owner.requireOwned(resource);
    }

    private static long debugId(GpuResource resource) {
        return ProbeGraphicsDevice.debugId(resource);
    }

    @Override public void beginRendering(RenderingInfo info) {
        open();
        Objects.requireNonNull(info, "info");
        if (activeRendering != null) throw new IllegalStateException("already rendering");
        owned(info.target());

        /*
         * Offscreen targets retain their caller-owned texture attachments inside
         * the backend implementation even though RenderTarget no longer exposes
         * them publicly. Those textures still require explicit transitions. A
         * presentation-backed target has no public textures, so its internal
         * acquire/layout transitions are deliberately backend-managed instead.
         */
        if (!(info.target() instanceof ProbeResources.ProbeRenderTarget probeTarget)) {
            throw new IllegalArgumentException("foreign render target");
        }
        probeTarget.colors().forEach(this::owned);
        if (probeTarget.depth() != null) owned(probeTarget.depth());
        if (validation && !probeTarget.presentable()) {
            for (Texture color : probeTarget.colors()) {
                requireTextureState(color, ResourceState.COLOR_ATTACHMENT_WRITE);
            }
            if (probeTarget.depth() != null) {
                requireTextureState(probeTarget.depth(), ResourceState.DEPTH_ATTACHMENT_WRITE);
            }
        }

        activeRendering = info;
        ops.add(backendName + ": beginRendering target=" + debugId(info.target()));
    }

    @Override public void endRendering() {
        open();
        if (activeRendering == null) throw new IllegalStateException("not rendering");
        activeRendering = null;
        ops.add(backendName + ": endRendering");
    }

    @Override public void setGraphicsState(GraphicsState state) {
        open();
        owned(Objects.requireNonNull(state, "state"));
        if (!(state instanceof ProbeResources.ProbeGraphicsState probe)) throw new IllegalArgumentException("foreign graphics state");
        graphicsState = probe;
        if (validation && activeRendering != null) validateTargetCompatibility(probe.descriptor(), activeRendering.target());
        ops.add(backendName + ": graphicsState=" + debugId(state));
    }

    @Override public void setVertexBuffer(int binding, Buffer buffer, long offset) {
        open();
        owned(Objects.requireNonNull(buffer, "buffer"));
        requireBufferUsage(buffer, BufferUsage.VERTEX);
        if (binding < 0 || offset < 0 || offset >= buffer.size()) throw new IllegalArgumentException("invalid vertex buffer binding");
        vertexBuffers.put(binding, buffer);
        ops.add(backendName + ": vertexBuffer[" + binding + "]=" + debugId(buffer));
    }

    @Override public void setIndexBuffer(Buffer buffer, IndexType indexType, long offset) {
        open();
        owned(Objects.requireNonNull(buffer, "buffer"));
        Objects.requireNonNull(indexType, "indexType");
        requireBufferUsage(buffer, BufferUsage.INDEX);
        if (offset < 0 || offset >= buffer.size() || offset % indexType.bytes() != 0) {
            throw new IllegalArgumentException("invalid index buffer offset");
        }
        indexBuffer = buffer;
        ops.add(backendName + ": indexBuffer=" + debugId(buffer));
    }

    @Override public void bindSet(int group, BindingSet set) {
        open();
        Objects.requireNonNull(set, "set");
        owned(set);
        if (group < 0) throw new IllegalArgumentException("group must be >= 0");
        if (!(set instanceof ProbeResources.ProbeBindingSet probeSet)) throw new IllegalArgumentException("foreign binding set");

        if (graphicsState == null) throw new IllegalStateException("bindSet requires an active graphics state");
        List<BindingLayout> expected = graphicsState.descriptor().bindingLayouts();

        if (group >= expected.size()) throw new IllegalArgumentException("binding group " + group + " is not declared by the active state");
        if (validation && expected.get(group) != set.layout()) {
            throw new IllegalArgumentException("binding set layout does not match active state group " + group);
        }
        boundSets.put(group, probeSet);
        ops.add(backendName + ": bindSet[" + group + "]=" + debugId(set));
    }

    @Override public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        open();
        requireGraphicsDraw(false);
        if (vertexCount <= 0 || instanceCount <= 0 || firstVertex < 0 || firstInstance < 0) {
            throw new IllegalArgumentException("invalid draw arguments");
        }
        ops.add(backendName + ": draw vertices=" + vertexCount + " instances=" + instanceCount);
    }

    @Override public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance) {
        open();
        requireGraphicsDraw(true);
        if (indexCount <= 0 || instanceCount <= 0 || firstIndex < 0 || firstInstance < 0) {
            throw new IllegalArgumentException("invalid indexed draw arguments");
        }
        ops.add(backendName + ": drawIndexed indices=" + indexCount + " instances=" + instanceCount);
    }

    @Override public void copyTexture(Texture source, Texture destination) {
        open();
        outsideRendering("copyTexture");
        owned(Objects.requireNonNull(source, "source"));
        owned(Objects.requireNonNull(destination, "destination"));
        requireTextureUsage(source, TextureUsage.COPY_SRC);
        requireTextureUsage(destination, TextureUsage.COPY_DST);
        if (source.width() != destination.width()
                || source.height() != destination.height()
                || source.format() != destination.format()) {
            throw new IllegalArgumentException("complete texture copies require identical dimensions and formats");
        }
        if (validation) {
            requireTextureState(source, ResourceState.COPY_SRC);
            requireTextureState(destination, ResourceState.COPY_DST);
        }
        ops.add(backendName + ": copyTexture " + debugId(source) + " -> " + debugId(destination));
    }

    @Override public void writeBuffer(Buffer buffer, long offset, ByteBuffer data) {
        open();
        outsideRendering("writeBuffer");
        owned(Objects.requireNonNull(buffer, "buffer"));
        Objects.requireNonNull(data, "data");
        int byteCount = data.remaining();
        validateWriteRange(buffer.size(), offset, byteCount);
        ResourceState state = bufferStates.getOrDefault(buffer, ResourceState.UNDEFINED);
        if (state != ResourceState.VERTEX_READ
                && state != ResourceState.INDEX_READ
                && state != ResourceState.UNIFORM_READ) {
            throw new IllegalStateException("buffer must be in VERTEX_READ, INDEX_READ, or UNIFORM_READ");
        }
        byte[] snapshot = new byte[byteCount];
        data.duplicate().get(snapshot);
        bufferWrites.add(new ProbeBufferWrite(buffer, offset, snapshot));
        ops.add(backendName + ": writeBuffer " + debugId(buffer) + " offset=" + offset
                + " size=" + byteCount);
    }

    private static void validateWriteRange(long bufferSize, long offset, int byteCount) {
        if (offset < 0) throw new IllegalArgumentException("buffer write offset must be non-negative");
        if (byteCount == 0) throw new IllegalArgumentException("buffer write must not be empty");
        if ((offset & 3L) != 0L) throw new IllegalArgumentException("buffer write offset must be four-byte aligned");
        if ((byteCount & 3) != 0) throw new IllegalArgumentException("buffer write size must be four-byte aligned");
        if (offset > bufferSize - byteCount) throw new IllegalArgumentException("buffer write exceeds destination bounds");
    }

    @Override public void transition(Texture texture, ResourceState from, ResourceState to) {
        open();
        outsideRendering("transition");
        owned(Objects.requireNonNull(texture, "texture"));
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (to == ResourceState.UNDEFINED) throw new IllegalArgumentException("cannot transition into UNDEFINED");
        validateTextureStateUsage(texture, from);
        validateTextureStateUsage(texture, to);
        ResourceState current = textureStates.getOrDefault(texture, ResourceState.UNDEFINED);
        if (validation && current != from) {
            throw new IllegalStateException("texture " + debugId(texture) + " expected " + from + " but was " + current);
        }
        textureStates.put(texture, to);
        ops.add(backendName + ": transitionTexture " + debugId(texture) + " " + from + " -> " + to);
    }

    @Override public void transition(Buffer buffer, ResourceState from, ResourceState to) {
        open();
        outsideRendering("transition");
        owned(Objects.requireNonNull(buffer, "buffer"));
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (to == ResourceState.UNDEFINED) throw new IllegalArgumentException("cannot transition into UNDEFINED");
        validateBufferStateUsage(buffer, from);
        validateBufferStateUsage(buffer, to);
        ResourceState current = bufferStates.getOrDefault(buffer, ResourceState.UNDEFINED);
        if (validation && current != from) {
            throw new IllegalStateException("buffer " + debugId(buffer) + " expected " + from + " but was " + current);
        }
        bufferStates.put(buffer, to);
        ops.add(backendName + ": transitionBuffer " + debugId(buffer) + " " + from + " -> " + to);
    }

    @Override public CommandList finish() {
        open();
        if (activeRendering != null) throw new IllegalStateException("rendering scope still open");
        finished = true;
        ProbeCommandList result = new ProbeCommandList(
                owner, List.copyOf(ops), List.copyOf(bufferWrites));
        ops.clear();
        bufferWrites.clear();
        owner.commandListCreated(result);
        return result;
    }

    @Override public void close() {
        if (finished) return;
        finished = true;
        activeRendering = null;
        ops.clear();
        bufferWrites.clear();
    }

    boolean terminal() { return finished; }
    int recordedBufferWriteCount() { return bufferWrites.size(); }

    private void requireGraphicsDraw(boolean indexed) {
        if (activeRendering == null) throw new IllegalStateException("draw outside rendering");
        if (graphicsState == null) throw new IllegalStateException("no graphics state");
        if (indexed && indexBuffer == null) throw new IllegalStateException("no index buffer");
        if (validation) {
            validateTargetCompatibility(graphicsState.descriptor(), activeRendering.target());
            for (VertexBinding binding : graphicsState.descriptor().vertexLayout().bindings()) {
                Buffer buffer = vertexBuffers.get(binding.binding());
                if (buffer == null) throw new IllegalStateException("missing vertex buffer binding " + binding.binding());
                ResourceState state = bufferStates.getOrDefault(buffer, ResourceState.UNDEFINED);
                if (state != ResourceState.UNDEFINED && state != ResourceState.VERTEX_READ) {
                    throw new IllegalStateException("vertex buffer requires VERTEX_READ after GPU writes");
                }
            }
            if (indexed) {
                ResourceState state = bufferStates.getOrDefault(indexBuffer, ResourceState.UNDEFINED);
                if (state != ResourceState.UNDEFINED && state != ResourceState.INDEX_READ) {
                    throw new IllegalStateException("index buffer requires INDEX_READ after GPU writes");
                }
            }
        }
        validateRequiredBindingSets(graphicsState.descriptor().bindingLayouts());
        validateBoundResourceStates();
    }

    private void validateRequiredBindingSets(List<BindingLayout> layouts) {
        if (!validation) return;
        for (int group = 0; group < layouts.size(); group++) {
            ProbeResources.ProbeBindingSet set = boundSets.get(group);
            if (set == null || set.layout() != layouts.get(group)) {
                throw new IllegalStateException("missing compatible binding set for group " + group);
            }
        }
    }

    private void validateBoundResourceStates() {
        if (!validation) return;
        List<BindingLayout> layouts = graphicsState.descriptor().bindingLayouts();

        for (int group = 0; group < layouts.size(); group++) {
            ProbeResources.ProbeBindingSet set = boundSets.get(group);
            if (set == null || set.layout() != layouts.get(group)) continue;
            for (Map.Entry<Binding<?>, Object> entry : set.descriptor().values().entrySet()) {
                Binding<?> binding = entry.getKey();
                Object value = entry.getValue();
                switch (binding.type()) {
                    case SAMPLED_TEXTURE -> requireTextureState(((TextureBinding) value).texture(), ResourceState.SAMPLED_READ);
                    case UNIFORM_BUFFER -> requireBufferState(((BufferBinding) value).buffer(), ResourceState.UNIFORM_READ);
                }
            }
        }
    }

    private void validateTargetCompatibility(GraphicsStateDescriptor descriptor, RenderTarget target) {
        List<TextureFormat> targetColors = target.colorFormats();
        if (!descriptor.colorFormats().equals(targetColors)) {
            throw new IllegalStateException("graphics state color formats do not match render target");
        }
        TextureFormat targetDepth = target.depthFormat();
        if (!descriptor.depthFormat().equals(Optional.ofNullable(targetDepth))) {
            throw new IllegalStateException("graphics state depth format does not match render target");
        }
    }

    private void requireTextureState(Texture texture, ResourceState expected) {
        ResourceState actual = textureStates.getOrDefault(texture, ResourceState.UNDEFINED);
        if (actual != expected) {
            throw new IllegalStateException("texture " + debugId(texture) + " expected state " + expected + " but was " + actual);
        }
    }

    private void requireBufferState(Buffer buffer, ResourceState expected) {
        ResourceState actual = bufferStates.getOrDefault(buffer, ResourceState.UNDEFINED);
        if (actual != expected) {
            throw new IllegalStateException("buffer " + debugId(buffer) + " expected state " + expected + " but was " + actual);
        }
    }

    private static void requireBufferUsage(Buffer buffer, BufferUsage usage) {
        if (!buffer.usage().contains(usage)) throw new IllegalArgumentException("buffer lacks required usage " + usage);
    }

    private static void requireTextureUsage(Texture texture, TextureUsage usage) {
        if (!texture.usage().contains(usage)) throw new IllegalArgumentException("texture lacks required usage " + usage);
    }

    private static void validateTextureStateUsage(Texture texture, ResourceState state) {
        switch (state) {
            case COLOR_ATTACHMENT_WRITE -> requireTextureUsage(texture, TextureUsage.COLOR_ATTACHMENT);
            case DEPTH_ATTACHMENT_WRITE -> requireTextureUsage(texture, TextureUsage.DEPTH_ATTACHMENT);
            case SAMPLED_READ -> requireTextureUsage(texture, TextureUsage.SAMPLED);
            case COPY_SRC -> requireTextureUsage(texture, TextureUsage.COPY_SRC);
            case COPY_DST -> requireTextureUsage(texture, TextureUsage.COPY_DST);
            case UNDEFINED -> { }
            case UNIFORM_READ, VERTEX_READ, INDEX_READ ->
                    throw new IllegalArgumentException("state " + state + " is not valid for textures");
        }
    }

    private static void validateBufferStateUsage(Buffer buffer, ResourceState state) {
        switch (state) {
            case UNIFORM_READ -> requireBufferUsage(buffer, BufferUsage.UNIFORM);
            case VERTEX_READ -> requireBufferUsage(buffer, BufferUsage.VERTEX);
            case INDEX_READ -> requireBufferUsage(buffer, BufferUsage.INDEX);
            case UNDEFINED -> { }
            case COPY_SRC, COPY_DST, SAMPLED_READ, COLOR_ATTACHMENT_WRITE, DEPTH_ATTACHMENT_WRITE ->
                    throw new IllegalArgumentException("state " + state + " is not valid for buffers");
        }
    }

    record ProbeBufferWrite(Buffer buffer, long offset, byte[] bytes) {
        ProbeBufferWrite {
            bytes = bytes.clone();
        }

        @Override public byte[] bytes() {
            return bytes.clone();
        }
    }
}
