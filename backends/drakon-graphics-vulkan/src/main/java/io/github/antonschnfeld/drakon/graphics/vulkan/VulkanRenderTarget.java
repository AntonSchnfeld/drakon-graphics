package io.github.antonschnfeld.drakon.graphics.vulkan;

import io.github.antonschnfeld.drakon.graphics.resource.ResourceState;
import io.github.antonschnfeld.drakon.graphics.resource.Texture;
import io.github.antonschnfeld.drakon.graphics.resource.TextureFormat;

import java.util.List;

final class VulkanRenderTarget extends VulkanTarget {
    final List<VulkanTexture> colors;
    final VulkanTexture depth;
    private final int width;
    private final int height;
    private final List<TextureFormat> colorFormats;
    private final TextureFormat depthFormat;

    VulkanRenderTarget(VulkanDevice device, List<VulkanTexture> colors, VulkanTexture depth) {
        super(device);
        this.colors = List.copyOf(colors);
        this.depth = depth;
        width = !colors.isEmpty() ? colors.get(0).width() : depth.width();
        height = !colors.isEmpty() ? colors.get(0).height() : depth.height();
        colorFormats = colors.stream().map(Texture::format).toList();
        depthFormat = depth == null ? null : depth.format();
    }

    @Override long colorView(int index) { return colors.get(index).view; }
    @Override long depthView() { return depth == null ? 0L : depth.view; }

    @Override
    void validateWritableStates() {
        for (VulkanTexture color : colors) {
            if (color.state != ResourceState.COLOR_ATTACHMENT_WRITE) {
                throw new IllegalStateException("color attachment must be in COLOR_ATTACHMENT_WRITE");
            }
        }
        if (depth != null && depth.state != ResourceState.DEPTH_ATTACHMENT_WRITE) {
            throw new IllegalStateException("depth attachment must be in DEPTH_ATTACHMENT_WRITE");
        }
    }

    @Override public int width() { requireAlive(); return width; }
    @Override public int height() { requireAlive(); return height; }
    @Override public List<TextureFormat> colorFormats() { requireAlive(); return colorFormats; }
    @Override public TextureFormat depthFormat() { requireAlive(); return depthFormat; }
    @Override void deleteNative() { /* Dynamic rendering needs no native grouping object. */ }
}
