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

public final class H_RenderToTexture {
    public static void run(String backendId) {
        GraphicsBackend backend = GraphicsBackends.require(backendId);

        try (GraphicsDevice device = backend.createDevice(GraphicsDeviceConfig.debug())) {
            Buffer vertices = device.createBuffer(new BufferDescriptor(3L * 3 * Float.BYTES, Set.of(BufferUsage.VERTEX)),
                    MemorySegment.ofArray(new byte[(int) 3L * 3 * Float.BYTES]));

            VertexLayout layout = VertexLayout.builder()
                    .binding(0, 3 * Float.BYTES, VertexInputRate.PER_VERTEX)
                    .attribute(0, 0, VertexFormat.FLOAT3, 0).build();
            Shader vs = shader(device, ShaderStage.VERTEX, "// render-to-texture vertex");
            Shader fs = shader(device, ShaderStage.FRAGMENT, "// render-to-texture fragment");
            GraphicsState state = device.createGraphicsState(GraphicsStateDescriptor.builder()
                    .vertexShader(vs).fragmentShader(fs).vertexLayout(layout)
                    .colorFormat(TextureFormat.RGBA8_UNORM).build());

            Texture renderedTexture = device.createTexture(new TextureDescriptor(1024, 1024, TextureFormat.RGBA8_UNORM,
                    Set.of(TextureUsage.COLOR_ATTACHMENT, TextureUsage.SAMPLED)));
            RenderTarget textureTarget = device.createRenderTarget(new RenderTargetDescriptor(List.of(renderedTexture), null));
            RenderView textureView = RenderView.fullTarget(textureTarget);

            RenderPass pass = commands -> {
                commands.transition(renderedTexture, ResourceState.UNDEFINED, ResourceState.COLOR_ATTACHMENT_WRITE);
                commands.beginRendering(RenderingInfo.builder(textureView)
                        .color(ColorAttachmentOps.clear(Color.BLACK)).build());
                commands.setGraphicsState(state);
                commands.setVertexBuffer(0, vertices, 0);
                commands.draw(3, 1, 0, 0);
                commands.endRendering();
                commands.transition(renderedTexture, ResourceState.COLOR_ATTACHMENT_WRITE, ResourceState.SAMPLED_READ);
            };

            new Renderer(device).execute(RenderPipeline.of(pass));
            // renderedTexture is now in SAMPLED_READ state for a later pass/frame.
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
