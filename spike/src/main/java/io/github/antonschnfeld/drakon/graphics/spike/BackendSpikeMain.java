package io.github.antonschnfeld.drakon.graphics.spike;

import io.github.antonschnfeld.drakon.graphics.backend.GraphicsDeviceConfig;
import io.github.antonschnfeld.drakon.graphics.command.Color;
import io.github.antonschnfeld.drakon.graphics.command.ColorAttachmentOps;
import io.github.antonschnfeld.drakon.graphics.command.RenderingInfo;
import io.github.antonschnfeld.drakon.graphics.opengl.OpenGLBackend;
import io.github.antonschnfeld.drakon.graphics.opengl.OpenGLDevice;
import io.github.antonschnfeld.drakon.graphics.backend.GraphicsDevice;
import io.github.antonschnfeld.drakon.graphics.pipeline.RenderPass;
import io.github.antonschnfeld.drakon.graphics.pipeline.RenderPipeline;
import io.github.antonschnfeld.drakon.graphics.render.Renderer;
import io.github.antonschnfeld.drakon.graphics.resource.GraphicsState;
import io.github.antonschnfeld.drakon.graphics.resource.GraphicsStateDescriptor;
import io.github.antonschnfeld.drakon.graphics.resource.Buffer;
import io.github.antonschnfeld.drakon.graphics.resource.BufferDescriptor;
import io.github.antonschnfeld.drakon.graphics.resource.BufferUsage;
import io.github.antonschnfeld.drakon.graphics.resource.RenderTarget;
import io.github.antonschnfeld.drakon.graphics.resource.Shader;
import io.github.antonschnfeld.drakon.graphics.resource.ShaderStage;
import io.github.antonschnfeld.drakon.graphics.resource.ResourceState;
import io.github.antonschnfeld.drakon.graphics.resource.Texture;
import io.github.antonschnfeld.drakon.graphics.resource.TextureDescriptor;
import io.github.antonschnfeld.drakon.graphics.resource.TextureFormat;
import io.github.antonschnfeld.drakon.graphics.resource.TextureUsage;
import io.github.antonschnfeld.drakon.graphics.shader.GlslShaderCode;
import io.github.antonschnfeld.drakon.graphics.shader.ShaderDescriptor;
import io.github.antonschnfeld.drakon.graphics.shader.SpirvShaderCode;
import io.github.antonschnfeld.drakon.graphics.vulkan.VulkanBackend;
import io.github.antonschnfeld.drakon.graphics.vulkan.VulkanDevice;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.Set;

import static org.lwjgl.util.shaderc.Shaderc.*;

/**
 * Executable real-backend smoke test for the backend spike.
 *
 * <p>The program deliberately uses only the portable rendering API after each
 * backend-specific window bootstrap. Shaderc appears here only to produce the
 * SPIR-V that a future {@code drakon-shaders} module would normally provide.</p>
 */
public final class BackendSpikeMain {
    private static final int LARGE_HEAP_INITIALIZATION_BYTES = 1024 * 1024;

    private static final String VERTEX_GLSL = """
            #version 450
            layout(location = 0) out vec3 color;
            // Counter-clockwise clip-space winding matches RasterState.standard().
            const vec2 positions[3] = vec2[](
                vec2(-0.65,  0.55),
                vec2( 0.00, -0.65),
                vec2( 0.65,  0.55)
            );
            const vec3 colors[3] = vec3[](
                vec3(1.0, 0.2, 0.2),
                vec3(0.2, 0.4, 1.0),
                vec3(0.2, 1.0, 0.2)
            );
            void main() {
                gl_Position = vec4(positions[gl_VertexIndex], 0.0, 1.0);
                color = colors[gl_VertexIndex];
            }
            """;

    private static final String OPENGL_VERTEX_GLSL = VERTEX_GLSL.replace("gl_VertexIndex", "gl_VertexID");

    private static final String FRAGMENT_GLSL = """
            #version 450
            layout(location = 0) in vec3 color;
            layout(location = 0) out vec4 outColor;
            void main() {
                outColor = vec4(color, 1.0);
            }
            """;

    private BackendSpikeMain() {}

    /**
     * Runs one or both windowed backend smoke tests.
     *
     * @param args optional {@code opengl}, {@code vulkan}, or {@code both};
     *             default is {@code both}
     */
    public static void main(String[] args) {
        String backend = args.length == 0 ? "both" : args[0].toLowerCase(Locale.ROOT);
        switch (backend) {
            case "opengl" -> runOpenGL();
            case "vulkan" -> runVulkan();
            case "both" -> {
                runOpenGL();
                runVulkan();
            }
            default -> throw new IllegalArgumentException("expected opengl, vulkan, or both");
        }
    }

