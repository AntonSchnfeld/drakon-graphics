package io.github.antonschnfeld.drakon.graphics.spike;

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
import io.github.antonschnfeld.drakon.graphics.shader.GlslShaderCode;
import io.github.antonschnfeld.drakon.graphics.shader.ShaderCode;
import io.github.antonschnfeld.drakon.graphics.shader.ShaderDescriptor;
import io.github.antonschnfeld.drakon.graphics.shader.SpirvShaderCode;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_fragment_shader;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_vertex_shader;

/** Persistent resources and per-frame recording for the real 3D acceptance workload. */
final class ThreeDWorkload implements AutoCloseable {
    static final int INSTANCE_COUNT = 3;
    static final int INSTANCE_STRIDE = 20 * Float.BYTES;
    static final int INSTANCE_BUFFER_BYTES = INSTANCE_COUNT * INSTANCE_STRIDE;

    private static final int PRESENTATION_STRESS_FRAMES = 720;
    private static final int MULTI_SUBMIT_START_FRAME = 120;
    private static final int ACQUISITION_RACE_BEFORE_FRAME = 300;
    private static final int MINIMIZE_BEFORE_FRAME = 500;
    private static final int[] RESIZE_REQUEST_FRAMES = {60, 160, 260, 360, 460};
    private static final int[][] RESIZE_REQUESTS = {
            {640, 360},
            {1000, 600},
            {480, 720},
            {1280, 720},
            {800, 500}
    };
    private static final int OFFSCREEN_WIDTH = 800;
    private static final int OFFSCREEN_HEIGHT = 500;
    private static final int CUBE_INDEX_COUNT = 36;
    private static final int PANEL_INDEX_COUNT = 6;
    private static final int TRANSPARENT_INSTANCE_COUNT = 4;
    private static final int TRANSPARENT_INSTANCE_BUFFER_BYTES = TRANSPARENT_INSTANCE_COUNT * INSTANCE_STRIDE;
    private static final int NEAR_INSTANCE = 0;
    private static final int FAR_INSTANCE = 1;
    private static final int SIDE_INSTANCE = 2;
    private static final int FAR_PANEL_INSTANCE = 0;
    private static final int NEAR_PANEL_INSTANCE = 1;
    private static final int ZERO_ALPHA_MARKER_INSTANCE = 2;
    private static final int ONE_ALPHA_MARKER_INSTANCE = 3;

    private static final String OPENGL_SCENE_VERTEX = """
            #version 430
            layout(location = 0) in vec3 position;
            layout(location = 1) in vec2 uv;
            layout(location = 2) in vec4 model0;
            layout(location = 3) in vec4 model1;
            layout(location = 4) in vec4 model2;
            layout(location = 5) in vec4 model3;
            layout(location = 6) in vec4 tint;
            layout(std140, binding = 0) uniform CameraData {
                mat4 viewProjection;
            } camera;
            layout(location = 0) out vec2 interpolatedUv;
            layout(location = 1) out vec4 interpolatedTint;
            void main() {
                mat4 model = mat4(model0, model1, model2, model3);
                vec4 clip = camera.viewProjection * model * vec4(position, 1.0);
                clip.z = 2.0 * clip.z - clip.w;
                gl_Position = clip;
                interpolatedUv = uv;
                interpolatedTint = tint;
            }
            """;

    private static final String VULKAN_SCENE_VERTEX = """
            #version 450
            layout(location = 0) in vec3 position;
            layout(location = 1) in vec2 uv;
            layout(location = 2) in vec4 model0;
            layout(location = 3) in vec4 model1;
            layout(location = 4) in vec4 model2;
            layout(location = 5) in vec4 model3;
            layout(location = 6) in vec4 tint;
            layout(set = 0, binding = 0, std140) uniform CameraData {
                mat4 viewProjection;
            } camera;
            layout(location = 0) out vec2 interpolatedUv;
            layout(location = 1) out vec4 interpolatedTint;
            void main() {
                mat4 model = mat4(model0, model1, model2, model3);
                gl_Position = camera.viewProjection * model * vec4(position, 1.0);
                interpolatedUv = uv;
                interpolatedTint = tint;
            }
            """;

    private static final String OPENGL_SCENE_FRAGMENT = """
            #version 430
            layout(location = 0) in vec2 interpolatedUv;
            layout(location = 1) in vec4 interpolatedTint;
            layout(location = 0) out vec4 outColor;
            void main() {
                vec2 cell = floor(interpolatedUv * 4.0);
                float checker = mod(cell.x + cell.y, 2.0);
                float brightness = mix(0.55, 1.0, checker);
                outColor = vec4(interpolatedTint.rgb * brightness, interpolatedTint.a);
            }
            """;

    private static final String VULKAN_SCENE_FRAGMENT = OPENGL_SCENE_FRAGMENT
            .replace("#version 430", "#version 450");

    private static final String OPENGL_PRESENT_VERTEX = """
            #version 430
            layout(location = 0) in vec2 position;
            layout(location = 1) in vec2 uv;
            layout(location = 0) out vec2 interpolatedUv;
            void main() {
                gl_Position = vec4(position, 0.0, 1.0);
                interpolatedUv = uv;
            }
            """;

    private static final String VULKAN_PRESENT_VERTEX = OPENGL_PRESENT_VERTEX
            .replace("#version 430", "#version 450");

