package io.github.antonschnfeld.drakon.graphics.spike;

import io.github.antonschnfeld.drakon.graphics.backend.GraphicsDevice;
import io.github.antonschnfeld.drakon.graphics.command.Color;
import io.github.antonschnfeld.drakon.graphics.command.ColorAttachmentOps;
import io.github.antonschnfeld.drakon.graphics.command.CommandEncoder;
import io.github.antonschnfeld.drakon.graphics.command.DepthAttachmentOps;
import io.github.antonschnfeld.drakon.graphics.command.RenderingInfo;
import io.github.antonschnfeld.drakon.graphics.pipeline.RenderPipeline;
import io.github.antonschnfeld.drakon.graphics.render.Renderer;
import io.github.antonschnfeld.drakon.graphics.resource.Binding;
import io.github.antonschnfeld.drakon.graphics.resource.BindingLayout;
import io.github.antonschnfeld.drakon.graphics.resource.BindingSet;
import io.github.antonschnfeld.drakon.graphics.resource.BindingSetDescriptor;
import io.github.antonschnfeld.drakon.graphics.resource.Buffer;
import io.github.antonschnfeld.drakon.graphics.resource.BufferBinding;
import io.github.antonschnfeld.drakon.graphics.resource.BufferDescriptor;
import io.github.antonschnfeld.drakon.graphics.resource.BufferUsage;
import io.github.antonschnfeld.drakon.graphics.resource.DepthState;
import io.github.antonschnfeld.drakon.graphics.resource.GraphicsState;
import io.github.antonschnfeld.drakon.graphics.resource.GraphicsStateDescriptor;
import io.github.antonschnfeld.drakon.graphics.resource.IndexType;
import io.github.antonschnfeld.drakon.graphics.resource.RenderTarget;
import io.github.antonschnfeld.drakon.graphics.resource.ResourceState;
import io.github.antonschnfeld.drakon.graphics.resource.Shader;
import io.github.antonschnfeld.drakon.graphics.resource.ShaderStage;
import io.github.antonschnfeld.drakon.graphics.resource.TextureFormat;
import io.github.antonschnfeld.drakon.graphics.resource.VertexFormat;
import io.github.antonschnfeld.drakon.graphics.resource.VertexInputRate;
import io.github.antonschnfeld.drakon.graphics.resource.VertexLayout;
import io.github.antonschnfeld.drakon.graphics.shader.GlslShaderCode;
import io.github.antonschnfeld.drakon.graphics.shader.ShaderCode;
import io.github.antonschnfeld.drakon.graphics.shader.ShaderDescriptor;
import io.github.antonschnfeld.drakon.graphics.shader.SpirvShaderCode;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Locale;
import java.util.Set;

import static org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_fragment_shader;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_vertex_shader;

/** Focused real-backend workload with no application-owned image resources. */
final class DirectPresentationDepthWorkload implements AutoCloseable {
    private static final int FRAME_COUNT = 240;
    private static final int CUBE_VERTEX_COUNT = 24;
    private static final int CUBE_INDEX_COUNT = 36;
    private static final int CUBE_COUNT = 3;
    private static final int VERTEX_STRIDE = 6 * Float.BYTES;

    private static final String OPENGL_VERTEX = """
            #version 430
            layout(location = 0) in vec3 position;
            layout(location = 1) in vec3 color;
            layout(std140, binding = 0) uniform CameraData {
                mat4 viewProjection;
            } camera;
            layout(location = 0) out vec3 interpolatedColor;
            void main() {
                vec4 clip = camera.viewProjection * vec4(position, 1.0);
                clip.z = 2.0 * clip.z - clip.w;
                gl_Position = clip;
                interpolatedColor = color;
            }
            """;

    private static final String VULKAN_VERTEX = OPENGL_VERTEX
            .replace("#version 430", "#version 450")
            .replace("layout(std140, binding = 0)",
                    "layout(set = 0, binding = 0, std140)")
            .replace("vec4 clip = camera.viewProjection * vec4(position, 1.0);\n"
                            + "    clip.z = 2.0 * clip.z - clip.w;\n"
                            + "    gl_Position = clip;",
                    "gl_Position = camera.viewProjection * vec4(position, 1.0);");

    private static final String OPENGL_FRAGMENT = """
            #version 430
            layout(location = 0) in vec3 interpolatedColor;
            layout(location = 0) out vec4 outColor;
            void main() {
                outColor = vec4(interpolatedColor, 1.0);
            }
            """;

    private static final String VULKAN_FRAGMENT = OPENGL_FRAGMENT
            .replace("#version 430", "#version 450");

