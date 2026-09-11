package io.github.antonschnfeld.drakon.graphics.resource;

import io.github.antonschnfeld.drakon.graphics.backend.GraphicsDevice;

import java.util.List;

/**
 * Opaque device-owned destination into which graphics commands can render.
 *
 * <p>A render target describes the dimensions and attachment formats required
 * by graphics state and rendering commands, but it deliberately does not expose
 * how those attachments are backed. A target created through
 * {@link GraphicsDevice#createRenderTarget(RenderTargetDescriptor)}
 * references caller-owned textures, while a backend integration may also provide
 * a presentation-backed target whose images are owned and rotated internally by
 * the backend.</p>
 *
 * <p>Closing an offscreen target releases only the backend object grouping its
 * attachments; it does not close caller-owned textures. A presentation-backed
 * implementation may additionally release presentation resources that it owns.
 * The concrete creation mechanism defines which case applies.</p>
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
     * resource interface. Presentation-backed targets may report a different
     * value after backend recreation.</p>
     *
     * @return depth format, or {@code null} when the target has no depth attachment
     */
    TextureFormat depthFormat();
}
