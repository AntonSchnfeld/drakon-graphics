package io.github.antonschnfeld.drakon.graphics.reference;

import io.github.antonschnfeld.drakon.graphics.backend.GraphicsDevice;
import io.github.antonschnfeld.drakon.graphics.command.Color;
import io.github.antonschnfeld.drakon.graphics.command.ColorAttachmentOps;
import io.github.antonschnfeld.drakon.graphics.command.DepthAttachmentOps;
import io.github.antonschnfeld.drakon.graphics.command.RenderingInfo;
import io.github.antonschnfeld.drakon.graphics.pipeline.RenderPipeline;
import io.github.antonschnfeld.drakon.graphics.render.Renderer;
import io.github.antonschnfeld.drakon.graphics.resource.Binding;
import io.github.antonschnfeld.drakon.graphics.resource.BindingLayout;
import io.github.antonschnfeld.drakon.graphics.resource.BindingSet;
import io.github.antonschnfeld.drakon.graphics.resource.BindingSetDescriptor;
import io.github.antonschnfeld.drakon.graphics.resource.BlendState;
import io.github.antonschnfeld.drakon.graphics.resource.Buffer;
import io.github.antonschnfeld.drakon.graphics.resource.BufferBinding;
import io.github.antonschnfeld.drakon.graphics.resource.BufferDescriptor;
import io.github.antonschnfeld.drakon.graphics.resource.BufferUsage;
import io.github.antonschnfeld.drakon.graphics.resource.CullMode;
import io.github.antonschnfeld.drakon.graphics.resource.DepthState;
import io.github.antonschnfeld.drakon.graphics.resource.GraphicsState;
import io.github.antonschnfeld.drakon.graphics.resource.GraphicsStateDescriptor;
import io.github.antonschnfeld.drakon.graphics.resource.IndexType;
import io.github.antonschnfeld.drakon.graphics.resource.RasterState;
import io.github.antonschnfeld.drakon.graphics.resource.RenderTarget;
import io.github.antonschnfeld.drakon.graphics.resource.RenderTargetDescriptor;
import io.github.antonschnfeld.drakon.graphics.resource.ResourceState;
import io.github.antonschnfeld.drakon.graphics.resource.Sampler;
import io.github.antonschnfeld.drakon.graphics.resource.SamplerDescriptor;
import io.github.antonschnfeld.drakon.graphics.resource.Shader;
import io.github.antonschnfeld.drakon.graphics.resource.ShaderStage;
import io.github.antonschnfeld.drakon.graphics.resource.Texture;
import io.github.antonschnfeld.drakon.graphics.resource.TextureBinding;
import io.github.antonschnfeld.drakon.graphics.resource.TextureDescriptor;
import io.github.antonschnfeld.drakon.graphics.resource.TextureFormat;
import io.github.antonschnfeld.drakon.graphics.resource.TextureUsage;
import io.github.antonschnfeld.drakon.graphics.resource.VertexFormat;
import io.github.antonschnfeld.drakon.graphics.resource.VertexInputRate;
import io.github.antonschnfeld.drakon.graphics.resource.VertexLayout;
import io.github.antonschnfeld.drakon.graphics.shader.ShaderCode;
import io.github.antonschnfeld.drakon.graphics.shader.ShaderDescriptor;

import java.lang.foreign.Arena;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Backend-neutral renderer for the 0.1 reference application.
 *
 * <p>The application supplies a device, a presentation target, and shader code
 * already prepared for that device. This class owns every resource it creates,
 * but it does not own the supplied device or presentation target.</p>
 */
public final class ReferenceRenderer implements AutoCloseable {
    private final GraphicsDevice device;
    private final RenderTarget presentationTarget;
    private final Renderer renderer;
    private final ReferenceScene scene;
    private final Buffer cubeVertexBuffer;
    private final Buffer cubeIndexBuffer;
    private final Buffer instanceBuffer;
    private final Buffer panelVertexBuffer;
    private final Buffer panelIndexBuffer;
    private final Buffer panelInstanceBuffer;
    private final Buffer cameraBuffer;
    private final Buffer presentVertexBuffer;
    private final Buffer presentIndexBuffer;
    private final Texture sceneTexture;
    private final Sampler sceneSampler;
    private final Sampler presentSampler;
    private final BindingSet sceneBindingSet;
    private final Binding<TextureBinding> offscreenBinding;
    private final BindingLayout presentLayout;
    private final GraphicsState sceneState;
    private final GraphicsState transparentState;
    private final Shader presentVertexShader;
    private final Shader presentFragmentShader;
    private GraphicsState presentState;
    private TextureFormat presentationFormat;
    private OffscreenResources offscreen;
    private boolean closed;