    private static final String OPENGL_PRESENT_FRAGMENT = """
            #version 430
            layout(binding = 0) uniform sampler2D offscreenColor;
            layout(location = 0) in vec2 interpolatedUv;
            layout(location = 0) out vec4 outColor;
            void main() {
                outColor = texture(offscreenColor, vec2(interpolatedUv.x, 1.0 - interpolatedUv.y));
            }
            """;

    private static final String VULKAN_PRESENT_FRAGMENT = """
            #version 450
            layout(set = 0, binding = 0) uniform sampler2D offscreenColor;
            layout(location = 0) in vec2 interpolatedUv;
            layout(location = 0) out vec4 outColor;
            void main() {
                outColor = texture(offscreenColor, interpolatedUv);
            }
            """;

    private static final String OPENGL_OVERLAY_VERTEX = """
            #version 430
            const vec2 positions[3] = vec2[3](
                vec2(-0.96, 0.94),
                vec2(-0.96, 0.72),
                vec2(-0.74, 0.94));
            void main() {
                gl_Position = vec4(positions[gl_VertexID], 0.0, 1.0);
            }
            """;

    private static final String VULKAN_OVERLAY_VERTEX = """
            #version 450
            const vec2 positions[3] = vec2[3](
                vec2(-0.96, 0.94),
                vec2(-0.96, 0.72),
                vec2(-0.74, 0.94));
            void main() {
                gl_Position = vec4(positions[gl_VertexIndex], 0.0, 1.0);
            }
            """;

    private static final String OPENGL_OVERLAY_FRAGMENT = """
            #version 430
            layout(location = 0) out vec4 outColor;
            void main() {
                outColor = vec4(1.0, 0.0, 0.75, 0.82);
            }
            """;

    private static final String VULKAN_OVERLAY_FRAGMENT = OPENGL_OVERLAY_FRAGMENT
            .replace("#version 430", "#version 450");

    private final GraphicsDevice device;
    private final RenderTarget presentationTarget;
    private final Renderer renderer;
    private final Buffer cubeVertexBuffer;
    private final Buffer cubeIndexBuffer;
    private final Buffer instanceBuffer;
    private final Buffer panelVertexBuffer;
    private final Buffer panelIndexBuffer;
    private final Buffer transparentInstanceBuffer;
    private final Buffer cameraBuffer;
    private final Buffer presentVertexBuffer;
    private final Buffer presentIndexBuffer;
    private final Texture offscreenColor;
    private final Texture depth;
    private final RenderTarget offscreenTarget;
    private final Sampler presentSampler;
    private final BindingSet cameraBindingSet;
    private final BindingSet presentBindingSet;
    private final BindingLayout presentLayout;
    private final GraphicsState sceneState;
    private final GraphicsState transparentState;
    private final Shader presentVertexShader;
    private final Shader presentFragmentShader;
    private final Shader overlayVertexShader;
    private final Shader overlayFragmentShader;
    private GraphicsState presentState;
    private GraphicsState overlayState;
    private TextureFormat presentationFormat;
    private final ByteBuffer cameraData = ByteBuffer.allocate(16 * Float.BYTES).order(ByteOrder.nativeOrder());
    private final ByteBuffer instanceData = ByteBuffer.allocate(INSTANCE_BUFFER_BYTES).order(ByteOrder.nativeOrder());
    private final long animationStartNanos = System.nanoTime();
    private boolean initialized;
    private int presentationStateRebuilds;

    static ThreeDWorkload createOpenGL(GraphicsDevice device, RenderTarget presentationTarget) {
        return create(
                device,
                presentationTarget,
                new GlslShaderCode(OPENGL_SCENE_VERTEX),
                new GlslShaderCode(OPENGL_SCENE_FRAGMENT),
                new GlslShaderCode(OPENGL_PRESENT_VERTEX),
                new GlslShaderCode(OPENGL_PRESENT_FRAGMENT),
                new GlslShaderCode(OPENGL_OVERLAY_VERTEX),
                new GlslShaderCode(OPENGL_OVERLAY_FRAGMENT));
    }

    static ThreeDWorkload createVulkan(GraphicsDevice device, RenderTarget presentationTarget) {
        return create(
                device,
                presentationTarget,
                new SpirvShaderCode(BackendSpikeMain.compileSpirv(
                        VULKAN_SCENE_VERTEX, shaderc_glsl_vertex_shader)),
                new SpirvShaderCode(BackendSpikeMain.compileSpirv(
                        VULKAN_SCENE_FRAGMENT, shaderc_glsl_fragment_shader)),
                new SpirvShaderCode(BackendSpikeMain.compileSpirv(
                        VULKAN_PRESENT_VERTEX, shaderc_glsl_vertex_shader)),
                new SpirvShaderCode(BackendSpikeMain.compileSpirv(
                        VULKAN_PRESENT_FRAGMENT, shaderc_glsl_fragment_shader)),
                new SpirvShaderCode(BackendSpikeMain.compileSpirv(
                        VULKAN_OVERLAY_VERTEX, shaderc_glsl_vertex_shader)),
                new SpirvShaderCode(BackendSpikeMain.compileSpirv(
                        VULKAN_OVERLAY_FRAGMENT, shaderc_glsl_fragment_shader)));
    }

