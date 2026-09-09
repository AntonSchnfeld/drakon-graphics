package io.github.antonschnfeld.drakon.graphics.pipeline;

import java.util.List;

/**
 * Ordered composition of {@link RenderPass render passes} that together form a
 * rendering or GPU-work strategy.
 *
 * <p>The pipeline itself does not own a scene, view, render target, or graphics
 * backend. Individual passes capture the resources and inputs they need.</p>
 */
@FunctionalInterface
public interface RenderPipeline {
    /**
     * Returns the passes to execute in order for one pipeline execution.
     *
     * <p>The standard renderer obtains this list once at the beginning of
     * execution and snapshots it. The returned list and all of its elements must
     * be non-null.</p>
     *
     * @return ordered passes for the current execution
     */
    List<? extends RenderPass> passes();

    /**
     * Creates an immutable pipeline containing the supplied passes in order.
     *
     * @param passes passes to execute; neither the array nor any element may be
     *        {@code null}
     * @return immutable pipeline containing those passes
     * @throws NullPointerException if {@code passes} or an element is null
     */
    static RenderPipeline of(RenderPass... passes) {
        List<RenderPass> copy = List.of(passes);
        return () -> copy;
    }
}