    /**
     * Creates persistent renderer resources and initializes their portable states.
     *
     * @param device graphics device used for all resources and submissions
     * @param presentationTarget backend-supplied portable presentation target
     * @param shaders backend-targeted scene and fullscreen shader representations
     */
    public ReferenceRenderer(
            GraphicsDevice device,
            RenderTarget presentationTarget,
            ShaderResources.PreparedShaders shaders) {
        this.device = Objects.requireNonNull(device, "device");
        this.presentationTarget = Objects.requireNonNull(presentationTarget, "presentationTarget");
        Objects.requireNonNull(shaders, "shaders");
        renderer = new Renderer(device);

        try (Arena geometryArena = Arena.ofConfined()) {
            cubeVertexBuffer = device.createBuffer(
                    new BufferDescriptor(24L * 5 * Float.BYTES, Set.of(BufferUsage.VERTEX)),
                    SceneGeometry.cubeVertices(geometryArena));
            cubeIndexBuffer = device.createBuffer(
                    new BufferDescriptor(
                            SceneGeometry.CUBE_INDEX_COUNT * (long) Short.BYTES,
                            Set.of(BufferUsage.INDEX)),
                    SceneGeometry.cubeIndices(geometryArena));
            panelVertexBuffer = device.createBuffer(
                    new BufferDescriptor(4L * 5 * Float.BYTES, Set.of(BufferUsage.VERTEX)),
                    SceneGeometry.panelVertices(geometryArena));
            panelIndexBuffer = device.createBuffer(
                    new BufferDescriptor(
                            SceneGeometry.PANEL_INDEX_COUNT * (long) Short.BYTES,
                            Set.of(BufferUsage.INDEX)),
                    SceneGeometry.panelIndices(geometryArena));
            presentVertexBuffer = device.createBuffer(
                    new BufferDescriptor(4L * 4 * Float.BYTES, Set.of(BufferUsage.VERTEX)),
                    SceneGeometry.fullscreenVertices(geometryArena));
            presentIndexBuffer = device.createBuffer(
                    new BufferDescriptor(6L * Short.BYTES, Set.of(BufferUsage.INDEX)),
                    SceneGeometry.fullscreenIndices(geometryArena));
            sceneTexture = device.createTexture(
                    new TextureDescriptor(
                            SceneGeometry.TEXTURE_SIZE,
                            SceneGeometry.TEXTURE_SIZE,
                            TextureFormat.RGBA8_UNORM,
                            Set.of(TextureUsage.SAMPLED)),
                    SceneGeometry.checkerTexture(geometryArena),
                    ResourceState.SAMPLED_READ);
        }
        instanceBuffer = device.createBuffer(
                new BufferDescriptor(
                        ReferenceScene.OBJECT_COUNT * (long) ReferenceScene.INSTANCE_STRIDE,
                        Set.of(BufferUsage.VERTEX)));
        panelInstanceBuffer = device.createBuffer(
                new BufferDescriptor(ReferenceScene.INSTANCE_STRIDE, Set.of(BufferUsage.VERTEX)));
        cameraBuffer = device.createBuffer(
                new BufferDescriptor(16L * Float.BYTES, Set.of(BufferUsage.UNIFORM)));
        sceneSampler = device.createSampler(new SamplerDescriptor(
                SamplerDescriptor.Filter.NEAREST,
                SamplerDescriptor.Filter.NEAREST,
                SamplerDescriptor.AddressMode.REPEAT));
        presentSampler = device.createSampler(new SamplerDescriptor(
                SamplerDescriptor.Filter.LINEAR,
                SamplerDescriptor.Filter.LINEAR,
                SamplerDescriptor.AddressMode.CLAMP_TO_EDGE));

        Binding<BufferBinding> cameraBinding = Binding.uniformBuffer(
                "CameraData", 0, ShaderStage.VERTEX);
        Binding<TextureBinding> sceneTextureBinding = Binding.sampledTexture(
                "sceneTexture", 1, ShaderStage.FRAGMENT);
        BindingLayout sceneLayout = BindingLayout.of(cameraBinding, sceneTextureBinding);
        sceneBindingSet = device.createBindingSet(BindingSetDescriptor.builder(sceneLayout)
                .bind(cameraBinding, BufferBinding.whole(cameraBuffer))
                .bind(sceneTextureBinding, new TextureBinding(sceneTexture, sceneSampler))
                .build());

        offscreenBinding = Binding.sampledTexture(
                "offscreenColor", 0, ShaderStage.FRAGMENT);
        presentLayout = BindingLayout.of(offscreenBinding);

        try (Shader sceneVertex = createShader(ShaderStage.VERTEX, shaders.sceneVertex());
                Shader sceneFragment = createShader(ShaderStage.FRAGMENT, shaders.sceneFragment())) {
            sceneState = createSceneState(sceneVertex, sceneFragment, sceneLayout, false);
            transparentState = createSceneState(sceneVertex, sceneFragment, sceneLayout, true);
        }

        presentVertexShader = createShader(ShaderStage.VERTEX, shaders.postVertex());
        presentFragmentShader = createShader(ShaderStage.FRAGMENT, shaders.postFragment());
        presentationFormat = presentationTarget.colorFormats().get(0);
        presentState = createPresentState(presentationFormat);
        offscreen = createOffscreen(presentationTarget.width(), presentationTarget.height());

        initializeResourceStates(offscreen);
        scene = new ReferenceScene();
    }

