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
import io.github.antonschnfeld.drakon.graphics.command.DepthAttachmentOps;
import io.github.antonschnfeld.drakon.graphics.command.RenderingInfo;
import io.github.antonschnfeld.drakon.graphics.pipeline.RenderPass;
import io.github.antonschnfeld.drakon.graphics.pipeline.RenderPipeline;
import io.github.antonschnfeld.drakon.graphics.render.Renderer;
import io.github.antonschnfeld.drakon.graphics.resource.*;
import io.github.antonschnfeld.drakon.graphics.shader.*;

import java.lang.foreign.MemorySegment;
import java.util.*;

public final class C_ShadowAndMainPass {
    public static void run(String backendId) {
        GraphicsBackend backend = GraphicsBackends.require(backendId);

        try (GraphicsDevice device = backend.createDevice(GraphicsDeviceConfig.debug())) {
            Buffer vertices = device.createBuffer(
                    new BufferDescriptor(3L * 3 * Float.BYTES, Set.of(BufferUsage.VERTEX)),
                    MemorySegment.ofArray(new byte[3 * 3 * Float.BYTES]));
            Buffer indices = device.createBuffer(
                    new BufferDescriptor(3L * Integer.BYTES, Set.of(BufferUsage.INDEX)),
                    MemorySegment.ofArray(new byte[3 * Integer.BYTES]));

            VertexLayout layout = VertexLayout.builder()
                    .binding(0, 3 * Float.BYTES, VertexInputRate.PER_VERTEX)
                    .attribute(0, 0, VertexFormat.FLOAT3, 0)
                    .build();

            Texture shadowDepth = device.createTexture(new TextureDescriptor(2048, 2048, TextureFormat.D32_FLOAT,
                    Set.of(TextureUsage.DEPTH_ATTACHMENT, TextureUsage.SAMPLED)));
            Texture shadowColor = device.createTexture(new TextureDescriptor(2048, 2048, TextureFormat.RGBA8_UNORM,
                    Set.of(TextureUsage.COLOR_ATTACHMENT)));
            RenderTarget shadowTarget = device.createRenderTarget(new RenderTargetDescriptor(List.of(shadowColor), shadowDepth));

            Shader shadowVs = shader(device, ShaderStage.VERTEX, "// shadow vertex shader");
            Shader shadowFs = shader(device, ShaderStage.FRAGMENT, "// shadow fragment shader");
            GraphicsState shadowState = device.createGraphicsState(GraphicsStateDescriptor.builder()
                    .vertexShader(shadowVs)
                    .fragmentShader(shadowFs)
                    .vertexLayout(layout)
                    .depth(DepthState.standard())
                    .colorFormat(TextureFormat.RGBA8_UNORM)
                    .depthFormat(TextureFormat.D32_FLOAT)
                    .build());

            Binding<TextureBinding> shadowMap = Binding.sampledTexture("shadowMap", 0, ShaderStage.FRAGMENT);
            BindingLayout shadowBindings = BindingLayout.of(shadowMap);
            Sampler sampler = device.createSampler(SamplerDescriptor.linearClamp());
            BindingSet mainSet = device.createBindingSet(BindingSetDescriptor.builder(shadowBindings)
                    .bind(shadowMap, new TextureBinding(shadowDepth, sampler))
                    .build());

            Shader mainVs = shader(device, ShaderStage.VERTEX, "// main vertex shader");
            Shader mainFs = shader(device, ShaderStage.FRAGMENT, "// main fragment shader samples shadowMap");
            GraphicsState mainState = device.createGraphicsState(GraphicsStateDescriptor.builder()
                    .vertexShader(mainVs)
                    .fragmentShader(mainFs)
                    .vertexLayout(layout)
                    .depth(DepthState.standard())
                    .colorFormat(TextureFormat.RGBA8_UNORM)
                    .depthFormat(TextureFormat.D32_FLOAT)
                    .bindingLayout(shadowBindings)
                    .build());

            Texture color = device.createTexture(new TextureDescriptor(1280, 720, TextureFormat.RGBA8_UNORM, Set.of(TextureUsage.COLOR_ATTACHMENT)));
            Texture depth = device.createTexture(new TextureDescriptor(1280, 720, TextureFormat.D32_FLOAT, Set.of(TextureUsage.DEPTH_ATTACHMENT)));
            RenderTarget mainTarget = device.createRenderTarget(new RenderTargetDescriptor(List.of(color), depth));
            RenderView mainView = RenderView.fullTarget(mainTarget);

            RenderPass shadowPass = commands -> {
                commands.transition(shadowColor, ResourceState.UNDEFINED, ResourceState.COLOR_ATTACHMENT_WRITE);
                commands.transition(shadowDepth, ResourceState.UNDEFINED, ResourceState.DEPTH_ATTACHMENT_WRITE);
                commands.beginRendering(RenderingInfo.builder(shadowTarget)
                        .color(ColorAttachmentOps.clear(Color.BLACK))
                        .depth(DepthAttachmentOps.clear(1.0f))
                        .build());
                commands.setGraphicsState(shadowState);
                commands.setVertexBuffer(0, vertices, 0);
                commands.setIndexBuffer(indices, IndexType.UINT32, 0);
                commands.drawIndexed(3, 1, 0, 0, 0);
                commands.endRendering();
                commands.transition(shadowDepth, ResourceState.DEPTH_ATTACHMENT_WRITE, ResourceState.SAMPLED_READ);
            };

            RenderPass mainPass = commands -> {
                commands.transition(color, ResourceState.UNDEFINED, ResourceState.COLOR_ATTACHMENT_WRITE);
                commands.transition(depth, ResourceState.UNDEFINED, ResourceState.DEPTH_ATTACHMENT_WRITE);
                commands.beginRendering(RenderingInfo.builder(mainView)
                        .color(ColorAttachmentOps.clear(Color.BLACK))
                        .depth(DepthAttachmentOps.clear(1.0f))
                        .build());
                commands.setGraphicsState(mainState);
                commands.bindSet(0, mainSet);
                commands.setVertexBuffer(0, vertices, 0);
                commands.setIndexBuffer(indices, IndexType.UINT32, 0);
                commands.drawIndexed(3, 1, 0, 0, 0);
                commands.endRendering();
            };

            new Renderer(device).execute(RenderPipeline.of(shadowPass, mainPass));
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