    private static ThreeDWorkload create(
            GraphicsDevice device,
            RenderTarget presentationTarget,
            ShaderCode sceneVertexCode,
            ShaderCode sceneFragmentCode,
            ShaderCode presentVertexCode,
            ShaderCode presentFragmentCode,
            ShaderCode overlayVertexCode,
            ShaderCode overlayFragmentCode) {
        try (Shader sceneVertex = device.createShader(new ShaderDescriptor(
                        ShaderStage.VERTEX, "main", sceneVertexCode));
                Shader sceneFragment = device.createShader(new ShaderDescriptor(
                        ShaderStage.FRAGMENT, "main", sceneFragmentCode))) {
            Shader presentVertex = null;
            Shader presentFragment = null;
            Shader overlayVertex = null;
            Shader overlayFragment = null;
            boolean ownershipTransferred = false;
            try {
                presentVertex = device.createShader(new ShaderDescriptor(
                        ShaderStage.VERTEX, "main", presentVertexCode));
                presentFragment = device.createShader(new ShaderDescriptor(
                        ShaderStage.FRAGMENT, "main", presentFragmentCode));
                overlayVertex = device.createShader(new ShaderDescriptor(
                        ShaderStage.VERTEX, "main", overlayVertexCode));
                overlayFragment = device.createShader(new ShaderDescriptor(
                        ShaderStage.FRAGMENT, "main", overlayFragmentCode));
                ThreeDWorkload result = new ThreeDWorkload(
                        device,
                        presentationTarget,
                        sceneVertex,
                        sceneFragment,
                        presentVertex,
                        presentFragment,
                        overlayVertex,
                        overlayFragment);
                ownershipTransferred = true;
                return result;
            } finally {
                if (!ownershipTransferred) {
                    if (overlayFragment != null) overlayFragment.close();
                    if (overlayVertex != null) overlayVertex.close();
                    if (presentFragment != null) presentFragment.close();
                    if (presentVertex != null) presentVertex.close();
                }
            }
        }
    }

    private ThreeDWorkload(
            GraphicsDevice device,
            RenderTarget presentationTarget,
            Shader sceneVertex,
            Shader sceneFragment,
            Shader presentVertex,
            Shader presentFragment,
            Shader overlayVertex,
            Shader overlayFragment) {
        this.device = device;
        this.presentationTarget = presentationTarget;
        presentVertexShader = presentVertex;
        presentFragmentShader = presentFragment;
        overlayVertexShader = overlayVertex;
        overlayFragmentShader = overlayFragment;
        renderer = new Renderer(device);
        cubeVertexBuffer = device.createBuffer(
                new BufferDescriptor(24L * 5 * Float.BYTES, Set.of(BufferUsage.VERTEX)),
                cubeVertices());
        cubeIndexBuffer = device.createBuffer(
                new BufferDescriptor(CUBE_INDEX_COUNT * (long) Short.BYTES, Set.of(BufferUsage.INDEX)),
                cubeIndices());
        instanceBuffer = device.createBuffer(
                new BufferDescriptor(INSTANCE_BUFFER_BYTES, Set.of(BufferUsage.VERTEX)));
        panelVertexBuffer = device.createBuffer(
                new BufferDescriptor(4L * 5 * Float.BYTES, Set.of(BufferUsage.VERTEX)),
                panelVertices());
        panelIndexBuffer = device.createBuffer(
                new BufferDescriptor(PANEL_INDEX_COUNT * (long) Short.BYTES, Set.of(BufferUsage.INDEX)),
                panelIndices());
        transparentInstanceBuffer = device.createBuffer(
                new BufferDescriptor(TRANSPARENT_INSTANCE_BUFFER_BYTES, Set.of(BufferUsage.VERTEX)),
                transparentInstances());
        cameraBuffer = device.createBuffer(
                new BufferDescriptor(16L * Float.BYTES, Set.of(BufferUsage.UNIFORM)));
        presentVertexBuffer = device.createBuffer(
                new BufferDescriptor(4L * 4 * Float.BYTES, Set.of(BufferUsage.VERTEX)),
                presentVertices());
        presentIndexBuffer = device.createBuffer(
                new BufferDescriptor(6L * Short.BYTES, Set.of(BufferUsage.INDEX)),
                presentIndices());
        offscreenColor = device.createTexture(new TextureDescriptor(
                OFFSCREEN_WIDTH,
                OFFSCREEN_HEIGHT,
                TextureFormat.RGBA8_UNORM,
                Set.of(TextureUsage.COLOR_ATTACHMENT, TextureUsage.SAMPLED)));
        depth = device.createTexture(new TextureDescriptor(
                OFFSCREEN_WIDTH,
                OFFSCREEN_HEIGHT,
                TextureFormat.D32_FLOAT,
                Set.of(TextureUsage.DEPTH_ATTACHMENT)));
        offscreenTarget = device.createRenderTarget(
                new RenderTargetDescriptor(List.of(offscreenColor), depth));
        presentSampler = device.createSampler(new SamplerDescriptor(
                SamplerDescriptor.Filter.LINEAR,
                SamplerDescriptor.Filter.LINEAR,
                SamplerDescriptor.AddressMode.CLAMP_TO_EDGE));

        Binding<BufferBinding> cameraBinding = Binding.uniformBuffer(
                "CameraData", 0, ShaderStage.VERTEX);
        BindingLayout cameraLayout = BindingLayout.of(cameraBinding);
        cameraBindingSet = device.createBindingSet(BindingSetDescriptor.builder(cameraLayout)
                .bind(cameraBinding, BufferBinding.whole(cameraBuffer))
                .build());

        Binding<TextureBinding> colorBinding = Binding.sampledTexture(
                "offscreenColor", 0, ShaderStage.FRAGMENT);
        presentLayout = BindingLayout.of(colorBinding);
        presentBindingSet = device.createBindingSet(BindingSetDescriptor.builder(presentLayout)
                .bind(colorBinding, new TextureBinding(offscreenColor, presentSampler))
                .build());

        sceneState = device.createGraphicsState(GraphicsStateDescriptor.builder()
                .vertexShader(sceneVertex)
                .fragmentShader(sceneFragment)
                .vertexLayout(VertexLayout.builder()
                        .binding(0, 5 * Float.BYTES, VertexInputRate.PER_VERTEX)
                        .attribute(0, 0, VertexFormat.FLOAT3, 0)
                        .attribute(1, 0, VertexFormat.FLOAT2, 3 * Float.BYTES)
                        .binding(1, INSTANCE_STRIDE, VertexInputRate.PER_INSTANCE)
                        .attribute(2, 1, VertexFormat.FLOAT4, 0)
                        .attribute(3, 1, VertexFormat.FLOAT4, 4 * Float.BYTES)
                        .attribute(4, 1, VertexFormat.FLOAT4, 8 * Float.BYTES)
                        .attribute(5, 1, VertexFormat.FLOAT4, 12 * Float.BYTES)
                        .attribute(6, 1, VertexFormat.FLOAT4, 16 * Float.BYTES)
                        .build())
                .bindingLayout(cameraLayout)
                .depth(DepthState.standard())
                .raster(RasterState.standard())
                .colorFormat(TextureFormat.RGBA8_UNORM)
                .depthFormat(TextureFormat.D32_FLOAT)
                .build());
        transparentState = device.createGraphicsState(GraphicsStateDescriptor.builder()
                .vertexShader(sceneVertex)
                .fragmentShader(sceneFragment)
                .vertexLayout(VertexLayout.builder()
                        .binding(0, 5 * Float.BYTES, VertexInputRate.PER_VERTEX)
                        .attribute(0, 0, VertexFormat.FLOAT3, 0)
                        .attribute(1, 0, VertexFormat.FLOAT2, 3 * Float.BYTES)
                        .binding(1, INSTANCE_STRIDE, VertexInputRate.PER_INSTANCE)
                        .attribute(2, 1, VertexFormat.FLOAT4, 0)
                        .attribute(3, 1, VertexFormat.FLOAT4, 4 * Float.BYTES)
                        .attribute(4, 1, VertexFormat.FLOAT4, 8 * Float.BYTES)
                        .attribute(5, 1, VertexFormat.FLOAT4, 12 * Float.BYTES)
                        .attribute(6, 1, VertexFormat.FLOAT4, 16 * Float.BYTES)
                        .build())
                .bindingLayout(cameraLayout)
                .depth(DepthState.readOnly())
                .blend(BlendState.alphaBlend())
                .raster(new RasterState(CullMode.NONE))
                .colorFormat(TextureFormat.RGBA8_UNORM)
                .depthFormat(TextureFormat.D32_FLOAT)
                .build());
        presentationFormat = presentationTarget.colorFormats().get(0);
        presentState = device.createGraphicsState(GraphicsStateDescriptor.builder()
                .vertexShader(presentVertexShader)
                .fragmentShader(presentFragmentShader)
                .vertexLayout(VertexLayout.builder()
                        .binding(0, 4 * Float.BYTES, VertexInputRate.PER_VERTEX)
                        .attribute(0, 0, VertexFormat.FLOAT2, 0)
                        .attribute(1, 0, VertexFormat.FLOAT2, 2 * Float.BYTES)
                        .build())
                .bindingLayout(presentLayout)
                .raster(new RasterState(CullMode.NONE))
                .colorFormat(presentationFormat)
                .build());
        overlayState = device.createGraphicsState(GraphicsStateDescriptor.builder()
                .vertexShader(overlayVertexShader)
                .fragmentShader(overlayFragmentShader)
                .blend(BlendState.alphaBlend())
                .raster(new RasterState(CullMode.NONE))
                .colorFormat(presentationFormat)
                .build());
    }