    private final GraphicsDevice device;
    private final RenderTarget target;
    private final Renderer renderer;
    private final Arena dataArena = Arena.ofConfined();
    private final MemorySegment cameraData = dataArena.allocate(16L * Float.BYTES, Float.BYTES);
    private final SegmentWriter cameraWriter = new SegmentWriter(cameraData);
    private final Buffer vertexBuffer;
    private final Buffer indexBuffer;
    private final Buffer cameraBuffer;
    private final BindingSet cameraBindingSet;
    private final GraphicsState graphicsState;
    private final TextureFormat colorFormat;
    private final TextureFormat depthFormat;
    private final long animationStartNanos = System.nanoTime();
    private boolean initialized;

    static DirectPresentationDepthWorkload createOpenGL(
            GraphicsDevice device,
            RenderTarget target) {
        return new DirectPresentationDepthWorkload(
                device,
                target,
                new GlslShaderCode(OPENGL_VERTEX),
                new GlslShaderCode(OPENGL_FRAGMENT));
    }

    static DirectPresentationDepthWorkload createVulkan(
            GraphicsDevice device,
            RenderTarget target) {
        return new DirectPresentationDepthWorkload(
                device,
                target,
                new SpirvShaderCode(BackendSpikeMain.compileSpirv(
                        VULKAN_VERTEX, shaderc_glsl_vertex_shader)),
                new SpirvShaderCode(BackendSpikeMain.compileSpirv(
                        VULKAN_FRAGMENT, shaderc_glsl_fragment_shader)));
    }

    private DirectPresentationDepthWorkload(
            GraphicsDevice device,
            RenderTarget target,
            ShaderCode vertexCode,
            ShaderCode fragmentCode) {
        this.device = device;
        this.target = target;
        renderer = new Renderer(device);
        colorFormat = target.colorFormats().get(0);
        depthFormat = target.depthFormat();
        if (depthFormat == null) {
            throw new IllegalStateException(
                    "direct presentation workload requires a depth attachment");
        }

        vertexBuffer = device.createBuffer(
                new BufferDescriptor(
                        CUBE_COUNT * (long) CUBE_VERTEX_COUNT * VERTEX_STRIDE,
                        Set.of(BufferUsage.VERTEX)),
                cubeVertices(dataArena));
        indexBuffer = device.createBuffer(
                new BufferDescriptor(
                        CUBE_COUNT * (long) CUBE_INDEX_COUNT * Short.BYTES,
                        Set.of(BufferUsage.INDEX)),
                cubeIndices(dataArena));
        cameraBuffer = device.createBuffer(
                new BufferDescriptor(16L * Float.BYTES, Set.of(BufferUsage.UNIFORM)));

        Binding<BufferBinding> cameraBinding = Binding.uniformBuffer(
                "CameraData", 0, ShaderStage.VERTEX);
        BindingLayout cameraLayout = BindingLayout.of(cameraBinding);
        cameraBindingSet = device.createBindingSet(BindingSetDescriptor.builder(cameraLayout)
                .bind(cameraBinding, BufferBinding.whole(cameraBuffer))
                .build());
        try (Shader vertex = device.createShader(new ShaderDescriptor(
                        ShaderStage.VERTEX, "main", vertexCode));
                Shader fragment = device.createShader(new ShaderDescriptor(
                        ShaderStage.FRAGMENT, "main", fragmentCode))) {
            graphicsState = device.createGraphicsState(GraphicsStateDescriptor.builder()
                    .vertexShader(vertex)
                    .fragmentShader(fragment)
                    .vertexLayout(VertexLayout.builder()
                            .binding(0, VERTEX_STRIDE, VertexInputRate.PER_VERTEX)
                            .attribute(0, 0, VertexFormat.FLOAT3, 0)
                            .attribute(1, 0, VertexFormat.FLOAT3, 3 * Float.BYTES)
                            .build())
                    .bindingLayout(cameraLayout)
                    .depth(DepthState.standard())
                    .colorFormat(colorFormat)
                    .depthFormat(depthFormat)
                    .build());
        }
    }