    /**
     * Updates dynamic scene data and records the offscreen and fullscreen passes.
     *
     * @param elapsedSeconds application animation time in seconds
     */
    public void render(double elapsedSeconds) {
        requireOpen();
        scene.update(elapsedSeconds, offscreen.color().width(), offscreen.color().height());
        ensurePresentationStateCompatible();
        OffscreenResources frameOffscreen = offscreen;

        renderer.execute(RenderPipeline.of(commands -> {
            commands.writeBuffer(cameraBuffer, 0, scene.cameraData());
            commands.writeBuffer(instanceBuffer, 0, scene.objectData());
            commands.writeBuffer(panelInstanceBuffer, 0, scene.glassData());

            commands.beginRendering(RenderingInfo.builder(frameOffscreen.target())
                    .color(ColorAttachmentOps.clear(new Color(0.025f, 0.035f, 0.065f, 1.0f)))
                    .depth(DepthAttachmentOps.clear(1.0f))
                    .build());
            commands.setGraphicsState(sceneState);
            commands.setVertexBuffer(0, cubeVertexBuffer, 0);
            commands.setVertexBuffer(1, instanceBuffer, 0);
            commands.setIndexBuffer(cubeIndexBuffer, IndexType.UINT16, 0);
            commands.bindSet(0, sceneBindingSet);
            commands.drawIndexed(SceneGeometry.CUBE_INDEX_COUNT, 1, 0, 0, 0);
            commands.drawIndexed(SceneGeometry.CUBE_INDEX_COUNT, 1, 0, 0, 1);
            commands.drawIndexed(SceneGeometry.CUBE_INDEX_COUNT, 1, 0, 0, 2);

            commands.setGraphicsState(transparentState);
            commands.setVertexBuffer(0, panelVertexBuffer, 0);
            commands.setVertexBuffer(1, panelInstanceBuffer, 0);
            commands.setIndexBuffer(panelIndexBuffer, IndexType.UINT16, 0);
            commands.bindSet(0, sceneBindingSet);
            commands.drawIndexed(SceneGeometry.PANEL_INDEX_COUNT, 1, 0, 0, 0);
            commands.endRendering();

            commands.transition(
                    frameOffscreen.color(),
                    ResourceState.COLOR_ATTACHMENT_WRITE,
                    ResourceState.SAMPLED_READ);
            commands.beginRendering(RenderingInfo.builder(presentationTarget)
                    .color(ColorAttachmentOps.clear(Color.BLACK))
                    .build());
            commands.setGraphicsState(presentState);
            commands.setVertexBuffer(0, presentVertexBuffer, 0);
            commands.setIndexBuffer(presentIndexBuffer, IndexType.UINT16, 0);
            commands.bindSet(0, frameOffscreen.presentBindingSet());
            commands.drawIndexed(6, 1, 0, 0, 0);
            commands.endRendering();
            commands.transition(
                    frameOffscreen.color(),
                    ResourceState.SAMPLED_READ,
                    ResourceState.COLOR_ATTACHMENT_WRITE);
        }));
    }