    void runPresentationStress(
            String backendName, GlfwWindow window, boolean exerciseAcquisitionRace) {
        initialize();
        long startNanos = System.nanoTime();
        RenderTarget stableTarget = presentationTarget;
        int lastTargetWidth = presentationTarget.width();
        int lastTargetHeight = presentationTarget.height();
        int targetExtentChanges = 0;
        int resizeRequest = 0;
        int zeroExtentPauses = 0;
        boolean zeroObservedDuringMinimize = false;

        System.out.printf(Locale.ROOT,
                "%s presentation stress initial extent: raw=%dx%d target=%dx%d format=%s.%n",
                backendName,
                window.rawFramebufferWidth(),
                window.rawFramebufferHeight(),
                lastTargetWidth,
                lastTargetHeight,
                presentationFormat);

        for (int frame = 0; frame < PRESENTATION_STRESS_FRAMES; frame++) {
            if (resizeRequest < RESIZE_REQUESTS.length
                    && frame == RESIZE_REQUEST_FRAMES[resizeRequest]) {
                int[] requested = RESIZE_REQUESTS[resizeRequest];
                window.setWindowSize(requested[0], requested[1]);
                window.pollEvents();
                System.out.printf(Locale.ROOT,
                        "%s resize request %d: logical=%dx%d observed raw=%dx%d.%n",
                        backendName,
                        resizeRequest + 1,
                        requested[0],
                        requested[1],
                        window.rawFramebufferWidth(),
                        window.rawFramebufferHeight());
                resizeRequest++;
            }

            if (frame == MINIMIZE_BEFORE_FRAME) {
                zeroObservedDuringMinimize = exerciseMinimizeRestore(backendName, window);
                if (zeroObservedDuringMinimize) zeroExtentPauses++;
            }

            if (exerciseAcquisitionRace && frame == ACQUISITION_RACE_BEFORE_FRAME) {
                boolean zeroObserved = exerciseAcquisitionMinimizeRestore(backendName, window);
                zeroObservedDuringMinimize |= zeroObserved;
                if (zeroObserved) zeroExtentPauses++;
            }

            if (waitWhileFramebufferZero(window)) zeroExtentPauses++;
            renderFrame(frame >= MULTI_SUBMIT_START_FRAME);
            if (frame + 1 < PRESENTATION_STRESS_FRAMES) window.pollEvents();

            if (presentationTarget != stableTarget) {
                throw new AssertionError("presentation RenderTarget facade identity changed");
            }
            int targetWidth = presentationTarget.width();
            int targetHeight = presentationTarget.height();
            if (targetWidth != lastTargetWidth || targetHeight != lastTargetHeight) {
                targetExtentChanges++;
                lastTargetWidth = targetWidth;
                lastTargetHeight = targetHeight;
                System.out.printf(Locale.ROOT,
                        "%s presentation target extent changed to %dx%d after presented frame %d.%n",
                        backendName, targetWidth, targetHeight, frame + 1);
            }
        }
        double seconds = elapsedSeconds(startNanos);
        System.out.printf(Locale.ROOT,
                "%s presentation stress passed: %d frames, %d multi-submit frames, "
                        + "%d target extent changes, zero extent during minimize=%s, "
                        + "zero-extent pauses=%d, presentation-state rebuilds=%d, "
                        + "same target facade=true, persistent application resources=true "
                        + "in %.3f s (%.1f FPS).%n",
                backendName,
                PRESENTATION_STRESS_FRAMES,
                PRESENTATION_STRESS_FRAMES - MULTI_SUBMIT_START_FRAME,
                targetExtentChanges,
                zeroObservedDuringMinimize,
                zeroExtentPauses,
                presentationStateRebuilds,
                seconds,
                PRESENTATION_STRESS_FRAMES / seconds);
    }

