package io.github.antonschnfeld.drakon.graphics.spike;

import io.github.antonschnfeld.drakon.graphics.backend.GraphicsDeviceConfig;
import io.github.antonschnfeld.drakon.graphics.command.Color;
import io.github.antonschnfeld.drakon.graphics.command.ColorAttachmentOps;
import io.github.antonschnfeld.drakon.graphics.command.CommandEncoder;
import io.github.antonschnfeld.drakon.graphics.command.CommandList;
import io.github.antonschnfeld.drakon.graphics.command.RenderingInfo;
import io.github.antonschnfeld.drakon.graphics.opengl.OpenGLBackend;
import io.github.antonschnfeld.drakon.graphics.opengl.OpenGLDevice;
import io.github.antonschnfeld.drakon.graphics.backend.GraphicsDevice;
import io.github.antonschnfeld.drakon.graphics.pipeline.RenderPass;
import io.github.antonschnfeld.drakon.graphics.pipeline.RenderPipeline;
import io.github.antonschnfeld.drakon.graphics.render.Renderer;
import io.github.antonschnfeld.drakon.graphics.resource.GraphicsState;
import io.github.antonschnfeld.drakon.graphics.resource.GraphicsStateDescriptor;
import io.github.antonschnfeld.drakon.graphics.resource.Binding;
import io.github.antonschnfeld.drakon.graphics.resource.BindingLayout;
import io.github.antonschnfeld.drakon.graphics.resource.BindingSet;
import io.github.antonschnfeld.drakon.graphics.resource.BindingSetDescriptor;
import io.github.antonschnfeld.drakon.graphics.resource.Buffer;
import io.github.antonschnfeld.drakon.graphics.resource.BufferBinding;
import io.github.antonschnfeld.drakon.graphics.resource.BufferDescriptor;
import io.github.antonschnfeld.drakon.graphics.resource.BufferUsage;
import io.github.antonschnfeld.drakon.graphics.resource.IndexType;
import io.github.antonschnfeld.drakon.graphics.resource.RenderTarget;
import io.github.antonschnfeld.drakon.graphics.resource.Shader;
import io.github.antonschnfeld.drakon.graphics.resource.ShaderStage;
import io.github.antonschnfeld.drakon.graphics.resource.ResourceState;
import io.github.antonschnfeld.drakon.graphics.resource.Texture;
import io.github.antonschnfeld.drakon.graphics.resource.TextureBinding;
import io.github.antonschnfeld.drakon.graphics.resource.TextureDescriptor;
import io.github.antonschnfeld.drakon.graphics.resource.TextureFormat;
import io.github.antonschnfeld.drakon.graphics.resource.TextureUsage;
import io.github.antonschnfeld.drakon.graphics.resource.Sampler;
import io.github.antonschnfeld.drakon.graphics.resource.SamplerDescriptor;
import io.github.antonschnfeld.drakon.graphics.resource.VertexFormat;
import io.github.antonschnfeld.drakon.graphics.resource.VertexInputRate;
import io.github.antonschnfeld.drakon.graphics.resource.VertexLayout;
import io.github.antonschnfeld.drakon.graphics.shader.GlslShaderCode;
import io.github.antonschnfeld.drakon.graphics.shader.ShaderDescriptor;
import io.github.antonschnfeld.drakon.graphics.shader.SpirvShaderCode;
import io.github.antonschnfeld.drakon.graphics.vulkan.VulkanBackend;
import io.github.antonschnfeld.drakon.graphics.vulkan.VulkanDevice;
import io.github.antonschnfeld.drakon.graphics.vulkan.VulkanPresentation;
import org.lwjgl.opengl.GL;

import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.Set;