    private Shader createShader(ShaderStage stage, ShaderCode code) {
        return device.createShader(new ShaderDescriptor(stage, "main", Objects.requireNonNull(code, "code")));
    }

    private GraphicsState createSceneState(
            Shader vertex,
            Shader fragment,
            BindingLayout layout,
            boolean transparent) {
        GraphicsStateDescriptor.Builder descriptor = GraphicsStateDescriptor.builder()
                .vertexShader(vertex)
                .fragmentShader(fragment)
                .vertexLayout(sceneVertexLayout())
                .bindingLayout(layout)
                .depth(transparent ? DepthState.readOnly() : DepthState.standard())
                .raster(transparent ? new RasterState(CullMode.NONE) : RasterState.standard())
                .colorFormat(TextureFormat.RGBA8_UNORM)
                .depthFormat(TextureFormat.D32_FLOAT);
        if (transparent) descriptor.blend(BlendState.alphaBlend());
        return device.createGraphicsState(descriptor.build());
    }

    private static VertexLayout sceneVertexLayout() {
        return VertexLayout.builder()
                .binding(0, 5 * Float.BYTES, VertexInputRate.PER_VERTEX)
                .attribute(0, 0, VertexFormat.FLOAT3, 0)
                .attribute(1, 0, VertexFormat.FLOAT2, 3 * Float.BYTES)
                .binding(1, ReferenceScene.INSTANCE_STRIDE, VertexInputRate.PER_INSTANCE)
                .attribute(2, 1, VertexFormat.FLOAT4, 0)
                .attribute(3, 1, VertexFormat.FLOAT4, 4 * Float.BYTES)
                .attribute(4, 1, VertexFormat.FLOAT4, 8 * Float.BYTES)
                .attribute(5, 1, VertexFormat.FLOAT4, 12 * Float.BYTES)
                .attribute(6, 1, VertexFormat.FLOAT4, 16 * Float.BYTES)
                .build();
    }

    private GraphicsState createPresentState(TextureFormat format) {
        return device.createGraphicsState(GraphicsStateDescriptor.builder()
                .vertexShader(presentVertexShader)
                .fragmentShader(presentFragmentShader)
                .vertexLayout(VertexLayout.builder()
                        .binding(0, 4 * Float.BYTES, VertexInputRate.PER_VERTEX)
                        .attribute(0, 0, VertexFormat.FLOAT2, 0)
                        .attribute(1, 0, VertexFormat.FLOAT2, 2 * Float.BYTES)
                        .build())
                .bindingLayout(presentLayout)
                .raster(new RasterState(CullMode.NONE))
                .colorFormat(format)
                .build());
    }

    private OffscreenResources createOffscreen(int width, int height) {
        Texture color = device.createTexture(new TextureDescriptor(
                width,
                height,
                TextureFormat.RGBA8_UNORM,
                Set.of(TextureUsage.COLOR_ATTACHMENT, TextureUsage.SAMPLED)));
        Texture depth = null;
        RenderTarget target = null;
        BindingSet bindingSet = null;
        try {
            depth = device.createTexture(new TextureDescriptor(
                    width,
                    height,
                    TextureFormat.D32_FLOAT,
                    Set.of(TextureUsage.DEPTH_ATTACHMENT)));
            target = device.createRenderTarget(new RenderTargetDescriptor(List.of(color), depth));
            bindingSet = device.createBindingSet(BindingSetDescriptor.builder(presentLayout)
                    .bind(offscreenBinding, new TextureBinding(color, presentSampler))
                    .build());
            return new OffscreenResources(color, depth, target, bindingSet);
        } catch (RuntimeException | Error failure) {
            if (bindingSet != null) bindingSet.close();
            if (target != null) target.close();
            if (depth != null) depth.close();
            color.close();
            throw failure;
        }
    }