    void runInteractive(GlfwWindow window) {
        initialize();
        while (!window.shouldClose()) {
            if (waitWhileFramebufferZero(window)) continue;
            renderFrame(true);
            window.pollEvents();
        }
    }

    private boolean exerciseAcquisitionMinimizeRestore(String backendName, GlfwWindow window) {
        int checkedWidth = window.rawFramebufferWidth();
        int checkedHeight = window.rawFramebufferHeight();
        if (checkedWidth <= 0 || checkedHeight <= 0) {
            throw new IllegalStateException("acquisition-race stress requires a positive initial extent");
        }

        window.iconify();
        boolean zeroObserved = false;
        long observationDeadline = System.nanoTime() + 2_000_000_000L;
        do {
            window.waitEvents(0.01);
            zeroObserved = window.rawFramebufferWidth() == 0 || window.rawFramebufferHeight() == 0;
            if (zeroObserved) break;
        } while (System.nanoTime() < observationDeadline);

        System.out.printf(Locale.ROOT,
                "%s acquisition-race stress: positive check=%dx%d, iconified=%s, raw=%dx%d, "
                        + "zeroObserved=%s.%n",
                backendName,
                checkedWidth,
                checkedHeight,
                window.isIconified(),
                window.rawFramebufferWidth(),
                window.rawFramebufferHeight(),
                zeroObserved);
        if (zeroObserved) {
            // Simulate the frame already committed by the application after its
            // positive check, then one retry while backend recreation is pending.
            renderFrame(true);
            renderFrame(true);
        }

        window.restore();
        long restoreDeadline = System.nanoTime() + 5_000_000_000L;
        while (window.rawFramebufferWidth() == 0 || window.rawFramebufferHeight() == 0) {
            if (System.nanoTime() >= restoreDeadline) {
                throw new IllegalStateException("GLFW framebuffer did not become renderable after restore");
            }
            window.waitEvents(0.05);
        }
        window.pollEvents();
        System.out.printf(Locale.ROOT,
                "%s acquisition-race restore: raw=%dx%d; same workload continues.%n",
                backendName, window.rawFramebufferWidth(), window.rawFramebufferHeight());
        return zeroObserved;
    }

    private static boolean exerciseMinimizeRestore(String backendName, GlfwWindow window) {
        window.iconify();
        boolean zeroObserved = false;
        long observationDeadline = System.nanoTime() + 2_000_000_000L;
        do {
            window.waitEvents(0.05);
            zeroObserved = window.rawFramebufferWidth() == 0 || window.rawFramebufferHeight() == 0;
            if (zeroObserved) break;
        } while (System.nanoTime() < observationDeadline);

        System.out.printf(Locale.ROOT,
                "%s minimize observation: iconified=%s raw=%dx%d zeroObserved=%s; "
                        + "no presentation work was issued during observation.%n",
                backendName,
                window.isIconified(),
                window.rawFramebufferWidth(),
                window.rawFramebufferHeight(),
                zeroObserved);

        window.restore();
        long restoreDeadline = System.nanoTime() + 5_000_000_000L;
        while (window.rawFramebufferWidth() == 0 || window.rawFramebufferHeight() == 0) {
            if (System.nanoTime() >= restoreDeadline) {
                throw new IllegalStateException("GLFW framebuffer did not become renderable after restore");
            }
            window.waitEvents(0.05);
        }
        window.pollEvents();
        System.out.printf(Locale.ROOT,
                "%s restore observation: raw=%dx%d; rendering resumes with existing resources.%n",
                backendName, window.rawFramebufferWidth(), window.rawFramebufferHeight());
        return zeroObserved;
    }