    void run(String backendName, GlfwWindow window) {
        initialize();
        RenderTarget stableTarget = target;
        long startNanos = System.nanoTime();
        boolean zeroObserved = false;
        for (int frame = 0; frame < FRAME_COUNT; frame++) {
            if (frame == 60) {
                window.setWindowSize(960, 540);
                window.pollEvents();
            }
            if (frame == 120) {
                zeroObserved = exerciseMinimizeRestore(backendName, window);
            }
            if (waitWhileFramebufferZero(window)) zeroObserved = true;
            renderFrame();
            if (frame + 1 < FRAME_COUNT) window.pollEvents();
            if (target != stableTarget) {
                throw new AssertionError("direct presentation target facade identity changed");
            }
            if (target.colorFormats().get(0) != colorFormat
                    || target.depthFormat() != depthFormat) {
                throw new IllegalStateException(
                        "direct presentation attachment formats changed during workload");
            }
        }
        double seconds = elapsedSeconds(startNanos);
        System.out.printf(Locale.ROOT,
                "%s direct depth presentation passed: %d frames, color=%s, depth=%s, "
                        + "no application textures, two depth-tested scopes per frame "
                        + "with second-scope LOAD, resize=true, "
                        + "zero extent during minimize=%s in %.3f s (%.1f FPS).%n",
                backendName,
                FRAME_COUNT,
                colorFormat,
                depthFormat,
                zeroObserved,
                seconds,
                FRAME_COUNT / seconds);
    }

    private void initialize() {
        if (initialized) return;
        renderer.execute(RenderPipeline.of(commands -> {
            commands.transition(vertexBuffer, ResourceState.UNDEFINED, ResourceState.VERTEX_READ);
            commands.transition(indexBuffer, ResourceState.UNDEFINED, ResourceState.INDEX_READ);
            commands.transition(cameraBuffer, ResourceState.UNDEFINED, ResourceState.UNIFORM_READ);
        }));
        initialized = true;
    }

    private void renderFrame() {
        updateCamera();
        renderer.execute(RenderPipeline.of(commands -> {
            commands.writeBuffer(cameraBuffer, 0, cameraData);
            commands.beginRendering(RenderingInfo.builder(target)
                    .color(ColorAttachmentOps.clear(new Color(0.025f, 0.035f, 0.06f, 1.0f)))
                    .depth(DepthAttachmentOps.clear(1.0f))
                    .build());
            bindGeometry(commands);
            // Near is deliberately submitted before the overlapping far cube;
            // correct visibility therefore depends on depth testing.
            commands.drawIndexed(CUBE_INDEX_COUNT, 1, 0, 0, 0);
            commands.drawIndexed(CUBE_INDEX_COUNT, 1, CUBE_INDEX_COUNT, 0, 0);
            commands.endRendering();

            commands.beginRendering(RenderingInfo.builder(target)
                    .color(ColorAttachmentOps.load())
                    .depth(DepthAttachmentOps.load())
                    .build());
            bindGeometry(commands);
            commands.drawIndexed(CUBE_INDEX_COUNT, 1, 2 * CUBE_INDEX_COUNT, 0, 0);
            commands.endRendering();
        }));
        device.present(target);
    }

    private void bindGeometry(CommandEncoder commands) {
        commands.setGraphicsState(graphicsState);
        commands.setVertexBuffer(0, vertexBuffer, 0);
        commands.setIndexBuffer(indexBuffer, IndexType.UINT16, 0);
        commands.bindSet(0, cameraBindingSet);
    }

    private void updateCamera() {
        float seconds = (float) elapsedSeconds(animationStartNanos);
        float eyeX = 0.45f * (float) Math.sin(seconds * 0.4f);
        float[] view = ThreeDMath.lookAt(
                eyeX, 1.2f, 6.4f,
                0.1f, 0.0f, -0.2f,
                0.0f, 1.0f, 0.0f);
        float[] projection = ThreeDMath.perspective(
                (float) Math.toRadians(50.0),
                (float) target.width() / target.height(),
                0.1f,
                100.0f);
        cameraWriter.rewind();
        ThreeDMath.put(cameraWriter, ThreeDMath.multiply(projection, view));
    }

    private static MemorySegment cubeVertices(Arena arena) {
        SegmentWriter vertices = new SegmentWriter(arena.allocate(
                CUBE_COUNT * (long) CUBE_VERTEX_COUNT * VERTEX_STRIDE,
                Float.BYTES));
        putCube(vertices, -0.1f, -0.05f, 0.55f, 1.0f, 0.28f, 0.14f);
        putCube(vertices, 0.1f, -0.05f, -0.95f, 0.18f, 0.40f, 1.0f);
        putCube(vertices, 1.75f, 0.10f, -0.25f, 0.20f, 0.92f, 0.42f);
        return vertices.writtenSegment();
    }