    private static void runOpenGL() {
        OpenGLBackend backend = new OpenGLBackend();
        try (OpenGLDevice device = backend.createWindowedDevice(
            GraphicsDeviceConfig.debug(), 800, 500, "drakon-graphics OpenGL spike")) {
            try (Buffer heapInitializedBuffer = createInitializationSmokeBuffer(device, false);
                    Buffer directInitializedBuffer = createInitializationSmokeBuffer(device, true);
                    Buffer largeHeapInitializedBuffer = createLargeHeapInitializationSmokeBuffer(device);
                    Texture heapInitializedTexture = createInitializationSmokeTexture(device, false);
                    Texture directInitializedTexture = createInitializationSmokeTexture(device, true)) {
                verifyInitializationSmokeBuffer(heapInitializedBuffer);
                verifyInitializationSmokeBuffer(directInitializedBuffer);
                verifyLargeHeapInitializationSmokeBuffer(largeHeapInitializedBuffer);
                verifyInitializationSmokeTexture(heapInitializedTexture);
                verifyInitializationSmokeTexture(directInitializedTexture);
                Shader vertex = device.createShader(new ShaderDescriptor(
                        ShaderStage.VERTEX, "main", new GlslShaderCode(OPENGL_VERTEX_GLSL)));
                Shader fragment = device.createShader(new ShaderDescriptor(
                        ShaderStage.FRAGMENT, "main", new GlslShaderCode(FRAGMENT_GLSL)));
                runWindowLoop(device, device.defaultRenderTarget(), vertex, fragment, device::shouldClose, device::pollEvents);
            }
        }
    }

    private static void runVulkan() {
        VulkanBackend backend = new VulkanBackend();
        try (VulkanDevice device = backend.createWindowedDevice(
            GraphicsDeviceConfig.debug(), 800, 500, "drakon-graphics Vulkan spike")) {
            try (Buffer heapInitializedBuffer = createInitializationSmokeBuffer(device, false);
                    Buffer directInitializedBuffer = createInitializationSmokeBuffer(device, true);
                    Buffer largeHeapInitializedBuffer = createLargeHeapInitializationSmokeBuffer(device);
                    Texture heapInitializedTexture = createInitializationSmokeTexture(device, false);
                    Texture directInitializedTexture = createInitializationSmokeTexture(device, true)) {
                verifyInitializationSmokeBuffer(heapInitializedBuffer);
                verifyInitializationSmokeBuffer(directInitializedBuffer);
                verifyLargeHeapInitializationSmokeBuffer(largeHeapInitializedBuffer);
                verifyInitializationSmokeTexture(heapInitializedTexture);
                verifyInitializationSmokeTexture(directInitializedTexture);
                Shader vertex = device.createShader(new ShaderDescriptor(
                        ShaderStage.VERTEX, "main",
                        new SpirvShaderCode(compileSpirv(VERTEX_GLSL, shaderc_glsl_vertex_shader))));
                Shader fragment = device.createShader(new ShaderDescriptor(
                        ShaderStage.FRAGMENT, "main",
                        new SpirvShaderCode(compileSpirv(FRAGMENT_GLSL, shaderc_glsl_fragment_shader))));
                runWindowLoop(device, device.defaultRenderTarget(), vertex, fragment, device::shouldClose, device::pollEvents);
            }
        }
    }

    /** Exercises heap and direct buffer initialization before the existing triangle loop. */
    private static Buffer createInitializationSmokeBuffer(GraphicsDevice device, boolean direct) {
        ByteBuffer data = initializationData(8, 2, new byte[] {1, 2, 3, 4}, direct);
        int position = data.position();
        int limit = data.limit();
        Buffer buffer = device.createBuffer(new BufferDescriptor(8, Set.of(BufferUsage.VERTEX)), data);
        if (data.position() != position || data.limit() != limit) {
            throw new AssertionError("createBuffer changed the caller ByteBuffer position or limit");
        }
        return buffer;
    }

    private static void verifyInitializationSmokeBuffer(Buffer buffer) {
        if (buffer.size() != 8 || !buffer.usage().equals(Set.of(BufferUsage.VERTEX))) {
            throw new AssertionError("initialized buffer metadata does not match its descriptor");
        }
    }

    /** Exercises heap initialization larger than native-call scratch storage. */
    private static Buffer createLargeHeapInitializationSmokeBuffer(GraphicsDevice device) {
        ByteBuffer data = ByteBuffer.allocate(LARGE_HEAP_INITIALIZATION_BYTES + 8);
        data.position(4);
        data.limit(data.position() + LARGE_HEAP_INITIALIZATION_BYTES);
        int position = data.position();
        int limit = data.limit();
        Buffer buffer = device.createBuffer(new BufferDescriptor(LARGE_HEAP_INITIALIZATION_BYTES,
                Set.of(BufferUsage.VERTEX)), data);
        if (data.position() != position || data.limit() != limit) {
            throw new AssertionError("createBuffer changed the large heap ByteBuffer position or limit");
        }
        return buffer;
    }