    private static boolean waitWhileFramebufferZero(GlfwWindow window) {
        boolean waited = false;
        while (!window.shouldClose()
                && (window.rawFramebufferWidth() == 0 || window.rawFramebufferHeight() == 0)) {
            waited = true;
            window.waitEvents(0.05);
        }
        return waited;
    }

    private void initialize() {
        if (initialized) return;
        renderer.execute(RenderPipeline.of(commands -> {
            commands.transition(cubeVertexBuffer, ResourceState.UNDEFINED, ResourceState.VERTEX_READ);
            commands.transition(cubeIndexBuffer, ResourceState.UNDEFINED, ResourceState.INDEX_READ);
            commands.transition(instanceBuffer, ResourceState.UNDEFINED, ResourceState.VERTEX_READ);
            commands.transition(panelVertexBuffer, ResourceState.UNDEFINED, ResourceState.VERTEX_READ);
            commands.transition(panelIndexBuffer, ResourceState.UNDEFINED, ResourceState.INDEX_READ);
            commands.transition(transparentInstanceBuffer, ResourceState.UNDEFINED, ResourceState.VERTEX_READ);
            commands.transition(cameraBuffer, ResourceState.UNDEFINED, ResourceState.UNIFORM_READ);
            commands.transition(presentVertexBuffer, ResourceState.UNDEFINED, ResourceState.VERTEX_READ);
            commands.transition(presentIndexBuffer, ResourceState.UNDEFINED, ResourceState.INDEX_READ);
            commands.transition(offscreenColor, ResourceState.UNDEFINED, ResourceState.COLOR_ATTACHMENT_WRITE);
            commands.transition(depth, ResourceState.UNDEFINED, ResourceState.DEPTH_ATTACHMENT_WRITE);
        }));
        initialized = true;
    }

    private void renderFrame(boolean overlaySubmission) {
        updateFrameData(elapsedSeconds(animationStartNanos));
        renderer.execute(RenderPipeline.of(commands -> {
            commands.writeBuffer(cameraBuffer, 0, cameraData);
            commands.writeBuffer(instanceBuffer, 0, instanceData);

            commands.beginRendering(RenderingInfo.builder(offscreenTarget)
                    .color(ColorAttachmentOps.clear(new Color(0.025f, 0.035f, 0.06f, 1.0f)))
                    .depth(DepthAttachmentOps.clear(1.0f))
                    .build());
            commands.setGraphicsState(sceneState);
            commands.setVertexBuffer(0, cubeVertexBuffer, 0);
            commands.setVertexBuffer(1, instanceBuffer, 0);
            commands.setIndexBuffer(cubeIndexBuffer, IndexType.UINT16, 0);
            commands.bindSet(0, cameraBindingSet);
            // The near cube is intentionally submitted before the overlapping
            // far cube so correct visibility cannot come from painter's order.
            commands.drawIndexed(CUBE_INDEX_COUNT, 1, 0, 0, NEAR_INSTANCE);
            commands.drawIndexed(CUBE_INDEX_COUNT, 1, 0, 0, FAR_INSTANCE);
            commands.drawIndexed(CUBE_INDEX_COUNT, 1, 0, 0, SIDE_INSTANCE);

            commands.setGraphicsState(transparentState);
            commands.setVertexBuffer(0, panelVertexBuffer, 0);
            commands.setVertexBuffer(1, transparentInstanceBuffer, 0);
            commands.setIndexBuffer(panelIndexBuffer, IndexType.UINT16, 0);
            commands.bindSet(0, cameraBindingSet);
            // Transparent draws are application-ordered back-to-front. Their
            // read-only depth state tests opaque depth without changing it.
            commands.drawIndexed(PANEL_INDEX_COUNT, 1, 0, 0, FAR_PANEL_INSTANCE);
            commands.drawIndexed(PANEL_INDEX_COUNT, 1, 0, 0, NEAR_PANEL_INSTANCE);
            // Small persistent markers exercise alpha=0 and alpha=1 RGB boundaries.
            commands.drawIndexed(PANEL_INDEX_COUNT, 1, 0, 0, ZERO_ALPHA_MARKER_INSTANCE);
            commands.drawIndexed(PANEL_INDEX_COUNT, 1, 0, 0, ONE_ALPHA_MARKER_INSTANCE);
            commands.endRendering();

            commands.transition(
                    offscreenColor,
                    ResourceState.COLOR_ATTACHMENT_WRITE,
                    ResourceState.SAMPLED_READ);
            commands.beginRendering(RenderingInfo.builder(presentationTarget)
                    .color(ColorAttachmentOps.clear(Color.BLACK))
                    .build());
            ensurePresentationStatesCompatible();
            commands.setGraphicsState(presentState);
            commands.setVertexBuffer(0, presentVertexBuffer, 0);
            commands.setIndexBuffer(presentIndexBuffer, IndexType.UINT16, 0);
            commands.bindSet(0, presentBindingSet);
            commands.drawIndexed(6, 1, 0, 0, 0);
            commands.endRendering();
            commands.transition(
                    offscreenColor,
                    ResourceState.SAMPLED_READ,
                    ResourceState.COLOR_ATTACHMENT_WRITE);
        }));
        if (overlaySubmission) {
            renderer.execute(RenderPipeline.of(commands -> {
                commands.beginRendering(RenderingInfo.builder(presentationTarget)
                        .color(ColorAttachmentOps.load())
                        .build());
                ensurePresentationStatesCompatible();
                commands.setGraphicsState(overlayState);
                commands.draw(3, 1, 0, 0);
                commands.endRendering();
            }));
        }
        device.present(presentationTarget);
    }

