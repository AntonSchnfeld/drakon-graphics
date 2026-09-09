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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

public final class A_TexturedMesh {
    public static void run(String backendId) {
        GraphicsBackend backend = GraphicsBackends.require(backendId);

        try (GraphicsDevice device = backend.createDevice(GraphicsDeviceConfig.debug())) {
            Buffer vertices = device.createBuffer(
                    new BufferDescriptor(3L * 8 * Float.BYTES, Set.of(BufferUsage.VERTEX)),
                    ByteBuffer.allocateDirect(3 * 8 * Float.BYTES));
            Buffer indices = device.createBuffer(
                    new BufferDescriptor(3L * Integer.BYTES, Set.of(BufferUsage.INDEX)),
                    ByteBuffer.allocateDirect(3 * Integer.BYTES));

            Texture albedo = device.createTexture(new TextureDescriptor(512, 512, TextureFormat.RGBA8_UNORM, Set.of(TextureUsage.SAMPLED)));
            Sampler sampler = device.createSampler(SamplerDescriptor.linearRepeat());

            Binding<TextureBinding> albedoBinding = Binding.sampledTexture("albedo", 0, ShaderStage.FRAGMENT);
            BindingLayout materialLayout = BindingLayout.of(albedoBinding);
            BindingSet materialSet = device.createBindingSet(
                    BindingSetDescriptor.builder(materialLayout)
                            .bind(albedoBinding, new TextureBinding(albedo, sampler))
                            .build());

            Shader vertexShader = shader(device, ShaderStage.VERTEX, "// textured vertex shader");
            Shader fragmentShader = shader(device, ShaderStage.FRAGMENT, "// textured fragment shader");

            VertexLayout vertexLayout = VertexLayout.builder()
                    .binding(0, 8 * Float.BYTES, VertexInputRate.PER_VERTEX)
                    .attribute(0, 0, VertexFormat.FLOAT3, 0)
                    .attribute(1, 0, VertexFormat.FLOAT3, 3 * Float.BYTES)
                    .attribute(2, 0, VertexFormat.FLOAT2, 6 * Float.BYTES)
                    .build();

            GraphicsState state = device.createGraphicsState(
                    GraphicsStateDescriptor.builder()
                            .vertexShader(vertexShader)
                            .fragmentShader(fragmentShader)
                            .vertexLayout(vertexLayout)
                            .depth(DepthState.standard())
                            .colorFormat(TextureFormat.RGBA8_UNORM)
                            .depthFormat(TextureFormat.D32_FLOAT)
                            .bindingLayout(materialLayout)
                            .build());

            Texture color = device.createTexture(new TextureDescriptor(1280, 720, TextureFormat.RGBA8_UNORM, Set.of(TextureUsage.COLOR_ATTACHMENT)));
            Texture depth = device.createTexture(new TextureDescriptor(1280, 720, TextureFormat.D32_FLOAT, Set.of(TextureUsage.DEPTH_ATTACHMENT)));
            RenderTarget target = device.createRenderTarget(new RenderTargetDescriptor(List.of(color), depth));
            RenderView view = RenderView.fullTarget(target);

            RenderPass pass = commands -> {
                // Shader-visible textures need an explicit readable state just like attachments need writable states.
                commands.transition(albedo, ResourceState.UNDEFINED, ResourceState.SAMPLED_READ);
                // Attachment state changes are explicit; beginRendering never hides a Vulkan barrier.
                commands.transition(color, ResourceState.UNDEFINED, ResourceState.COLOR_ATTACHMENT_WRITE);
                commands.transition(depth, ResourceState.UNDEFINED, ResourceState.DEPTH_ATTACHMENT_WRITE);
                commands.beginRendering(RenderingInfo.builder(view)
                        .color(ColorAttachmentOps.clear(Color.BLACK))
                        .depth(DepthAttachmentOps.clear(1.0f))
                        .build());
                commands.setGraphicsState(state);
                commands.setVertexBuffer(0, vertices, 0);
                commands.setIndexBuffer(indices, IndexType.UINT32, 0);
                commands.bindSet(0, materialSet);
                commands.drawIndexed(3, 1, 0, 0, 0);
                commands.endRendering();
            };

            RenderPipeline pipeline = RenderPipeline.of(pass);
            Renderer renderer = new Renderer(device);
            renderer.execute(pipeline);
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
            ByteBuffer words = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
            words.putInt(0x07230203).flip(); // SPIR-V magic word
            code = new SpirvShaderCode(words);
        } else {
            throw new IllegalStateException("Unsupported probe shader target: " + device.shaderTarget());
        }
        return device.createShader(new ShaderDescriptor(stage, "main", code));
    }

}
