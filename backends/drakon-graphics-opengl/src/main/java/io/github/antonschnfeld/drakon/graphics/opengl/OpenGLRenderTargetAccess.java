package io.github.antonschnfeld.drakon.graphics.opengl;

import io.github.antonschnfeld.drakon.graphics.resource.RenderTarget;

/**
 * Narrow OpenGL integration contract for render targets not created from
 * texture attachments by {@link OpenGLDevice}.
 *
 * <p>This interface is backend-specific: framebuffer names and buffer swapping
 * deliberately do not appear in the portable {@link RenderTarget} contract.
 * An integration supplies a target for exactly one {@link #device() device},
 * keeps the associated OpenGL context current while that device is used, and
 * implements its own target lifetime checks in the inherited target methods.</p>
 *
 * <p>{@link #close()} releases only resources owned by the target integration.
 * It must not destroy an externally owned context or platform destination merely
 * because the graphics device or target is closed. Closing the device does not
 * close externally implemented targets.</p>
 */
public interface OpenGLRenderTargetAccess extends RenderTarget {
    /**
     * Returns the only device with which this target may be used.
     *
     * @return owning OpenGL device
     */
    OpenGLDevice device();

    /**
     * Returns the framebuffer name to bind for rendering.
     *
     * @return OpenGL framebuffer name, including zero for context-owned storage
     */
    int framebuffer();

    /**
     * Presents this target after rendering submissions have completed.
     *
     * @throws IllegalArgumentException if this target has no presentation integration
     * @throws IllegalStateException if the target cannot currently be presented
     */
    void present();
}
