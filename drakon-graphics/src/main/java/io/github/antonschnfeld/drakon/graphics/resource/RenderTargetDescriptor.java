package io.github.antonschnfeld.drakon.graphics.resource;

import java.util.List;
import java.util.Objects;

/**
 * Groups existing texture resources into an offscreen render target.
 *
 * <p>All attachments must have identical dimensions. Color attachments require
 * {@link TextureUsage#COLOR_ATTACHMENT}; an optional depth attachment requires
 * {@link TextureUsage#DEPTH_ATTACHMENT}. The descriptor does not transfer
 * ownership of attachment textures.</p>
 *
 * <p>This descriptor intentionally models only caller-owned offscreen
 * attachments. Presentation-backed targets may use swapchain images, a default
 * framebuffer, or other backend-owned storage and are therefore created by the
 * relevant backend/window integration rather than by this descriptor.</p>
 *
 * @param colorAttachments ordered color textures; may be empty for depth-only rendering
 * @param depthAttachment optional depth texture, or {@code null}
 */
public record RenderTargetDescriptor(List<Texture> colorAttachments, Texture depthAttachment) {
    /**
     * Validates target attachments and defensively copies the color list.
     *
     * @param colorAttachments ordered color textures
     * @param depthAttachment optional depth texture
     * @throws NullPointerException if {@code colorAttachments} or an element is null
     * @throws IllegalArgumentException if there are no attachments, usages or
     *         formats are invalid, or attachment dimensions differ
     */
    public RenderTargetDescriptor {
        colorAttachments = List.copyOf(Objects.requireNonNull(colorAttachments, "colorAttachments"));
        if (colorAttachments.isEmpty() && depthAttachment == null) {
            throw new IllegalArgumentException("render target needs at least one attachment");
        }

        int width = !colorAttachments.isEmpty() ? colorAttachments.get(0).width() : depthAttachment.width();
        int height = !colorAttachments.isEmpty() ? colorAttachments.get(0).height() : depthAttachment.height();

        for (Texture texture : colorAttachments) {
            if (!texture.usage().contains(TextureUsage.COLOR_ATTACHMENT)) {
                throw new IllegalArgumentException("color attachment texture was not created with COLOR_ATTACHMENT usage");
            }
            if (texture.format().isDepth()) {
                throw new IllegalArgumentException("depth format cannot be used as a color attachment");
            }
            if (texture.width() != width || texture.height() != height) {
                throw new IllegalArgumentException("all render target attachments must have matching dimensions");
            }
        }

        if (depthAttachment != null) {
            if (!depthAttachment.usage().contains(TextureUsage.DEPTH_ATTACHMENT)) {
                throw new IllegalArgumentException("depth attachment texture was not created with DEPTH_ATTACHMENT usage");
            }
            if (!depthAttachment.format().isDepth()) {
                throw new IllegalArgumentException("depth attachment requires a depth texture format");
            }
            if (depthAttachment.width() != width || depthAttachment.height() != height) {
                throw new IllegalArgumentException("depth attachment dimensions must match color attachments");
            }
        }
    }
}