    private static void verifyLargeHeapInitializationSmokeBuffer(Buffer buffer) {
        if (buffer.size() != LARGE_HEAP_INITIALIZATION_BYTES
                || !buffer.usage().equals(Set.of(BufferUsage.VERTEX))) {
            throw new AssertionError("large initialized buffer metadata does not match its descriptor");
        }
    }

    /** Exercises heap and direct texture initialization before the existing triangle loop. */
    private static Texture createInitializationSmokeTexture(GraphicsDevice device, boolean direct) {
        ByteBuffer pixels = initializationData(20, 2, new byte[] {
                (byte) 255, 0, 0, (byte) 255,
                0, (byte) 255, 0, (byte) 255,
                0, 0, (byte) 255, (byte) 255,
                (byte) 255, (byte) 255, (byte) 255, (byte) 255
        }, direct);
        int position = pixels.position();
        int limit = pixels.limit();
        Texture texture = device.createTexture(new TextureDescriptor(2, 2, TextureFormat.RGBA8_UNORM,
                Set.of(TextureUsage.SAMPLED)), pixels, ResourceState.SAMPLED_READ);
        if (pixels.position() != position || pixels.limit() != limit) {
            throw new AssertionError("createTexture changed the caller ByteBuffer position or limit");
        }
        return texture;
    }

    private static ByteBuffer initializationData(int capacity, int offset, byte[] bytes, boolean direct) {
        ByteBuffer data = direct ? ByteBuffer.allocateDirect(capacity) : ByteBuffer.allocate(capacity);
        data.position(offset);
        data.put(bytes);
        data.flip();
        data.position(offset);
        return data;
    }

    private static void verifyInitializationSmokeTexture(Texture texture) {
        if (texture.width() != 2 || texture.height() != 2 || texture.format() != TextureFormat.RGBA8_UNORM
                || !texture.usage().equals(Set.of(TextureUsage.SAMPLED))) {
            throw new AssertionError("initialized texture metadata does not match its descriptor");
        }
    }

    private static void runWindowLoop(
            GraphicsDevice device,
            RenderTarget target,
            Shader vertex,
            Shader fragment,
            BooleanSupplier shouldClose,
            Runnable pollEvents) {
        GraphicsState state = device.createGraphicsState(
                GraphicsStateDescriptor.builder()
                        .vertexShader(vertex)
                        .fragmentShader(fragment)
                        .colorFormat(target.colorFormats().get(0))
                        .build());
        Renderer renderer = new Renderer(device);

        while (!shouldClose.getAsBoolean()) {
            // RenderingInfo is rebuilt because a presentation-backed target can
            // change extent after a resize while retaining the same Java object.
            RenderPass pass = commands -> {
                commands.beginRendering(RenderingInfo.builder(target)
                        .color(ColorAttachmentOps.clear(new Color(0.03f, 0.04f, 0.06f, 1.0f)))
                        .build());
                commands.setGraphicsState(state);
                commands.draw(3, 1, 0, 0);
                commands.endRendering();
            };
            renderer.execute(RenderPipeline.of(pass));
            device.present(target);
            pollEvents.run();
        }
    }

    /** Compiles GLSL solely for the Vulkan smoke harness, not the backend. */
    private static ByteBuffer compileSpirv(String source, int kind) {
        long compiler = shaderc_compiler_initialize();
        if (compiler == MemoryUtil.NULL) throw new IllegalStateException("shaderc_compiler_initialize failed");
        long options = shaderc_compile_options_initialize();
        if (options == MemoryUtil.NULL) {
            shaderc_compiler_release(compiler);
            throw new IllegalStateException("shaderc_compile_options_initialize failed");
        }
        shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_3);
        long result = shaderc_compile_into_spv(compiler, source, kind, "backend-spike.glsl", "main", options);
        try {
            if (result == MemoryUtil.NULL) throw new IllegalStateException("shaderc_compile_into_spv failed");
            int status = shaderc_result_get_compilation_status(result);
            if (status != shaderc_compilation_status_success) {
                throw new IllegalArgumentException("shaderc failed: " + shaderc_result_get_error_message(result));
            }
            ByteBuffer bytes = shaderc_result_get_bytes(result);
            if (bytes == null) throw new IllegalStateException("shaderc returned no SPIR-V bytes");
            ByteBuffer copy = ByteBuffer.allocateDirect(bytes.remaining());
            copy.put(bytes).flip();
            return copy.asReadOnlyBuffer();
        } finally {
            if (result != MemoryUtil.NULL) shaderc_result_release(result);
            shaderc_compile_options_release(options);
            shaderc_compiler_release(compiler);
        }
    }

    @FunctionalInterface
    private interface BooleanSupplier {
        boolean getAsBoolean();
    }
}