    private void initializeResourceStates(OffscreenResources initialOffscreen) {
        renderer.execute(RenderPipeline.of(commands -> {
            commands.transition(cubeVertexBuffer, ResourceState.UNDEFINED, ResourceState.VERTEX_READ);
            commands.transition(cubeIndexBuffer, ResourceState.UNDEFINED, ResourceState.INDEX_READ);
            commands.transition(instanceBuffer, ResourceState.UNDEFINED, ResourceState.VERTEX_READ);
            commands.transition(panelVertexBuffer, ResourceState.UNDEFINED, ResourceState.VERTEX_READ);
            commands.transition(panelIndexBuffer, ResourceState.UNDEFINED, ResourceState.INDEX_READ);
            commands.transition(panelInstanceBuffer, ResourceState.UNDEFINED, ResourceState.VERTEX_READ);
            commands.transition(cameraBuffer, ResourceState.UNDEFINED, ResourceState.UNIFORM_READ);
            commands.transition(presentVertexBuffer, ResourceState.UNDEFINED, ResourceState.VERTEX_READ);
            commands.transition(presentIndexBuffer, ResourceState.UNDEFINED, ResourceState.INDEX_READ);
            commands.transition(
                    initialOffscreen.color(),
                    ResourceState.UNDEFINED,
                    ResourceState.COLOR_ATTACHMENT_WRITE);
            commands.transition(
                    initialOffscreen.depth(),
                    ResourceState.UNDEFINED,
                    ResourceState.DEPTH_ATTACHMENT_WRITE);
        }));
    }

    void resizeIfNeeded() {
        requireOpen();
        int width = presentationTarget.width();
        int height = presentationTarget.height();
        if (width <= 0 || height <= 0) return;
        if (offscreen.color().width() == width && offscreen.color().height() == height) return;

        OffscreenResources replacement = createOffscreen(width, height);
        try {
            renderer.execute(RenderPipeline.of(commands -> {
                commands.transition(
                        replacement.color(),
                        ResourceState.UNDEFINED,
                        ResourceState.COLOR_ATTACHMENT_WRITE);
                commands.transition(
                        replacement.depth(),
                        ResourceState.UNDEFINED,
                        ResourceState.DEPTH_ATTACHMENT_WRITE);
            }));
        } catch (RuntimeException | Error failure) {
            replacement.close();
            throw failure;
        }

        OffscreenResources previous = offscreen;
        offscreen = replacement;
        previous.close();
        System.out.printf(Locale.ROOT, "Resized offscreen attachments to %dx%d.%n", width, height);
    }

    private void ensurePresentationStateCompatible() {
        TextureFormat currentFormat = presentationTarget.colorFormats().get(0);
        if (currentFormat == presentationFormat) return;
        GraphicsState replacement = createPresentState(currentFormat);
        GraphicsState previous = presentState;
        presentState = replacement;
        presentationFormat = currentFormat;
        previous.close();
        System.out.printf(
                Locale.ROOT,
                "Presentation format changed to %s; rebuilt only fullscreen graphics state.%n",
                currentFormat);
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("reference renderer is closed");
    }

    /** Releases renderer-owned resources in dependency order. */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        presentState.close();
        presentFragmentShader.close();
        presentVertexShader.close();
        transparentState.close();
        sceneState.close();
        offscreen.close();
        sceneBindingSet.close();
        presentSampler.close();
        sceneSampler.close();
        sceneTexture.close();
        presentIndexBuffer.close();
        presentVertexBuffer.close();
        cameraBuffer.close();
        panelInstanceBuffer.close();
        panelIndexBuffer.close();
        panelVertexBuffer.close();
        instanceBuffer.close();
        cubeIndexBuffer.close();
        cubeVertexBuffer.close();
        scene.close();
    }

    /** Explicit ownership bundle for attachments recreated on resize. */
    private record OffscreenResources(
            Texture color,
            Texture depth,
            RenderTarget target,
            BindingSet presentBindingSet) implements AutoCloseable {
        @Override
        public void close() {
            presentBindingSet.close();
            target.close();
            depth.close();
            color.close();
        }
    }
}
