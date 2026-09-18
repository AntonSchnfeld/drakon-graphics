package io.github.antonschnfeld.drakon.graphics.resource;

import io.github.antonschnfeld.drakon.graphics.backend.GraphicsDevice;

import java.util.List;

/**
 * Opaque destination associated with a graphics device into which graphics
 * commands can render.
 *
 * <p>A render target describes the dimensions and attachment formats required
 * by graphics state and rendering commands, but deliberately does not expose
 * how those attachments are backed. A target may be created and lifetime-owned
 * by its graphics device, or it may be supplied by an external backend
 * integration. In either case, it is associated with exactly one graphics
 * device and may only be used with that device.</p>
 *
 * <p>Presentation-backed targets may contain backend-owned color and depth
 * attachments. Their formats are reported through {@link #colorFormats()} and
 * {@link #depthFormat()} for graphics-state compatibility, but the attachments
 * are not exposed as public {@link Texture} objects. Their native lifetime and
 * resource state remain private to the backend or external integration.</p>
 *
 * <p>A target created through
 * {@link GraphicsDevice#createRenderTarget(RenderTargetDescriptor)} references
 * caller-owned textures. Closing that target releases only the backend object
 * grouping those attachments and does not close the textures. An externally
 * integrated or presentation-backed target defines the lifetime of resources
 * owned by that integration; closing the target does not imply ownership of,
 * or destruction of, an external window, context, or other platform object.</p>
 */
public interface RenderTarget extends GpuResource {
    /**
     * Returns the current renderable width.
     *
     * <p>Presentation-backed targets may report a different value after a
     * resize or backend recreation. Callers must not assume this value is
     * immutable for the lifetime of every target.</p>
     *
     * @return width in pixels
     */
    int width();

    /**
     * Returns the current renderable height.
     *
     * <p>Presentation-backed targets may report a different value after a
     * resize or backend recreation. Callers must not assume this value is
     * immutable for the lifetime of every target.</p>
     *
     * @return height in pixels
     */
    int height();

    /**
     * Returns the color-attachment format in a one-element list.
     *
     * <p>The returned list describes compatibility only; it does not expose or
     * imply public {@link Texture} objects for the attachments. This distinction
     * allows the same render-target contract to represent both ordinary
     * texture-backed targets and backend-owned presentation images.</p>
     *
     * <p>For an offscreen target these formats are fixed. A presentation-backed
     * target may report different formats after backend recreation; graphics
     * states cached against the old formats must then be rebuilt.</p>
     *
     * @return immutable snapshot containing exactly one color format
     */
    List<TextureFormat> colorFormats();

    /**
     * Returns the depth-attachment format when one exists.
     *
     * <p>The nullable return intentionally mirrors the optional nature of depth
     * attachments without introducing another wrapper type into this small
     * resource interface. A non-null value describes compatibility and does not
     * imply that the attachment is exposed as a public {@link Texture}; a
     * presentation target may own its depth storage internally. Presentation-backed
     * targets may report a different value after backend recreation.</p>
     *
     * @return depth format, or {@code null} when the target has no depth attachment
     */
    TextureFormat depthFormat();
}