    private void ensurePresentationStatesCompatible() {
        TextureFormat currentFormat = presentationTarget.colorFormats().get(0);
        if (currentFormat == presentationFormat) return;

        GraphicsState nextPresent = device.createGraphicsState(GraphicsStateDescriptor.builder()
                .vertexShader(presentVertexShader)
                .fragmentShader(presentFragmentShader)
                .vertexLayout(VertexLayout.builder()
                        .binding(0, 4 * Float.BYTES, VertexInputRate.PER_VERTEX)
                        .attribute(0, 0, VertexFormat.FLOAT2, 0)
                        .attribute(1, 0, VertexFormat.FLOAT2, 2 * Float.BYTES)
                        .build())
                .bindingLayout(presentLayout)
                .raster(new RasterState(CullMode.NONE))
                .colorFormat(currentFormat)
                .build());
        GraphicsState nextOverlay;
        try {
            nextOverlay = device.createGraphicsState(GraphicsStateDescriptor.builder()
                    .vertexShader(overlayVertexShader)
                    .fragmentShader(overlayFragmentShader)
                    .blend(BlendState.alphaBlend())
                    .raster(new RasterState(CullMode.NONE))
                    .colorFormat(currentFormat)
                    .build());
        } catch (RuntimeException | Error failure) {
            nextPresent.close();
            throw failure;
        }

        GraphicsState previousPresent = presentState;
        GraphicsState previousOverlay = overlayState;
        presentState = nextPresent;
        overlayState = nextOverlay;
        presentationFormat = currentFormat;
        presentationStateRebuilds++;
        previousOverlay.close();
        previousPresent.close();
        System.out.printf(Locale.ROOT,
                "Presentation format changed to %s; rebuilt only presentation graphics states.%n",
                currentFormat);
    }

    private void updateFrameData(double seconds) {
        float time = (float) seconds;
        float eyeX = 0.55f * (float) Math.sin(time * 0.35f);
        float eyeY = 1.25f + 0.15f * (float) Math.sin(time * 0.27f);
        float eyeZ = 6.4f + 0.25f * (float) Math.cos(time * 0.31f);
        float[] view = ThreeDMath.lookAt(eyeX, eyeY, eyeZ, 0.15f, 0, -0.2f, 0, 1, 0);
        float[] projection = ThreeDMath.perspective(
                (float) Math.toRadians(50),
                (float) OFFSCREEN_WIDTH / OFFSCREEN_HEIGHT,
                0.1f,
                100.0f);
        float[] viewProjection = ThreeDMath.multiply(projection, view);
        cameraData.clear();
        ThreeDMath.put(cameraData, viewProjection);
        cameraData.flip();

        instanceData.clear();
        putInstance(
                ThreeDMath.multiply(
                        ThreeDMath.translation(0, -0.05f, 0.55f),
                        ThreeDMath.multiply(
                                ThreeDMath.rotationY(time * 0.55f),
                                ThreeDMath.rotationX(time * 0.31f))),
                1.0f, 0.30f, 0.16f, 1.0f);
        putInstance(
                ThreeDMath.multiply(
                        ThreeDMath.translation(0.10f, -0.05f, -0.95f),
                        ThreeDMath.multiply(
                                ThreeDMath.rotationY(0.4f - time * 0.35f),
                                ThreeDMath.rotationX(0.15f))),
                0.20f, 0.42f, 1.0f, 1.0f);
        putInstance(
                ThreeDMath.multiply(
                        ThreeDMath.translation(2.0f, 0.05f, -0.35f),
                        ThreeDMath.multiply(
                                ThreeDMath.rotationY(time * 0.70f),
                                ThreeDMath.rotationX(-time * 0.25f))),
                0.20f, 0.92f, 0.38f, 1.0f);
        instanceData.flip();
    }

    private void putInstance(float[] model, float red, float green, float blue, float alpha) {
        ThreeDMath.put(instanceData, model);
        instanceData.putFloat(red).putFloat(green).putFloat(blue).putFloat(alpha);
    }

    private static ByteBuffer cubeVertices() {
        ByteBuffer vertices = ByteBuffer.allocate(24 * 5 * Float.BYTES).order(ByteOrder.nativeOrder());
        putFace(vertices,
                -0.7f, -0.7f, 0.7f, 0.7f, -0.7f, 0.7f,
                0.7f, 0.7f, 0.7f, -0.7f, 0.7f, 0.7f);
        putFace(vertices,
                0.7f, -0.7f, -0.7f, -0.7f, -0.7f, -0.7f,
                -0.7f, 0.7f, -0.7f, 0.7f, 0.7f, -0.7f);
        putFace(vertices,
                0.7f, -0.7f, 0.7f, 0.7f, -0.7f, -0.7f,
                0.7f, 0.7f, -0.7f, 0.7f, 0.7f, 0.7f);
        putFace(vertices,
                -0.7f, -0.7f, -0.7f, -0.7f, -0.7f, 0.7f,
                -0.7f, 0.7f, 0.7f, -0.7f, 0.7f, -0.7f);
        putFace(vertices,
                -0.7f, 0.7f, 0.7f, 0.7f, 0.7f, 0.7f,
                0.7f, 0.7f, -0.7f, -0.7f, 0.7f, -0.7f);
        putFace(vertices,
                -0.7f, -0.7f, -0.7f, 0.7f, -0.7f, -0.7f,
                0.7f, -0.7f, 0.7f, -0.7f, -0.7f, 0.7f);
        return vertices.flip();
    }