import static org.lwjgl.opengl.GL11C.glGetPointer;
import static org.lwjgl.opengl.GL11C.glGetInteger;
import static org.lwjgl.opengl.GL11C.glIsEnabled;
import static org.lwjgl.opengl.GL31C.GL_COPY_WRITE_BUFFER;
import static org.lwjgl.opengl.GL43C.GL_DEBUG_CALLBACK_FUNCTION;
import static org.lwjgl.opengl.GL43C.GL_DEBUG_OUTPUT;
import static org.lwjgl.opengl.GL43C.GL_DEBUG_OUTPUT_SYNCHRONOUS;
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
    private static final int DYNAMIC_STRESS_FRAMES = 240;
    private static final double DYNAMIC_ANGULAR_SPEED = 4.5;

    private static final String VERTEX_GLSL = """
            #version 450
            layout(location = 0) in vec2 position;
            layout(location = 1) in vec2 uv;
            layout(location = 0) out vec2 interpolatedUv;
            void main() {
                gl_Position = vec4(position, 0.0, 1.0);
                interpolatedUv = uv;
            }
            """;

    private static final String OPENGL_VERTEX_GLSL = VERTEX_GLSL.replace("#version 450", "#version 430");

    private static final String OPENGL_FRAGMENT_GLSL = """
            #version 430
            layout(binding = 0) uniform sampler2D texturePattern;
            layout(location = 0) in vec2 interpolatedUv;
            layout(location = 0) out vec4 outColor;
            void main() {
                outColor = texture(texturePattern, interpolatedUv);
            }
            """;

    private static final String VULKAN_FRAGMENT_GLSL = """
            #version 450
            layout(set = 0, binding = 0) uniform sampler2D texturePattern;
            layout(location = 0) in vec2 interpolatedUv;
            layout(location = 0) out vec4 outColor;
            void main() {
                outColor = texture(texturePattern, interpolatedUv);
            }
            """;

    private static final String OPENGL_DYNAMIC_VERTEX_GLSL = """
            #version 430
            layout(location = 0) in vec2 position;
            layout(location = 1) in vec2 uv;
            layout(std140, binding = 1) uniform DynamicData {
                vec4 transformAndTint;
            } dynamicData;
            layout(location = 0) out vec2 interpolatedUv;
            layout(location = 1) out float interpolatedTint;
            void main() {
                vec2 animated = position * dynamicData.transformAndTint.z
                    + dynamicData.transformAndTint.xy;
                gl_Position = vec4(animated, 0.0, 1.0);
                interpolatedUv = uv;
                interpolatedTint = dynamicData.transformAndTint.w;
            }
            """;

    private static final String OPENGL_DYNAMIC_FRAGMENT_GLSL = """
            #version 430
            layout(binding = 0) uniform sampler2D texturePattern;
            layout(std140, binding = 1) uniform DynamicData {
                vec4 transformAndTint;
            } dynamicData;
            layout(location = 0) in vec2 interpolatedUv;
            layout(location = 1) in float interpolatedTint;
            layout(location = 0) out vec4 outColor;
            void main() {
                vec4 texel = texture(texturePattern, interpolatedUv);
                outColor = vec4(texel.rgb * (0.35 + 0.65 * interpolatedTint), texel.a);
            }
            """;

    private static final String VULKAN_DYNAMIC_VERTEX_GLSL = """
            #version 450
            layout(location = 0) in vec2 position;
            layout(location = 1) in vec2 uv;
            layout(set = 0, binding = 1, std140) uniform DynamicData {
                vec4 transformAndTint;
            } dynamicData;
            layout(location = 0) out vec2 interpolatedUv;
            layout(location = 1) out float interpolatedTint;
            void main() {
                vec2 animated = position * dynamicData.transformAndTint.z
                    + dynamicData.transformAndTint.xy;
                gl_Position = vec4(animated, 0.0, 1.0);
                interpolatedUv = uv;
                interpolatedTint = dynamicData.transformAndTint.w;
            }
            """;

    private static final String VULKAN_DYNAMIC_FRAGMENT_GLSL = """
            #version 450
            layout(set = 0, binding = 0) uniform sampler2D texturePattern;
            layout(set = 0, binding = 1, std140) uniform DynamicData {
                vec4 transformAndTint;
            } dynamicData;
            layout(location = 0) in vec2 interpolatedUv;
            layout(location = 1) in float interpolatedTint;
            layout(location = 0) out vec4 outColor;
            void main() {
                vec4 texel = texture(texturePattern, interpolatedUv);
                outColor = vec4(texel.rgb * (0.35 + 0.65 * interpolatedTint), texel.a);
            }
            """;

    private BackendSpikeMain() {}

    /**
     * Runs one or both windowed backend smoke tests.
     *
     * @param args optional {@code opengl}, {@code vulkan}, or {@code both},
     *             followed by optional {@code finite}; defaults to both backends
     *             with interactive windows
     */
    public static void main(String[] args) {
        if (args.length > 2 || (args.length == 2 && !"finite".equalsIgnoreCase(args[1]))) {
            throw new IllegalArgumentException(
                    "expected opengl, vulkan, or both, optionally followed by finite");
        }
        String backend = args.length == 0 ? "both" : args[0].toLowerCase(Locale.ROOT);
        boolean interactive = args.length < 2;
        switch (backend) {
            case "opengl" -> runOpenGL(interactive);
            case "vulkan" -> runVulkan(interactive);
            case "both" -> {
                runOpenGL(interactive);
                runVulkan(interactive);
            }
            default -> throw new IllegalArgumentException("expected opengl, vulkan, or both");
        }
    }

    private static void runOpenGL(boolean interactive) {
        OpenGLBackend backend = new OpenGLBackend();
        if (backend.isSupported()) {
            throw new AssertionError("OpenGL backend reported support without an externally current context");
        }
        try (GlfwPlatform platform = new GlfwPlatform();
                GlfwWindow window = platform.createOpenGLWindow(
                        800, 500, "drakon-graphics OpenGL spike")) {
            window.makeContextCurrent();
            window.enableSwapInterval();
            if (!backend.isSupported()) {
                throw new AssertionError("OpenGL backend did not recognize the current OpenGL 4.3 context");
            }
            GL.createCapabilities();
            long callbackBefore = glGetPointer(GL_DEBUG_CALLBACK_FUNCTION);
            boolean debugOutputBefore = glIsEnabled(GL_DEBUG_OUTPUT);
            boolean synchronousOutputBefore = glIsEnabled(GL_DEBUG_OUTPUT_SYNCHRONOUS);
            try (OpenGLDevice device = backend.createDevice(GraphicsDeviceConfig.debug());
                    GlfwOpenGLRenderTarget target = new GlfwOpenGLRenderTarget(device, window)) {
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
                    try (Shader vertex = device.createShader(new ShaderDescriptor(
                            ShaderStage.VERTEX, "main", new GlslShaderCode(OPENGL_VERTEX_GLSL)));
                            Shader fragment = device.createShader(new ShaderDescriptor(
                                    ShaderStage.FRAGMENT, "main", new GlslShaderCode(OPENGL_FRAGMENT_GLSL)))) {
                        verifyInactiveOpenGLBindingTolerance(device, target, vertex);
                        verifyBindingPersistenceAcrossStateChange(
                                device, target, vertex, fragment, true);
                        try (Shader dynamicVertex = device.createShader(new ShaderDescriptor(
                                ShaderStage.VERTEX, "main", new GlslShaderCode(OPENGL_DYNAMIC_VERTEX_GLSL)));
                                Shader dynamicFragment = device.createShader(new ShaderDescriptor(
                                        ShaderStage.FRAGMENT, "main", new GlslShaderCode(OPENGL_DYNAMIC_FRAGMENT_GLSL)));
                                DynamicMesh mesh = createDynamicMesh(
                                        device, target, dynamicVertex, dynamicFragment)) {
                            int copyWriteBinding = glGetInteger(GL_COPY_WRITE_BUFFER);
                            long animationStartNanos = System.nanoTime();
                            runDynamicBufferStress(
                                    device, target, mesh, window::pollEvents, "OpenGL", animationStartNanos);
                            if (glGetInteger(GL_COPY_WRITE_BUFFER) != copyWriteBinding) {
                                throw new AssertionError("OpenGL buffer writes changed external copy-write binding");
                            }
                        }
                        try (ThreeDWorkload workload = ThreeDWorkload.createOpenGL(device, target)) {
                            workload.runPresentationStress("OpenGL", window);
                            if (interactive) {
                                workload.runInteractive(window);
                            }
                        }
                    }
                }
            }
            if (glGetPointer(GL_DEBUG_CALLBACK_FUNCTION) != callbackBefore
                    || glIsEnabled(GL_DEBUG_OUTPUT) != debugOutputBefore
                    || glIsEnabled(GL_DEBUG_OUTPUT_SYNCHRONOUS) != synchronousOutputBefore) {
                throw new AssertionError("OpenGL device did not restore external debug state");
            }
        }
    }

    private static void runVulkan(boolean interactive) {
        VulkanBackend backend = new VulkanBackend();
        if (!backend.isSupported()) {
            throw new AssertionError("Vulkan backend cannot create its headless Vulkan 1.3 device");
        }
        try (GlfwPlatform platform = new GlfwPlatform();
                GlfwWindow window = platform.createVulkanWindow(
                        800, 500, "drakon-graphics Vulkan spike")) {
            VulkanPresentation presentation = backend.createPresentationDevice(
                    GraphicsDeviceConfig.debug(), new GlfwVulkanSurfaceFactory(window));
            try (VulkanDevice device = presentation.device();
                    RenderTarget target = presentation.target()) {
                expectIllegalState(
                        "Vulkan present without a submitted presentation frame",
                        () -> device.present(target));
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
                    try (Shader vertex = device.createShader(new ShaderDescriptor(
                            ShaderStage.VERTEX, "main",
                            new SpirvShaderCode(compileSpirv(VERTEX_GLSL, shaderc_glsl_vertex_shader))));
                            Shader fragment = device.createShader(new ShaderDescriptor(
                            ShaderStage.FRAGMENT, "main",
                                    new SpirvShaderCode(compileSpirv(VULKAN_FRAGMENT_GLSL, shaderc_glsl_fragment_shader))))) {
                        verifyBindingPersistenceAcrossStateChange(
                                device, target, vertex, fragment, false);
                        runVulkanLifetimeStress(device, target, vertex, fragment);
                        expectIllegalState(
                                "Vulkan double-present without a new frame",
                                () -> device.present(target));
                        try (Shader dynamicVertex = device.createShader(new ShaderDescriptor(
                                ShaderStage.VERTEX, "main", new SpirvShaderCode(compileSpirv(
                                        VULKAN_DYNAMIC_VERTEX_GLSL, shaderc_glsl_vertex_shader))));
                                Shader dynamicFragment = device.createShader(new ShaderDescriptor(
                                        ShaderStage.FRAGMENT, "main", new SpirvShaderCode(compileSpirv(
                                                VULKAN_DYNAMIC_FRAGMENT_GLSL, shaderc_glsl_fragment_shader))));
                                DynamicMesh mesh = createDynamicMesh(
                                        device, target, dynamicVertex, dynamicFragment)) {
                            long animationStartNanos = System.nanoTime();
                            runDynamicBufferStress(
                                    device, target, mesh, window::pollEvents, "Vulkan", animationStartNanos);
                        }
                        try (ThreeDWorkload workload = ThreeDWorkload.createVulkan(device, target)) {
                            workload.runPresentationStress("Vulkan", window);
                            if (interactive) {
                                workload.runInteractive(window);
                            }
                        }
                    }
                }
            }
        }
    }

    private static void verifyInactiveOpenGLBindingTolerance(
            GraphicsDevice device, RenderTarget target, Shader vertex) {
        String source = """
                #version 430
                uniform sampler2D optimizedAway;
                layout(location = 0) out vec4 outColor;
                void main() {
                    outColor = vec4(1.0);
                }
                """;
        Binding<TextureBinding> inactive = Binding.sampledTexture(
                "optimizedAway", 0, ShaderStage.FRAGMENT);
        BindingLayout layout = BindingLayout.of(inactive);
        try (Shader fragment = device.createShader(new ShaderDescriptor(
                ShaderStage.FRAGMENT, "main", new GlslShaderCode(source)));
                GraphicsState ignored = device.createGraphicsState(GraphicsStateDescriptor.builder()
                        .vertexShader(vertex)
                        .fragmentShader(fragment)
                        .bindingLayout(layout)
                        .colorFormat(target.colorFormats().get(0))
                        .build())) {
            if (ignored == null) throw new AssertionError("inactive OpenGL binding state was not created");
        }
    }

    private static void verifyBindingPersistenceAcrossStateChange(
            GraphicsDevice device,
            RenderTarget target,
            Shader vertex,
            Shader fragment,
            boolean inspectOpenGLBindings) {
        Binding<TextureBinding> textureA = Binding.sampledTexture("texturePattern", 0, ShaderStage.FRAGMENT);
        Binding<BufferBinding> uniformA = Binding.uniformBuffer("uniformA", 1, ShaderStage.VERTEX);
        Binding<TextureBinding> textureB0 = Binding.sampledTexture("texturePattern", 0, ShaderStage.FRAGMENT);
        Binding<TextureBinding> textureB1 = Binding.sampledTexture("textureB1", 1, ShaderStage.FRAGMENT);
        Binding<BufferBinding> uniformB0 = Binding.uniformBuffer("uniformB0", 2, ShaderStage.VERTEX);
        Binding<BufferBinding> uniformB1 = Binding.uniformBuffer("uniformB1", 3, ShaderStage.VERTEX);
        Binding<TextureBinding> sharedTexture = Binding.sampledTexture("sharedTexture", 0, ShaderStage.FRAGMENT);
        Binding<BufferBinding> sharedUniform = Binding.uniformBuffer("sharedUniform", 1, ShaderStage.VERTEX);
        BindingLayout layoutA = BindingLayout.of(textureA, uniformA);
        BindingLayout layoutB = BindingLayout.of(textureB0, textureB1, uniformB0, uniformB1);
        BindingLayout sharedLayout = BindingLayout.of(sharedTexture, sharedUniform);

        try (Buffer bufferA = device.createBuffer(new BufferDescriptor(16, Set.of(BufferUsage.UNIFORM)));
                Buffer bufferB = device.createBuffer(new BufferDescriptor(16, Set.of(BufferUsage.UNIFORM)));
                Buffer sharedBuffer = device.createBuffer(new BufferDescriptor(16, Set.of(BufferUsage.UNIFORM)));
                Texture sampledA = sampledPixel(device, (byte) 0x21);
                Texture sampledB = sampledPixel(device, (byte) 0x42);
                Texture sampledShared = sampledPixel(device, (byte) 0x63);
                Sampler sampler = device.createSampler(new SamplerDescriptor(
                        SamplerDescriptor.Filter.NEAREST,
                        SamplerDescriptor.Filter.NEAREST,
                        SamplerDescriptor.AddressMode.CLAMP_TO_EDGE));
                BindingSet setA = device.createBindingSet(BindingSetDescriptor.builder(layoutA)
                        .bind(textureA, new TextureBinding(sampledA, sampler))
                        .bind(uniformA, BufferBinding.whole(bufferA))
                        .build());
                BindingSet setB = device.createBindingSet(BindingSetDescriptor.builder(layoutB)
                        .bind(textureB0, new TextureBinding(sampledA, sampler))
                        .bind(textureB1, new TextureBinding(sampledB, sampler))
                        .bind(uniformB0, BufferBinding.whole(bufferA))
                        .bind(uniformB1, BufferBinding.whole(bufferB))
                        .build());
                BindingSet sharedSet = device.createBindingSet(BindingSetDescriptor.builder(sharedLayout)
                        .bind(sharedTexture, new TextureBinding(sampledShared, sampler))
                        .bind(sharedUniform, BufferBinding.whole(sharedBuffer))
                        .build());
                GraphicsState stateA = bindingPersistenceState(
                        device, target, vertex, fragment, layoutA, sharedLayout);
                GraphicsState stateB = bindingPersistenceState(
                        device, target, vertex, fragment, layoutB, sharedLayout)) {
            Renderer renderer = new Renderer(device);
            renderer.execute(RenderPipeline.of(commands -> {
                commands.transition(bufferA, ResourceState.UNDEFINED, ResourceState.UNIFORM_READ);
                commands.transition(bufferB, ResourceState.UNDEFINED, ResourceState.UNIFORM_READ);
                commands.transition(sharedBuffer, ResourceState.UNDEFINED, ResourceState.UNIFORM_READ);
            }));

            NativeBindings sharedUnderA = null;
            if (inspectOpenGLBindings) {
                renderBindingPersistencePass(
                        renderer, target, stateA, stateB, setA, setB, sharedSet, false);
                sharedUnderA = openGLBindings(1);
            }
            renderBindingPersistencePass(
                    renderer, target, stateA, stateB, setA, setB, sharedSet, true);
            if (inspectOpenGLBindings) {
                NativeBindings sharedUnderB = openGLBindings(2);
                NativeBindings overwrittenSlot = openGLBindings(1);
                if (!sharedUnderA.equals(sharedUnderB)) {
                    throw new AssertionError("OpenGL did not rebind the shared group using state B slots");
                }
                if (sharedUnderB.equals(overwrittenSlot)) {
                    throw new AssertionError("OpenGL state B did not occupy the preceding native slots");
                }
            } else {
                device.present(target);
            }
        }
    }

    private static GraphicsState bindingPersistenceState(
            GraphicsDevice device,
            RenderTarget target,
            Shader vertex,
            Shader fragment,
            BindingLayout first,
            BindingLayout shared) {
        return device.createGraphicsState(GraphicsStateDescriptor.builder()
                .vertexShader(vertex)
                .fragmentShader(fragment)
                .bindingLayout(first)
                .bindingLayout(shared)
                .colorFormat(target.colorFormats().get(0))
                .build());
    }

    private static void renderBindingPersistencePass(
            Renderer renderer,
            RenderTarget target,
            GraphicsState stateA,
            GraphicsState stateB,
            BindingSet setA,
            BindingSet setB,
            BindingSet sharedSet,
            boolean switchState) {
        renderer.execute(RenderPipeline.of(commands -> {
            commands.beginRendering(RenderingInfo.builder(target)
                    .color(ColorAttachmentOps.clear(Color.BLACK))
                    .build());
            commands.setGraphicsState(stateA);
            commands.bindSet(0, setA);
            commands.bindSet(1, sharedSet);
            if (switchState) {
                commands.setGraphicsState(stateB);
                commands.bindSet(0, setB);
            }
            commands.draw(3, 1, 0, 0);
            commands.endRendering();
        }));
    }

    private static Texture sampledPixel(GraphicsDevice device, byte value) {
        ByteBuffer pixel = ByteBuffer.allocate(4)
                .put(value).put(value).put(value).put((byte) 0xFF)
                .flip();
        return device.createTexture(
                new TextureDescriptor(1, 1, TextureFormat.RGBA8_UNORM, Set.of(TextureUsage.SAMPLED)),
                pixel,
                ResourceState.SAMPLED_READ);
    }

    private static NativeBindings openGLBindings(int slot) {
        org.lwjgl.opengl.GL13C.glActiveTexture(org.lwjgl.opengl.GL13C.GL_TEXTURE0 + slot);
        int texture = org.lwjgl.opengl.GL11C.glGetInteger(org.lwjgl.opengl.GL11C.GL_TEXTURE_BINDING_2D);
        int uniform = org.lwjgl.opengl.GL30C.glGetIntegeri(
                org.lwjgl.opengl.GL31C.GL_UNIFORM_BUFFER_BINDING, slot);
        return new NativeBindings(texture, uniform);
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

    private static TexturedMesh createTexturedMesh(
            GraphicsDevice device, RenderTarget target, Shader vertex, Shader fragment) {
        Buffer vertexBuffer = device.createBuffer(
                new BufferDescriptor(4L * 4 * Float.BYTES, Set.of(BufferUsage.VERTEX)), quadVertices());
        Buffer indexBuffer = device.createBuffer(
                new BufferDescriptor(6L * Short.BYTES, Set.of(BufferUsage.INDEX)), quadIndices());
        Texture texture = device.createTexture(
                new TextureDescriptor(4, 4, TextureFormat.RGBA8_UNORM, Set.of(TextureUsage.SAMPLED)),
                quadrantTexture(),
                ResourceState.SAMPLED_READ);
        Sampler sampler = device.createSampler(new SamplerDescriptor(
                SamplerDescriptor.Filter.NEAREST,
                SamplerDescriptor.Filter.NEAREST,
                SamplerDescriptor.AddressMode.CLAMP_TO_EDGE));

        Binding<TextureBinding> textureBinding = Binding.sampledTexture("texturePattern", 0, ShaderStage.FRAGMENT);
        BindingLayout bindingLayout = BindingLayout.of(textureBinding);
        GraphicsState state = device.createGraphicsState(
                GraphicsStateDescriptor.builder()
                        .vertexShader(vertex)
                        .fragmentShader(fragment)
                        .vertexLayout(VertexLayout.builder()
                                .binding(0, 4 * Float.BYTES, VertexInputRate.PER_VERTEX)
                                .attribute(0, 0, VertexFormat.FLOAT2, 0)
                                .attribute(1, 0, VertexFormat.FLOAT2, 2 * Float.BYTES)
                                .build())
                        .bindingLayout(bindingLayout)
                        .colorFormat(target.colorFormats().get(0))
                        .build());
        BindingSet bindingSet = device.createBindingSet(BindingSetDescriptor.builder(bindingLayout)
                .bind(textureBinding, new TextureBinding(texture, sampler))
                .build());
        return new TexturedMesh(vertexBuffer, indexBuffer, texture, sampler, bindingSet, state);
    }

    private static DynamicMesh createDynamicMesh(
            GraphicsDevice device, RenderTarget target, Shader vertex, Shader fragment) {
        Buffer vertexBuffer = device.createBuffer(
                new BufferDescriptor(4L * 4 * Float.BYTES, Set.of(BufferUsage.VERTEX)), quadVertices());
        Buffer indexBuffer = device.createBuffer(
                new BufferDescriptor(6L * Short.BYTES, Set.of(BufferUsage.INDEX)), quadIndices());
        Buffer dynamicBuffer = device.createBuffer(
                new BufferDescriptor(4L * Float.BYTES, Set.of(BufferUsage.UNIFORM)));
        Texture texture = device.createTexture(
                new TextureDescriptor(4, 4, TextureFormat.RGBA8_UNORM, Set.of(TextureUsage.SAMPLED)),
                quadrantTexture(),
                ResourceState.SAMPLED_READ);
        Sampler sampler = device.createSampler(new SamplerDescriptor(
                SamplerDescriptor.Filter.NEAREST,
                SamplerDescriptor.Filter.NEAREST,
                SamplerDescriptor.AddressMode.CLAMP_TO_EDGE));

        Binding<TextureBinding> textureBinding = Binding.sampledTexture(
                "texturePattern", 0, ShaderStage.FRAGMENT);
        Binding<BufferBinding> dynamicBinding = Binding.uniformBuffer(
                "DynamicData", 1, ShaderStage.VERTEX, ShaderStage.FRAGMENT);
        BindingLayout bindingLayout = BindingLayout.of(textureBinding, dynamicBinding);
        GraphicsState state = device.createGraphicsState(
                GraphicsStateDescriptor.builder()
                        .vertexShader(vertex)
                        .fragmentShader(fragment)
                        .vertexLayout(VertexLayout.builder()
                                .binding(0, 4 * Float.BYTES, VertexInputRate.PER_VERTEX)
                                .attribute(0, 0, VertexFormat.FLOAT2, 0)
                                .attribute(1, 0, VertexFormat.FLOAT2, 2 * Float.BYTES)
                                .build())
                        .bindingLayout(bindingLayout)
                        .colorFormat(target.colorFormats().get(0))
                        .build());
        BindingSet bindingSet = device.createBindingSet(BindingSetDescriptor.builder(bindingLayout)
                .bind(textureBinding, new TextureBinding(texture, sampler))
                .bind(dynamicBinding, BufferBinding.whole(dynamicBuffer))
                .build());
        return new DynamicMesh(
                vertexBuffer, indexBuffer, dynamicBuffer, texture, sampler, bindingSet, state);
    }

    private static void runDynamicBufferStress(
            GraphicsDevice device,
            RenderTarget target,
            DynamicMesh mesh,
            Runnable pollEvents,
            String backendName,
            long animationStartNanos) {
        Renderer renderer = new Renderer(device);
        renderer.execute(RenderPipeline.of(commands -> {
            commands.transition(mesh.vertexBuffer(), ResourceState.UNDEFINED, ResourceState.VERTEX_READ);
            commands.transition(mesh.indexBuffer(), ResourceState.UNDEFINED, ResourceState.INDEX_READ);
            commands.transition(mesh.dynamicBuffer(), ResourceState.UNDEFINED, ResourceState.UNIFORM_READ);
        }));
        long stressStartNanos = System.nanoTime();
        for (int submittedFrames = 0; submittedFrames < DYNAMIC_STRESS_FRAMES; submittedFrames++) {
            renderDynamicFrame(renderer, target, mesh, animationPhase(animationStartNanos));
            device.present(target);
            pollEvents.run();
        }
        double stressSeconds = elapsedSeconds(stressStartNanos);
        double framesPerSecond = DYNAMIC_STRESS_FRAMES / stressSeconds;
        System.out.printf(Locale.ROOT,
                "%s dynamic buffer stress passed: %d ordered updates using one "
                        + "Buffer/BindingSet/GraphicsState in %.3f s (%.1f FPS).%n",
                backendName, DYNAMIC_STRESS_FRAMES, stressSeconds, framesPerSecond);
    }

    private static void renderDynamicFrame(
            Renderer renderer, RenderTarget target, DynamicMesh mesh, double phase) {
        ByteBuffer frameData = dynamicFrameData(phase);
        RenderPass pass = commands -> {
            commands.writeBuffer(mesh.dynamicBuffer(), 0, frameData);
            // RenderingInfo is rebuilt because a presentation-backed target can
            // change extent after a resize while retaining the same Java object.
            commands.beginRendering(RenderingInfo.builder(target)
                    .color(ColorAttachmentOps.clear(new Color(0.03f, 0.04f, 0.06f, 1.0f)))
                    .build());
            commands.setGraphicsState(mesh.state());
            commands.setVertexBuffer(0, mesh.vertexBuffer(), 0);
            commands.setIndexBuffer(mesh.indexBuffer(), IndexType.UINT16, 0);
            commands.bindSet(0, mesh.bindingSet());
            commands.drawIndexed(6, 1, 0, 0, 0);
            commands.endRendering();
        };
        renderer.execute(RenderPipeline.of(pass));
    }

    private static double animationPhase(long animationStartNanos) {
        return elapsedSeconds(animationStartNanos) * DYNAMIC_ANGULAR_SPEED;
    }

    private static double elapsedSeconds(long startNanos) {
        return (System.nanoTime() - startNanos) * 1.0e-9;
    }

    private static void expectIllegalState(String label, Runnable operation) {
        try {
            operation.run();
        } catch (IllegalStateException expected) {
            System.out.println(label + " rejected as required.");
            return;
        }
        throw new AssertionError(label + " was accepted");
    }

    private static ByteBuffer dynamicFrameData(double phase) {
        ByteBuffer data = ByteBuffer.allocate(24).order(ByteOrder.nativeOrder());
        data.position(4);
        data.putFloat(0.16f * (float) Math.sin(phase));
        data.putFloat(0.10f * (float) Math.cos(phase * 0.73f));
        data.putFloat(0.72f + 0.12f * (float) Math.sin(phase * 0.41f));
        data.putFloat(0.55f + 0.45f * (float) Math.sin(phase * 0.57f));
        data.limit(data.position()).position(4);
        return data;
    }

    private static void runVulkanLifetimeStress(
            GraphicsDevice device,
            RenderTarget target,
            Shader vertex,
            Shader fragment) {
        CommandEncoder abandonedEncoder = device.createCommandEncoder();
        abandonedEncoder.close();
        abandonedEncoder.close();

        CommandEncoder finishedEncoder = device.createCommandEncoder();
        CommandList abandonedList = finishedEncoder.finish();
        finishedEncoder.close();
        abandonedList.close();
        abandonedList.close();

        Renderer renderer = new Renderer(device);
        try (TexturedMesh transientMesh = createTexturedMesh(device, target, vertex, fragment)) {
            renderer.execute(RenderPipeline.of(commands -> {
                commands.transition(transientMesh.vertexBuffer(), ResourceState.UNDEFINED, ResourceState.VERTEX_READ);
                commands.transition(transientMesh.indexBuffer(), ResourceState.UNDEFINED, ResourceState.INDEX_READ);
            }));
            renderer.execute(RenderPipeline.of(commands -> {
                commands.beginRendering(RenderingInfo.builder(target)
                        .color(ColorAttachmentOps.clear(new Color(0.02f, 0.02f, 0.02f, 1.0f)))
                        .build());
                commands.setGraphicsState(transientMesh.state());
                commands.setVertexBuffer(0, transientMesh.vertexBuffer(), 0);
                commands.setIndexBuffer(transientMesh.indexBuffer(), IndexType.UINT16, 0);
                commands.bindSet(0, transientMesh.bindingSet());
                commands.drawIndexed(6, 1, 0, 0, 0);
                commands.endRendering();
            }));
        }
        // The presentation submission above is not fenced until present(). The
        // transient resources are already logically closed here, so Vulkan must
        // retain their native objects until this frame eventually retires.
        device.present(target);
    }

    private static ByteBuffer quadVertices() {
        return ByteBuffer.allocate(4 * 4 * Float.BYTES).order(ByteOrder.nativeOrder())
                .putFloat(-0.80f).putFloat(0.80f).putFloat(0.0f).putFloat(0.0f)
                .putFloat(-0.80f).putFloat(-0.80f).putFloat(0.0f).putFloat(1.0f)
                .putFloat(0.80f).putFloat(-0.80f).putFloat(1.0f).putFloat(1.0f)
                .putFloat(0.80f).putFloat(0.80f).putFloat(1.0f).putFloat(0.0f)
                .flip();
    }

    private static ByteBuffer quadIndices() {
        return ByteBuffer.allocate(6 * Short.BYTES).order(ByteOrder.nativeOrder())
                .putShort((short) 0).putShort((short) 1).putShort((short) 2)
                .putShort((short) 2).putShort((short) 3).putShort((short) 0)
                .flip();
    }

    private static ByteBuffer quadrantTexture() {
        ByteBuffer texture = ByteBuffer.allocate(4 * 4 * 4);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                if (y < 2 && x < 2) putRgba(texture, 255, 32, 32);
                else if (y < 2) putRgba(texture, 32, 255, 32);
                else if (x < 2) putRgba(texture, 32, 64, 255);
                else putRgba(texture, 255, 240, 32);
            }
        }
        return texture.flip();
    }

    private static void putRgba(ByteBuffer texture, int red, int green, int blue) {
        texture.put((byte) red).put((byte) green).put((byte) blue).put((byte) 255);
    }

    private record NativeBindings(int texture, int uniformBuffer) {}

    private record TexturedMesh(
            Buffer vertexBuffer,
            Buffer indexBuffer,
            Texture texture,
            Sampler sampler,
            BindingSet bindingSet,
            GraphicsState state) implements AutoCloseable {
        @Override
        public void close() {
            state.close();
            bindingSet.close();
            sampler.close();
            texture.close();
            indexBuffer.close();
            vertexBuffer.close();
        }
    }

    private record DynamicMesh(
            Buffer vertexBuffer,
            Buffer indexBuffer,
            Buffer dynamicBuffer,
            Texture texture,
            Sampler sampler,
            BindingSet bindingSet,
            GraphicsState state) implements AutoCloseable {
        @Override
        public void close() {
            state.close();
            bindingSet.close();
            sampler.close();
            texture.close();
            dynamicBuffer.close();
            indexBuffer.close();
            vertexBuffer.close();
        }
    }

    /** Compiles GLSL solely for the Vulkan smoke harness, not the backend. */
    static ByteBuffer compileSpirv(String source, int kind) {
        long compiler = shaderc_compiler_initialize();
        if (compiler == 0L) throw new IllegalStateException("shaderc_compiler_initialize failed");
        long options = shaderc_compile_options_initialize();
        if (options == 0L) {
            shaderc_compiler_release(compiler);
            throw new IllegalStateException("shaderc_compile_options_initialize failed");
        }
        shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_3);
        try {
            try (Arena arena = Arena.ofConfined()) {
                var sourceText = arena.allocateFrom(source);
                long result = shaderc_compile_into_spv(
                        compiler,
                        sourceText.asSlice(0, sourceText.byteSize() - 1).asByteBuffer(),
                        kind,
                        arena.allocateFrom("backend-spike.glsl").asByteBuffer(),
                        arena.allocateFrom("main").asByteBuffer(),
                        options);
                try {
                    if (result == 0L) throw new IllegalStateException("shaderc_compile_into_spv failed");
                    int status = shaderc_result_get_compilation_status(result);
                    if (status != shaderc_compilation_status_success) {
                        throw new IllegalArgumentException(
                                "shaderc failed: " + shaderc_result_get_error_message(result));
                    }
                    ByteBuffer bytes = shaderc_result_get_bytes(result);
                    if (bytes == null) throw new IllegalStateException("shaderc returned no SPIR-V bytes");
                    ByteBuffer copy = ByteBuffer.allocate(bytes.remaining());
                    copy.put(bytes).flip();
                    return copy.asReadOnlyBuffer();
                } finally {
                    if (result != 0L) shaderc_result_release(result);
                }
            }
        } finally {
            shaderc_compile_options_release(options);
            shaderc_compiler_release(compiler);
        }
    }

    @FunctionalInterface
    private interface BooleanSupplier {
        boolean getAsBoolean();
    }
}
