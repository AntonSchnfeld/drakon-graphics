package io.github.antonschnfeld.drakon.graphics.resource;

/**
 * Opaque device resource containing values for one {@link BindingLayout}.
 *
 * <p>The set references but does not own buffers, textures, or samplers supplied
 * at creation. Referenced resources must remain valid while submitted work may
 * use this set.</p>
 */
public interface BindingSet extends GpuResource {
    /**
     * Returns the exact layout this set was created from.
     *
     * @return binding layout
     */
    BindingLayout layout();
}
