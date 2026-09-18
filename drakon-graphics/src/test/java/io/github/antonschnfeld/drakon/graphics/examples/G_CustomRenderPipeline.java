package io.github.antonschnfeld.drakon.graphics.examples;

import io.github.antonschnfeld.drakon.graphics.backend.*;
import io.github.antonschnfeld.drakon.graphics.command.*;
import io.github.antonschnfeld.drakon.graphics.pipeline.*;
import io.github.antonschnfeld.drakon.graphics.backend.GraphicsBackend;
import io.github.antonschnfeld.drakon.graphics.backend.GraphicsBackends;
import io.github.antonschnfeld.drakon.graphics.backend.GraphicsDevice;
import io.github.antonschnfeld.drakon.graphics.backend.GraphicsDeviceConfig;
import io.github.antonschnfeld.drakon.graphics.command.Color;
import io.github.antonschnfeld.drakon.graphics.command.ColorAttachmentOps;
import io.github.antonschnfeld.drakon.graphics.command.RenderingInfo;
import io.github.antonschnfeld.drakon.graphics.pipeline.RenderPass;
import io.github.antonschnfeld.drakon.graphics.pipeline.RenderPipeline;
import io.github.antonschnfeld.drakon.graphics.render.Renderer;
import io.github.antonschnfeld.drakon.graphics.resource.*;
import io.github.antonschnfeld.drakon.graphics.shader.*;

import java.lang.foreign.MemorySegment;
import java.util.*;

public final class G_CustomRenderPipeline {
    public static final class TwoStagePipeline implements RenderPipeline {
        private final List<RenderPass> passes;
        public TwoStagePipeline(RenderPass first, RenderPass second) { passes = List.of(first, second); }
        @Override public List<? extends RenderPass> passes() { return passes; }
    }

    public static void run(String backendId) {
        GraphicsBackend backend = GraphicsBackends.require(backendId);

        try (GraphicsDevice device = backend.createDevice(GraphicsDeviceConfig.debug())) {
            Buffer triangle = device.createBuffer(
                    new BufferDescriptor(3L * 2 * Float.BYTES, Set.of(BufferUsage.VERTEX)),
                    MemorySegment.ofArray(new byte[3 * 2 * Float.BYTES]));
            Buffer quad = device.createBuffer(
                    new BufferDescriptor(6L * 2 * Float.BYTES, Set.of(BufferUsage.VERTEX)),
                    MemorySegment.ofArray(new byte[6 * 2 * Float.BYTES]));

            VertexLayout layout = VertexLayout.builder()
                    .binding(0, 2 * Float.BYTES, VertexInputRate.PER_VERTEX)
                    .attribute(0, 0, VertexFormat.FLOAT2, 0).build();

            Shader firstVs = shader(device, ShaderStage.VERTEX, "// stage one vertex");
            Shader firstFs = shader(device, ShaderStage.FRAGMENT, "// stage one fragment");
            GraphicsState firstState = device.createGraphicsState(GraphicsStateDescriptor.builder()
                    .vertexShader(firstVs).fragmentShader(firstFs).vertexLayout(layout)
                    .colorFormat(TextureFormat.RGBA8_UNORM).build());

            Texture intermediate = device.createTexture(new TextureDescriptor(640, 360, TextureFormat.RGBA8_UNORM,
                    Set.of(TextureUsage.COLOR_ATTACHMENT, TextureUsage.SAMPLED)));
            RenderTarget intermediateTarget = device.createRenderTarget(new RenderTargetDescriptor(List.of(intermediate), null));

            Binding<TextureBinding> source = Binding.sampledTexture("source", 0, ShaderStage.FRAGMENT);
            BindingLayout secondBindings = BindingLayout.of(source);
            Sampler sampler = device.createSampler(SamplerDescriptor.linearClamp());
            BindingSet secondSet = device.createBindingSet(BindingSetDescriptor.builder(secondBindings)
                    .bind(source, new TextureBinding(intermediate, sampler)).build());

            Shader secondVs = shader(device, ShaderStage.VERTEX, "// stage two vertex");
            Shader secondFs = shader(device, ShaderStage.FRAGMENT, "// stage two fragment");
            GraphicsState secondState = device.createGraphicsState(GraphicsStateDescriptor.builder()
                    .vertexShader(secondVs).fragmentShader(secondFs).vertexLayout(layout)
                    .colorFormat(TextureFormat.RGBA8_UNORM).bindingLayout(secondBindings).build());

            Texture output = device.createTexture(new TextureDescriptor(640, 360, TextureFormat.RGBA8_UNORM, Set.of(TextureUsage.COLOR_ATTACHMENT)));
            RenderTarget outputTarget = device.createRenderTarget(new RenderTargetDescriptor(List.of(output), null));
            RenderView outputView = RenderView.fullTarget(outputTarget);

            RenderPass first = commands -> {
                commands.transition(intermediate, ResourceState.UNDEFINED, ResourceState.COLOR_ATTACHMENT_WRITE);
                commands.beginRendering(RenderingInfo.builder(intermediateTarget)
                        .color(ColorAttachmentOps.clear(Color.BLACK)).build());
                commands.setGraphicsState(firstState);
                commands.setVertexBuffer(0, triangle, 0);
                commands.draw(3, 1, 0, 0);
                commands.endRendering();
                commands.transition(intermediate, ResourceState.COLOR_ATTACHMENT_WRITE, ResourceState.SAMPLED_READ);
            };

            RenderPass second = commands -> {
                commands.transition(output, ResourceState.UNDEFINED, ResourceState.COLOR_ATTACHMENT_WRITE);
                commands.beginRendering(RenderingInfo.builder(outputView)
                        .color(ColorAttachmentOps.clear(Color.BLACK)).build());
                commands.setGraphicsState(secondState);
                commands.bindSet(0, secondSet);
                commands.setVertexBuffer(0, quad, 0);
                commands.draw(6, 1, 0, 0);
                commands.endRendering();
            };

            RenderPipeline pipeline = new TwoStagePipeline(first, second);
            new Renderer(device).execute(pipeline);
        }
    }
    /**
     * Supplies backend-targeted placeholder code because this project tests
     * drakon-graphics in isolation. Production code is expected to obtain the
     * same ShaderDescriptor from drakon-shaders (HLSL/Slang compiler layer).
     */
    private static Shader shader(GraphicsDevice device, ShaderStage stage, String label) {
        ShaderCode code;
        if (device.shaderTarget() instanceof OpenGLShaderTarget) {
            code = new GlslShaderCode("#version 450 core\nvoid main() {} // " + label);
        } else if (device.shaderTarget() instanceof VulkanShaderTarget) {
            // The probe validates representation routing, not SPIR-V semantics.
            code = new SpirvShaderCode(MemorySegment.ofArray(
                    new byte[] {0x03, 0x02, 0x23, 0x07})); // SPIR-V magic word
        } else {
            throw new IllegalStateException("Unsupported probe shader target: " + device.shaderTarget());
        }
        return device.createShader(new ShaderDescriptor(stage, "main", code));
    }

}
