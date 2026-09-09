package io.github.antonschnfeld.drakon.graphics.vulkan;

import io.github.antonschnfeld.drakon.graphics.resource.RenderTarget;
import io.github.antonschnfeld.drakon.graphics.resource.TextureFormat;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.List;

/** Stable public RenderTarget facade over rotating Vulkan swapchain images. */
final class VulkanPresentationTarget extends VulkanTarget {
    private final TextureFormat colorFormat;

    VulkanPresentationTarget(VulkanDevice device, TextureFormat colorFormat) {
        super(device);
        this.colorFormat = colorFormat;
    }

    @Override long colorView(int index) { return device.presentationColorView(index); }
    @Override long depthView() { return 0L; }
    @Override void validateWritableStates() { /* backing image is backend-owned */ }
    @Override void prepareForRendering(VkCommandBuffer commandBuffer) { device.preparePresentationImage(commandBuffer); }
    @Override void finishRendering(VkCommandBuffer commandBuffer) { device.finishPresentationImage(commandBuffer); }
    @Override boolean presentable() { return true; }
    @Override public int width() { requireAlive(); return device.presentationWidth(); }
    @Override public int height() { requireAlive(); return device.presentationHeight(); }
    @Override public List<TextureFormat> colorFormats() { requireAlive(); return List.of(colorFormat); }
    @Override public TextureFormat depthFormat() { requireAlive(); return null; }
    @Override void deleteNative() { /* VulkanDevice owns swapchain/surface lifetime. */ }
}
