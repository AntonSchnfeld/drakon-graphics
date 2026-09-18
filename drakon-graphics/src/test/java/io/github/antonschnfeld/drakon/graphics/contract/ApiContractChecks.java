package io.github.antonschnfeld.drakon.graphics.contract;

import io.github.antonschnfeld.drakon.graphics.backend.*;
import io.github.antonschnfeld.drakon.graphics.command.*;
import io.github.antonschnfeld.drakon.graphics.pipeline.*;
import io.github.antonschnfeld.drakon.graphics.backend.GraphicsBackend;
import io.github.antonschnfeld.drakon.graphics.backend.GraphicsBackends;
import io.github.antonschnfeld.drakon.graphics.backend.GraphicsDevice;
import io.github.antonschnfeld.drakon.graphics.backend.GraphicsDeviceConfig;
import io.github.antonschnfeld.drakon.graphics.pipeline.RenderPass;
import io.github.antonschnfeld.drakon.graphics.probe.ProbePresentationTargets;
import io.github.antonschnfeld.drakon.graphics.probe.ProbeLifecycleChecks;
import io.github.antonschnfeld.drakon.graphics.probe.ProbeTextureInitializationChecks;
import io.github.antonschnfeld.drakon.graphics.probe.ProbeValidationParityChecks;
import io.github.antonschnfeld.drakon.graphics.probe.ProbeBufferWriteChecks;
import io.github.antonschnfeld.drakon.graphics.render.Renderer;
import io.github.antonschnfeld.drakon.graphics.resource.*;
import io.github.antonschnfeld.drakon.graphics.shader.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class ApiContractChecks {
    private ApiContractChecks() {}

    public static void run() {
        if (!AutoCloseable.class.isAssignableFrom(CommandEncoder.class)
                || !AutoCloseable.class.isAssignableFrom(CommandList.class)) {
            throw new AssertionError("command encoders and lists must have deterministic close semantics");
        }
        checkPortableDepthState();
        checkVertexLayoutOverflow();
        checkRenderTargetIsPlatformAgnostic();
        checkPipelineSnapshot();
        checkShaderValueContracts();
        ProbeTextureInitializationChecks.run();
        ProbeLifecycleChecks.run();
        ProbeValidationParityChecks.run();
        ProbeBufferWriteChecks.run();
        checkBackendContracts("opengl");
        checkBackendContracts("vulkan");
    }

    private static void checkRenderTargetIsPlatformAgnostic() {
        Set<String> methods = Arrays.stream(RenderTarget.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName)
                .collect(java.util.stream.Collectors.toSet());
        if (!methods.equals(Set.of("width", "height", "colorFormats", "depthFormat"))) {
            throw new AssertionError("core RenderTarget must expose only portable extent and format metadata: " + methods);
        }
    }

    private static void checkPortableDepthState() {
        expect(IllegalArgumentException.class,
                () -> new DepthState(false, true, CompareOp.ALWAYS));
    }

    private static void checkVertexLayoutOverflow() {
        expect(IllegalArgumentException.class, () -> VertexLayout.builder()
                .binding(0, Integer.MAX_VALUE, VertexInputRate.PER_VERTEX)
                .attribute(0, 0, VertexFormat.FLOAT4, Integer.MAX_VALUE - 4)
                .build());
    }

    private static void checkPipelineSnapshot() {
        try (GraphicsDevice device = GraphicsBackends.require("opengl")
                .createDevice(GraphicsDeviceConfig.defaults())) {
            List<RenderPass> mutable = new ArrayList<>();
            AtomicInteger recorded = new AtomicInteger();

            mutable.add(commands -> {
                recorded.incrementAndGet();
                mutable.clear();
            });
            mutable.add(commands -> recorded.incrementAndGet());

            new Renderer(device).execute(() -> mutable);
            if (recorded.get() != 2) {
                throw new AssertionError("Renderer must snapshot pipeline passes before recording");
            }
        }
    }


    private static void checkShaderValueContracts() {
        expect(IllegalArgumentException.class,
                () -> new ShaderDescriptor(ShaderStage.VERTEX, "   ", new GlslShaderCode("void main() {}")));
        expect(IllegalArgumentException.class,
                () -> new SpirvShaderCode(MemorySegment.ofArray(new byte[3])));

        SpirvShaderCode code;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment input = arena.allocate(8, Integer.BYTES);
            input.setAtIndex(ValueLayout.JAVA_INT, 0, 0x07230203);
            input.setAtIndex(ValueLayout.JAVA_INT, 1, 0x00010600);
            code = new SpirvShaderCode(input);
            input.setAtIndex(ValueLayout.JAVA_INT, 0, 0);
        }
        if (!code.code().isReadOnly()
                || code.code().getAtIndex(ValueLayout.JAVA_INT, 0) != 0x07230203) {
            throw new AssertionError("SpirvShaderCode must own an immutable snapshot independent of its caller");
        }
        SpirvShaderCode equalCode = new SpirvShaderCode(code.code());
        if (!code.equals(equalCode) || code.hashCode() != equalCode.hashCode()) {
            throw new AssertionError("SpirvShaderCode equality must compare snapshot contents");
        }
    }

    private static void checkBackendContracts(String backendId) {
        GraphicsBackend backend = GraphicsBackends.require(backendId);

        try (GraphicsDevice first = backend.createDevice(GraphicsDeviceConfig.debug());
             GraphicsDevice second = backend.createDevice(GraphicsDeviceConfig.debug())) {

            ShaderCode glsl = new GlslShaderCode("void main() {}");
            ShaderCode spirv = new SpirvShaderCode(MemorySegment.ofArray(
                    new byte[] {0x03, 0x02, 0x23, 0x07}));

            if (backendId.equals("opengl")) {
                if (!(first.shaderTarget() instanceof OpenGLShaderTarget)
                        || !first.shaderTarget().accepts(glsl)
                        || first.shaderTarget().accepts(spirv)) {
                    throw new AssertionError("OpenGL probe must advertise a GLSL-only target");
                }
                expect(IllegalArgumentException.class, () -> first.createShader(
                        new ShaderDescriptor(ShaderStage.VERTEX, "main", spirv)));
            } else if (backendId.equals("vulkan")) {
                if (!(first.shaderTarget() instanceof VulkanShaderTarget)
                        || first.shaderTarget().accepts(glsl)
                        || !first.shaderTarget().accepts(spirv)) {
                    throw new AssertionError("Vulkan probe must advertise a SPIR-V target");
                }
                expect(IllegalArgumentException.class, () -> first.createShader(
                        new ShaderDescriptor(ShaderStage.VERTEX, "main", glsl)));
            }

            ShaderCode acceptedCode = backendId.equals("opengl") ? glsl : spirv;
            Shader vertexShader = first.createShader(new ShaderDescriptor(
                    ShaderStage.VERTEX, "main", acceptedCode));
            Shader fragmentShader = first.createShader(new ShaderDescriptor(
                    ShaderStage.FRAGMENT, "main", acceptedCode));
            expect(NullPointerException.class, () -> GraphicsStateDescriptor.builder()
                    .vertexShader(vertexShader)
                    .colorFormat(TextureFormat.RGBA8_UNORM)
                    .build());
            expect(IllegalArgumentException.class, () -> GraphicsStateDescriptor.builder()
                    .vertexShader(vertexShader)
                    .fragmentShader(fragmentShader)
                    .build());
            expect(IllegalArgumentException.class, () -> GraphicsStateDescriptor.builder()
                    .vertexShader(vertexShader)
                    .fragmentShader(fragmentShader)
                    .colorFormat(TextureFormat.RGBA8_UNORM)
                    .colorFormat(TextureFormat.BGRA8_UNORM)
                    .build());

            Buffer foreign = first.createBuffer(new BufferDescriptor(16, Set.of(BufferUsage.VERTEX)));
            try (CommandEncoder secondEncoder = second.createCommandEncoder()) {
                expect(IllegalArgumentException.class,
                        () -> secondEncoder.setVertexBuffer(0, foreign, 0));
            }
            Buffer local = second.createBuffer(new BufferDescriptor(16, Set.of(BufferUsage.VERTEX)));
            try (CommandEncoder invalidTransition = second.createCommandEncoder()) {
                expect(IllegalArgumentException.class, () -> invalidTransition.transition(
                        local, ResourceState.UNDEFINED, ResourceState.COPY_DST));
            }

            CommandEncoder abandoned = second.createCommandEncoder();
            abandoned.close();
            abandoned.close();
            expect(IllegalStateException.class, abandoned::finish);

            CommandEncoder transferred = second.createCommandEncoder();
            CommandList closedList = transferred.finish();
            transferred.close();
            closedList.close();
            closedList.close();
            expect(IllegalStateException.class, () -> second.submit(closedList));

            Texture color = second.createTexture(new TextureDescriptor(
                    16, 16, TextureFormat.RGBA8_UNORM,
                    Set.of(TextureUsage.COLOR_ATTACHMENT, TextureUsage.COPY_SRC)));
            expect(IllegalArgumentException.class,
                    () -> new RenderTargetDescriptor(List.of(), null));
            expect(IllegalArgumentException.class,
                    () -> new RenderTargetDescriptor(List.of(color, color), null));
            RenderTarget target = second.createRenderTarget(new RenderTargetDescriptor(List.of(color), null));
            if (!target.colorFormats().equals(List.of(TextureFormat.RGBA8_UNORM))
                    || target.depthFormat() != null) {
                throw new AssertionError("render target must expose compatibility formats, not attachments");
            }
            expect(IllegalArgumentException.class, () -> second.present(target));

            RenderTarget presentationTarget = ProbePresentationTargets.create(
                    second, 1280, 720, List.of(TextureFormat.RGBA8_UNORM), null);
            second.present(presentationTarget);
            expect(IllegalArgumentException.class, () -> first.present(presentationTarget));

            RenderTarget anotherPresentationTarget = ProbePresentationTargets.create(
                    second, 640, 480, List.of(TextureFormat.RGBA8_UNORM), null);
            second.present(anotherPresentationTarget);

            try (CommandEncoder presentationEncoder = second.createCommandEncoder()) {
                presentationEncoder.beginRendering(RenderingInfo.builder(presentationTarget)
                        .color(ColorAttachmentOps.clear(Color.BLACK))
                        .build());
                presentationEncoder.endRendering();
                try (CommandList presentationCommands = presentationEncoder.finish()) {
                    second.submit(presentationCommands);
                }
            }
            second.present(presentationTarget);
            presentationTarget.close();
            expect(IllegalStateException.class, () -> second.present(presentationTarget));
            second.present(anotherPresentationTarget);

            try (CommandEncoder scopeEncoder = second.createCommandEncoder()) {
                scopeEncoder.transition(color, ResourceState.UNDEFINED, ResourceState.COLOR_ATTACHMENT_WRITE);
                scopeEncoder.beginRendering(RenderingInfo.builder(target)
                        .color(ColorAttachmentOps.clear(Color.BLACK))
                        .build());
                expect(IllegalStateException.class,
                        () -> scopeEncoder.transition(color,
                                ResourceState.COLOR_ATTACHMENT_WRITE,
                                ResourceState.COPY_SRC));
                scopeEncoder.endRendering();
                try (CommandList scopeCommands = scopeEncoder.finish()) {
                    second.submit(scopeCommands);
                }
            }

            CommandEncoder oneShotEncoder = second.createCommandEncoder();
            oneShotEncoder.transition(color,
                    ResourceState.COLOR_ATTACHMENT_WRITE,
                    ResourceState.COPY_SRC);
            CommandList oneShot = oneShotEncoder.finish();
            oneShotEncoder.close();
            second.submit(oneShot);
            oneShot.close();
            oneShot.close();
            expect(IllegalStateException.class, () -> second.submit(oneShot));

            Texture mismatched = second.createTexture(new TextureDescriptor(
                    8, 8, TextureFormat.RGBA8_UNORM, Set.of(TextureUsage.COPY_DST)));
            try (CommandEncoder copyEncoder = second.createCommandEncoder()) {
                copyEncoder.transition(mismatched, ResourceState.UNDEFINED, ResourceState.COPY_DST);
                expect(IllegalArgumentException.class, () -> copyEncoder.copyTexture(color, mismatched));
            }
        }
    }

    private static void expect(Class<? extends Throwable> type, Runnable action) {
        try {
            action.run();
        } catch (Throwable throwable) {
            if (type.isInstance(throwable)) return;
            throw new AssertionError("Expected " + type.getSimpleName() + " but got " + throwable, throwable);
        }
        throw new AssertionError("Expected " + type.getSimpleName() + " but no exception was thrown");
    }
}
