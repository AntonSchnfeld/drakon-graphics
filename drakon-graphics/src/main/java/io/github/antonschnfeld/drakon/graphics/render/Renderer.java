package io.github.antonschnfeld.drakon.graphics.render;

import io.github.antonschnfeld.drakon.graphics.backend.GraphicsDevice;
import io.github.antonschnfeld.drakon.graphics.command.CommandEncoder;
import io.github.antonschnfeld.drakon.graphics.pipeline.RenderPass;
import io.github.antonschnfeld.drakon.graphics.pipeline.RenderPipeline;

import java.util.List;
import java.util.Objects;

/**
 * Executes {@link RenderPipeline render pipelines} on a graphics device.
 *
 * <p>The renderer deliberately does not own a scene, view, target, or other
 * engine-level concept. It is a thin frame/pipeline executor: obtain one command
 * encoder, let the pipeline's passes record into it in order, finish the command
 * list, and submit it.</p>
 */
public final class Renderer {
    private final GraphicsDevice device;

    /**
     * Creates a renderer that submits work to {@code device}.
     *
     * @param device graphics device used for command recording and submission
     * @throws NullPointerException if {@code device} is {@code null}
     */
    public Renderer(GraphicsDevice device) {
        this.device = Objects.requireNonNull(device, "device");
    }

    /**
     * Executes one pipeline as one ordered command-list submission.
     *
     * <p>The pass list is copied before execution so a custom pipeline cannot
     * mutate the sequence while it is being traversed. If a pass throws, no
     * command list is submitted by this method.</p>
     *
     * @param pipeline pipeline whose passes should be recorded and submitted
     * @throws NullPointerException if {@code pipeline}, its pass list, or any
     *         pass is {@code null}
     * @throws RuntimeException if a pass or backend operation fails
     */
    public void execute(RenderPipeline pipeline) {
        Objects.requireNonNull(pipeline, "pipeline");
        List<? extends RenderPass> passes = List.copyOf(
                Objects.requireNonNull(pipeline.passes(), "pipeline.passes()"));

        CommandEncoder commands = device.createCommandEncoder();
        for (RenderPass pass : passes) {
            pass.record(commands);
        }
        device.submit(commands.finish());
    }
}
