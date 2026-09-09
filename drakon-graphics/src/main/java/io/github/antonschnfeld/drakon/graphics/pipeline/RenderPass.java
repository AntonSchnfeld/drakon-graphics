package io.github.antonschnfeld.drakon.graphics.pipeline;

import io.github.antonschnfeld.drakon.graphics.command.CommandEncoder;
import io.github.antonschnfeld.drakon.graphics.render.Renderer;

/**
 * One reusable unit of rendering, compute, copy, or synchronization work.
 *
 * <p>A pass records backend-agnostic commands into an encoder supplied by the
 * {@link Renderer}. Any targets, views, resources, or
 * application data required by the pass are explicit constructor inputs or
 * captured state. The interface intentionally does not expose a universal
 * context/service-locator object.</p>
 *
 * <p>A pass must not call {@link CommandEncoder#finish()}; command-list lifetime
 * belongs to the renderer executing the containing {@link RenderPipeline}.</p>
 */
@FunctionalInterface
public interface RenderPass {
    /**
     * Records this pass into the supplied command encoder.
     *
     * @param commands active encoder for the current pipeline execution
     * @throws RuntimeException if the pass cannot record valid work; in that
     *         case the containing pipeline execution is aborted and is not
     *         submitted by the standard renderer
     */
    void record(CommandEncoder commands);
}