    private static void putFace(ByteBuffer vertices, float... positions) {
        for (int vertex = 0; vertex < 4; vertex++) {
            int position = vertex * 3;
            vertices.putFloat(positions[position]);
            vertices.putFloat(positions[position + 1]);
            vertices.putFloat(positions[position + 2]);
            vertices.putFloat(vertex == 1 || vertex == 2 ? 1 : 0);
            vertices.putFloat(vertex >= 2 ? 1 : 0);
        }
    }

    private static ByteBuffer cubeIndices() {
        ByteBuffer indices = ByteBuffer.allocate(CUBE_INDEX_COUNT * Short.BYTES)
                .order(ByteOrder.nativeOrder());
        for (int face = 0; face < 6; face++) {
            int start = face * 4;
            indices.putShort((short) start).putShort((short) (start + 1)).putShort((short) (start + 2));
            indices.putShort((short) (start + 2)).putShort((short) (start + 3)).putShort((short) start);
        }
        return indices.flip();
    }

    private static ByteBuffer panelVertices() {
        return ByteBuffer.allocate(4 * 5 * Float.BYTES).order(ByteOrder.nativeOrder())
                .putFloat(-1).putFloat(-1).putFloat(0).putFloat(0).putFloat(0)
                .putFloat(1).putFloat(-1).putFloat(0).putFloat(1).putFloat(0)
                .putFloat(1).putFloat(1).putFloat(0).putFloat(1).putFloat(1)
                .putFloat(-1).putFloat(1).putFloat(0).putFloat(0).putFloat(1)
                .flip();
    }

    private static ByteBuffer panelIndices() {
        return ByteBuffer.allocate(PANEL_INDEX_COUNT * Short.BYTES).order(ByteOrder.nativeOrder())
                .putShort((short) 0).putShort((short) 1).putShort((short) 2)
                .putShort((short) 2).putShort((short) 3).putShort((short) 0)
                .flip();
    }

    private static ByteBuffer transparentInstances() {
        ByteBuffer instances = ByteBuffer.allocate(TRANSPARENT_INSTANCE_BUFFER_BYTES)
                .order(ByteOrder.nativeOrder());
        // Deliberately non-premultiplied RGB: far orange, then near cyan.
        putInstance(instances, transform(-0.35f, 0.10f, -1.75f, 1.65f, 1.05f),
                1.0f, 0.22f, 0.05f, 0.45f);
        putInstance(instances, transform(0.25f, -0.05f, 1.25f, 1.45f, 0.90f),
                0.05f, 0.95f, 0.85f, 0.55f);
        putInstance(instances, transform(-2.15f, 1.30f, 1.45f, 0.16f, 0.16f),
                1.0f, 0.0f, 1.0f, 0.0f);
        putInstance(instances, transform(-1.75f, 1.30f, 1.45f, 0.16f, 0.16f),
                1.0f, 0.95f, 0.10f, 1.0f);
        return instances.flip();
    }

    private static float[] transform(float x, float y, float z, float scaleX, float scaleY) {
        float[] transform = ThreeDMath.translation(x, y, z);
        transform[0] = scaleX;
        transform[5] = scaleY;
        return transform;
    }

    private static void putInstance(
            ByteBuffer destination,
            float[] model,
            float red,
            float green,
            float blue,
            float alpha) {
        ThreeDMath.put(destination, model);
        destination.putFloat(red).putFloat(green).putFloat(blue).putFloat(alpha);
    }

    private static ByteBuffer presentVertices() {
        return ByteBuffer.allocate(4 * 4 * Float.BYTES).order(ByteOrder.nativeOrder())
                .putFloat(-1).putFloat(1).putFloat(0).putFloat(0)
                .putFloat(-1).putFloat(-1).putFloat(0).putFloat(1)
                .putFloat(1).putFloat(-1).putFloat(1).putFloat(1)
                .putFloat(1).putFloat(1).putFloat(1).putFloat(0)
                .flip();
    }

    private static ByteBuffer presentIndices() {
        return ByteBuffer.allocate(6 * Short.BYTES).order(ByteOrder.nativeOrder())
                .putShort((short) 0).putShort((short) 1).putShort((short) 2)
                .putShort((short) 2).putShort((short) 3).putShort((short) 0)
                .flip();
    }

    private static double elapsedSeconds(long startNanos) {
        return (System.nanoTime() - startNanos) * 1.0e-9;
    }

    @Override
    public void close() {
        overlayState.close();
        presentState.close();
        overlayFragmentShader.close();
        overlayVertexShader.close();
        presentFragmentShader.close();
        presentVertexShader.close();
        transparentState.close();
        sceneState.close();
        presentBindingSet.close();
        cameraBindingSet.close();
        presentSampler.close();
        offscreenTarget.close();
        depth.close();
        offscreenColor.close();
        presentIndexBuffer.close();
        presentVertexBuffer.close();
        cameraBuffer.close();
        transparentInstanceBuffer.close();
        panelIndexBuffer.close();
        panelVertexBuffer.close();
        instanceBuffer.close();
        cubeIndexBuffer.close();
        cubeVertexBuffer.close();
    }
}
