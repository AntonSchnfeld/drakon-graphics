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

public final class D_PostProcess {
    public static void run(String backendId) {
        GraphicsBackend backend = GraphicsBackends.require(backendId);

        try (GraphicsDevice device = backend.createDevice(GraphicsDeviceConfig.debug())) {
            Buffer triangle = device.createBuffer(
                    new BufferDescriptor(3L * 3 * Float.BYTES, Set.of(BufferUsage.VERTEX)),
                    MemorySegment.ofArray(new byte[3 * 3 * Float.BYTES]));
            Buffer quad = device.createBuffer(
                    new BufferDescriptor(6L * 2 * Float.BYTES, Set.of(BufferUsage.VERTEX)),
                    MemorySegment.ofArray(new byte[6 * 2 * Float.BYTES]));

            VertexLayout triangleLayout = VertexLayout.builder()
                    .binding(0, 3 * Float.BYTES, VertexInputRate.PER_VERTEX)
                    .attribute(0, 0, VertexFormat.FLOAT3, 0)
                    .build();
            VertexLayout quadLayout = VertexLayout.builder()
                    .binding(0, 2 * Float.BYTES, VertexInputRate.PER_VERTEX)
                    .attribute(0, 0, VertexFormat.FLOAT2, 0)
                    .build();

            Shader sceneVs = shader(device, ShaderStage.VERTEX, "// scene vertex");
            Shader sceneFs = shader(device, ShaderStage.FRAGMENT, "// scene fragment");
            GraphicsState sceneState = device.createGraphicsState(GraphicsStateDescriptor.builder()
                    .vertexShader(sceneVs).fragmentShader(sceneFs).vertexLayout(triangleLayout)
                    .colorFormat(TextureFormat.RGBA8_UNORM).build());

            Texture hdr = device.createTexture(new TextureDescriptor(1280, 720, TextureFormat.RGBA8_UNORM,
                    Set.of(TextureUsage.COLOR_ATTACHMENT, TextureUsage.SAMPLED)));
            RenderTarget hdrTarget = device.createRenderTarget(new RenderTargetDescriptor(List.of(hdr), null));

            Binding<TextureBinding> source = Binding.sampledTexture("source", 0, ShaderStage.FRAGMENT);
            BindingLayout postLayout = BindingLayout.of(source);
            Sampler sampler = device.createSampler(SamplerDescriptor.linearClamp());
            BindingSet postSet = device.createBindingSet(BindingSetDescriptor.builder(postLayout)
                    .bind(source, new TextureBinding(hdr, sampler)).build());

            Shader postVs = shader(device, ShaderStage.VERTEX, "// fullscreen vertex");
            Shader postFs = shader(device, ShaderStage.FRAGMENT, "// tonemap fragment");
            GraphicsState postState = device.createGraphicsState(GraphicsStateDescriptor.builder()
                    .vertexShader(postVs).fragmentShader(postFs).vertexLayout(quadLayout)
                    .colorFormat(TextureFormat.RGBA8_UNORM).bindingLayout(postLayout).build());

            Texture finalColor = device.createTexture(new TextureDescriptor(1280, 720, TextureFormat.RGBA8_UNORM, Set.of(TextureUsage.COLOR_ATTACHMENT)));
            RenderTarget finalTarget = device.createRenderTarget(new RenderTargetDescriptor(List.of(finalColor), null));
            RenderView finalView = RenderView.fullTarget(finalTarget);

            RenderPass scenePass = commands -> {
                commands.transition(hdr, ResourceState.UNDEFINED, ResourceState.COLOR_ATTACHMENT_WRITE);
                commands.beginRendering(RenderingInfo.builder(hdrTarget)
                        .color(ColorAttachmentOps.clear(Color.BLACK)).build());
                commands.setGraphicsState(sceneState);
                commands.setVertexBuffer(0, triangle, 0);
                commands.draw(3, 1, 0, 0);
                commands.endRendering();
                commands.transition(hdr, ResourceState.COLOR_ATTACHMENT_WRITE, ResourceState.SAMPLED_READ);
            };

            RenderPass toneMapPass = commands -> {
                commands.transition(finalColor, ResourceState.UNDEFINED, ResourceState.COLOR_ATTACHMENT_WRITE);
                commands.beginRendering(RenderingInfo.builder(finalView)
                        .color(ColorAttachmentOps.clear(Color.BLACK)).build());
                commands.setGraphicsState(postState);
                commands.bindSet(0, postSet);
                commands.setVertexBuffer(0, quad, 0);
                commands.draw(6, 1, 0, 0);
                commands.endRendering();
            };

            new Renderer(device).execute(RenderPipeline.of(scenePass, toneMapPass));
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
