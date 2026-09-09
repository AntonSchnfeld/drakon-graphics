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

import java.util.*;

public final class I_ResizeTarget {
    public static void run(String backendId) {
        GraphicsBackend backend = GraphicsBackends.require(backendId);

        try (GraphicsDevice device = backend.createDevice(GraphicsDeviceConfig.debug())) {
            Renderer renderer = new Renderer(device);

            Texture oldColor = device.createTexture(new TextureDescriptor(
                    800, 600, TextureFormat.RGBA8_UNORM, Set.of(TextureUsage.COLOR_ATTACHMENT)));
            RenderTarget oldTarget = device.createRenderTarget(new RenderTargetDescriptor(List.of(oldColor), null));
            RenderView oldView = RenderView.fullTarget(oldTarget);

            RenderPass clearOld = commands -> {
                commands.transition(oldColor, ResourceState.UNDEFINED, ResourceState.COLOR_ATTACHMENT_WRITE);
                commands.beginRendering(RenderingInfo.builder(oldView)
                        .color(ColorAttachmentOps.clear(Color.BLACK)).build());
                commands.endRendering();
            };
            renderer.execute(RenderPipeline.of(clearOld));

            // Offscreen targets are ordinary resources: resizing means replacing
            // them, not mutating hidden backend state behind an existing handle.
            oldTarget.close();
            oldColor.close();

            Texture newColor = device.createTexture(new TextureDescriptor(
                    1920, 1080, TextureFormat.RGBA8_UNORM, Set.of(TextureUsage.COLOR_ATTACHMENT)));
            RenderTarget newTarget = device.createRenderTarget(new RenderTargetDescriptor(List.of(newColor), null));
            RenderView newView = RenderView.fullTarget(newTarget);

            RenderPass clearNew = commands -> {
                commands.transition(newColor, ResourceState.UNDEFINED, ResourceState.COLOR_ATTACHMENT_WRITE);
                commands.beginRendering(RenderingInfo.builder(newView)
                        .color(ColorAttachmentOps.clear(Color.BLACK)).build());
                commands.endRendering();
            };
            renderer.execute(RenderPipeline.of(clearNew));

            if (newView.target().width() != 1920 || newView.target().height() != 1080) {
                throw new AssertionError("target recreation failed");
            }
        }
    }
}
