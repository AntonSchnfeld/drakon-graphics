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

    private static final int ACCEPTANCE_FRAMES = 240;
    private static final int OFFSCREEN_WIDTH = 800;
    private static final int OFFSCREEN_HEIGHT = 500;
    private static final int CUBE_INDEX_COUNT = 36;
    private static final int NEAR_INSTANCE = 0;
    private static final int FAR_INSTANCE = 1;
    private static final int SIDE_INSTANCE = 2;

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

    private final GraphicsDevice device;
    private final RenderTarget presentationTarget;
    private final Renderer renderer;
    private final Buffer cubeVertexBuffer;
    private final Buffer cubeIndexBuffer;
    private final Buffer instanceBuffer;
    private final Buffer cameraBuffer;
    private final Buffer presentVertexBuffer;
    private final Buffer presentIndexBuffer;
    private final Texture offscreenColor;
    private final Texture depth;
    private final RenderTarget offscreenTarget;
    private final Sampler presentSampler;
    private final BindingSet cameraBindingSet;
    private final BindingSet presentBindingSet;
    private final GraphicsState sceneState;
    private final GraphicsState presentState;
    private final ByteBuffer cameraData = ByteBuffer.allocate(16 * Float.BYTES).order(ByteOrder.nativeOrder());
    private final ByteBuffer instanceData = ByteBuffer.allocate(INSTANCE_BUFFER_BYTES).order(ByteOrder.nativeOrder());
    private final long animationStartNanos = System.nanoTime();
    private boolean initialized;

    static ThreeDWorkload createOpenGL(GraphicsDevice device, RenderTarget presentationTarget) {
        return create(
                device,
                presentationTarget,
                new GlslShaderCode(OPENGL_SCENE_VERTEX),
                new GlslShaderCode(OPENGL_SCENE_FRAGMENT),
                new GlslShaderCode(OPENGL_PRESENT_VERTEX),
                new GlslShaderCode(OPENGL_PRESENT_FRAGMENT));
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
                        VULKAN_PRESENT_FRAGMENT, shaderc_glsl_fragment_shader)));
    }

    private static ThreeDWorkload create(
            GraphicsDevice device,
            RenderTarget presentationTarget,
            ShaderCode sceneVertexCode,
            ShaderCode sceneFragmentCode,
            ShaderCode presentVertexCode,
            ShaderCode presentFragmentCode) {
        try (Shader sceneVertex = device.createShader(new ShaderDescriptor(
                        ShaderStage.VERTEX, "main", sceneVertexCode));
                Shader sceneFragment = device.createShader(new ShaderDescriptor(
                        ShaderStage.FRAGMENT, "main", sceneFragmentCode));
                Shader presentVertex = device.createShader(new ShaderDescriptor(
                        ShaderStage.VERTEX, "main", presentVertexCode));
                Shader presentFragment = device.createShader(new ShaderDescriptor(
                        ShaderStage.FRAGMENT, "main", presentFragmentCode))) {
            return new ThreeDWorkload(
                    device, presentationTarget, sceneVertex, sceneFragment, presentVertex, presentFragment);
        }
    }

    private ThreeDWorkload(
            GraphicsDevice device,
            RenderTarget presentationTarget,
            Shader sceneVertex,
            Shader sceneFragment,
            Shader presentVertex,
            Shader presentFragment) {
        this.device = device;
        this.presentationTarget = presentationTarget;
        renderer = new Renderer(device);
        cubeVertexBuffer = device.createBuffer(
                new BufferDescriptor(24L * 5 * Float.BYTES, Set.of(BufferUsage.VERTEX)),
                cubeVertices());
        cubeIndexBuffer = device.createBuffer(
                new BufferDescriptor(CUBE_INDEX_COUNT * (long) Short.BYTES, Set.of(BufferUsage.INDEX)),
                cubeIndices());
        instanceBuffer = device.createBuffer(
                new BufferDescriptor(INSTANCE_BUFFER_BYTES, Set.of(BufferUsage.VERTEX)));
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
        BindingLayout presentLayout = BindingLayout.of(colorBinding);
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
        presentState = device.createGraphicsState(GraphicsStateDescriptor.builder()
                .vertexShader(presentVertex)
                .fragmentShader(presentFragment)
                .vertexLayout(VertexLayout.builder()
                        .binding(0, 4 * Float.BYTES, VertexInputRate.PER_VERTEX)
                        .attribute(0, 0, VertexFormat.FLOAT2, 0)
                        .attribute(1, 0, VertexFormat.FLOAT2, 2 * Float.BYTES)
                        .build())
                .bindingLayout(presentLayout)
                .raster(new RasterState(CullMode.NONE))
                .colorFormat(presentationTarget.colorFormats().get(0))
                .build());
    }

    void runAcceptance(String backendName, Runnable pollEvents) {
        initialize();
        long startNanos = System.nanoTime();
        for (int frame = 0; frame < ACCEPTANCE_FRAMES; frame++) {
            renderFrame();
            pollEvents.run();
        }
        double seconds = elapsedSeconds(startNanos);
        System.out.printf(Locale.ROOT,
                "%s 3D acceptance: %d frames in %.3f s (%.1f FPS).%n",
                backendName, ACCEPTANCE_FRAMES, seconds, ACCEPTANCE_FRAMES / seconds);
    }

    void runInteractive(BooleanSupplier shouldClose, Runnable pollEvents) {
        initialize();
        while (!shouldClose.getAsBoolean()) {
            renderFrame();
            pollEvents.run();
        }
    }

    private void initialize() {
        if (initialized) return;
        renderer.execute(RenderPipeline.of(commands -> {
            commands.transition(cubeVertexBuffer, ResourceState.UNDEFINED, ResourceState.VERTEX_READ);
            commands.transition(cubeIndexBuffer, ResourceState.UNDEFINED, ResourceState.INDEX_READ);
            commands.transition(instanceBuffer, ResourceState.UNDEFINED, ResourceState.VERTEX_READ);
            commands.transition(cameraBuffer, ResourceState.UNDEFINED, ResourceState.UNIFORM_READ);
            commands.transition(presentVertexBuffer, ResourceState.UNDEFINED, ResourceState.VERTEX_READ);
            commands.transition(presentIndexBuffer, ResourceState.UNDEFINED, ResourceState.INDEX_READ);
            commands.transition(offscreenColor, ResourceState.UNDEFINED, ResourceState.COLOR_ATTACHMENT_WRITE);
            commands.transition(depth, ResourceState.UNDEFINED, ResourceState.DEPTH_ATTACHMENT_WRITE);
        }));
        initialized = true;
    }

    private void renderFrame() {
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
            commands.endRendering();

            commands.transition(
                    offscreenColor,
                    ResourceState.COLOR_ATTACHMENT_WRITE,
                    ResourceState.SAMPLED_READ);
            commands.beginRendering(RenderingInfo.builder(presentationTarget)
                    .color(ColorAttachmentOps.clear(Color.BLACK))
                    .build());
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
        device.present(presentationTarget);
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
        presentState.close();
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
        instanceBuffer.close();
        cubeIndexBuffer.close();
        cubeVertexBuffer.close();
    }

    @FunctionalInterface
    interface BooleanSupplier {
        boolean getAsBoolean();
    }
}