    private static void putCube(
            SegmentWriter vertices,
            float centerX,
            float centerY,
            float centerZ,
            float red,
            float green,
            float blue) {
        putFace(vertices, centerX, centerY, centerZ, red, green, blue,
                -0.7f, -0.7f, 0.7f, 0.7f, -0.7f, 0.7f,
                0.7f, 0.7f, 0.7f, -0.7f, 0.7f, 0.7f);
        putFace(vertices, centerX, centerY, centerZ, red, green, blue,
                0.7f, -0.7f, -0.7f, -0.7f, -0.7f, -0.7f,
                -0.7f, 0.7f, -0.7f, 0.7f, 0.7f, -0.7f);
        putFace(vertices, centerX, centerY, centerZ, red, green, blue,
                0.7f, -0.7f, 0.7f, 0.7f, -0.7f, -0.7f,
                0.7f, 0.7f, -0.7f, 0.7f, 0.7f, 0.7f);
        putFace(vertices, centerX, centerY, centerZ, red, green, blue,
                -0.7f, -0.7f, -0.7f, -0.7f, -0.7f, 0.7f,
                -0.7f, 0.7f, 0.7f, -0.7f, 0.7f, -0.7f);
        putFace(vertices, centerX, centerY, centerZ, red, green, blue,
                -0.7f, 0.7f, 0.7f, 0.7f, 0.7f, 0.7f,
                0.7f, 0.7f, -0.7f, -0.7f, 0.7f, -0.7f);
        putFace(vertices, centerX, centerY, centerZ, red, green, blue,
                -0.7f, -0.7f, -0.7f, 0.7f, -0.7f, -0.7f,
                0.7f, -0.7f, 0.7f, -0.7f, -0.7f, 0.7f);
    }

    private static void putFace(
            SegmentWriter vertices,
            float centerX,
            float centerY,
            float centerZ,
            float red,
            float green,
            float blue,
            float... positions) {
        for (int vertex = 0; vertex < 4; vertex++) {
            int position = vertex * 3;
            vertices.putFloat(centerX + positions[position]);
            vertices.putFloat(centerY + positions[position + 1]);
            vertices.putFloat(centerZ + positions[position + 2]);
            vertices.putFloat(red).putFloat(green).putFloat(blue);
        }
    }

    private static MemorySegment cubeIndices(Arena arena) {
        SegmentWriter indices = new SegmentWriter(arena.allocate(
                CUBE_COUNT * (long) CUBE_INDEX_COUNT * Short.BYTES,
                Short.BYTES));
        for (int cube = 0; cube < CUBE_COUNT; cube++) {
            int cubeStart = cube * CUBE_VERTEX_COUNT;
            for (int face = 0; face < 6; face++) {
                int start = cubeStart + face * 4;
                indices.putShort((short) start)
                        .putShort((short) (start + 1))
                        .putShort((short) (start + 2))
                        .putShort((short) (start + 2))
                        .putShort((short) (start + 3))
                        .putShort((short) start);
            }
        }
        return indices.writtenSegment();
    }

    private static boolean exerciseMinimizeRestore(
            String backendName,
            GlfwWindow window) {
        window.iconify();
        boolean zeroObserved = false;
        long observationDeadline = System.nanoTime() + 2_000_000_000L;
        do {
            window.waitEvents(0.05);
            zeroObserved = window.rawFramebufferWidth() == 0
                    || window.rawFramebufferHeight() == 0;
            if (zeroObserved) break;
        } while (System.nanoTime() < observationDeadline);
        System.out.printf(Locale.ROOT,
                "%s direct presentation minimize observation: "
                        + "iconified=%s raw=%dx%d zeroObserved=%s.%n",
                backendName,
                window.isIconified(),
                window.rawFramebufferWidth(),
                window.rawFramebufferHeight(),
                zeroObserved);

        window.restore();
        long restoreDeadline = System.nanoTime() + 5_000_000_000L;
        while (window.rawFramebufferWidth() == 0 || window.rawFramebufferHeight() == 0) {
            if (System.nanoTime() >= restoreDeadline) {
                throw new IllegalStateException(
                        "GLFW framebuffer did not become renderable after restore");
            }
            window.waitEvents(0.05);
        }
        window.pollEvents();
        return zeroObserved;
    }

    private static boolean waitWhileFramebufferZero(GlfwWindow window) {
        boolean waited = false;
        while (!window.shouldClose()
                && (window.rawFramebufferWidth() == 0
                        || window.rawFramebufferHeight() == 0)) {
            waited = true;
            window.waitEvents(0.05);
        }
        return waited;
    }

    private static double elapsedSeconds(long startNanos) {
        return (System.nanoTime() - startNanos) * 1.0e-9;
    }

    @Override
    public void close() {
        graphicsState.close();
        cameraBindingSet.close();
        cameraBuffer.close();
        indexBuffer.close();
        vertexBuffer.close();
        dataArena.close();
    }
}
