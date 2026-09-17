package io.github.antonschnfeld.drakon.graphics.vulkan;

import io.github.antonschnfeld.drakon.graphics.resource.RenderTarget;
import io.github.antonschnfeld.drakon.graphics.resource.TextureFormat;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.List;

/** Stable backend render-target facade over one externally supplied surface. */
final class VulkanPresentationTarget extends VulkanTarget {
    final VulkanSurfaceFactory surfaceFactory;
    final long surface;
    long swapchain;
    long[] swapchainImages = new long[0];
    long[] swapchainViews = new long[0];
    boolean[] swapchainInitialized = new boolean[0];
    int swapchainWidth;
    int swapchainHeight;
    TextureFormat colorFormat;
    VulkanDevice.FrameSync[] frames = new VulkanDevice.FrameSync[0];
    int frameSlot;
    int imageIndex = -1;
    long acquisitionSerial;
    long currentAcquisition;
    boolean imageAcquired;
    final VulkanSwapchainRecreation swapchainRecreation = new VulkanSwapchainRecreation();
    final VulkanSkippedPresentation skippedPresentation = new VulkanSkippedPresentation();

    VulkanPresentationTarget(VulkanDevice device, VulkanSurfaceFactory surfaceFactory, long surface) {
        super(device);
        this.surfaceFactory = surfaceFactory;
        this.surface = surface;
    }

    @Override long colorView(int index) { return device.presentationColorView(this, index); }
    @Override long depthView() { return 0L; }
    @Override void validateWritableStates(VulkanRecordingState states) { /* backing image is backend-owned */ }
    @Override
    VulkanPresentationState prepareForRendering(
            VkCommandBuffer commandBuffer, VulkanPresentationState presentationState) {
        return device.preparePresentationImage(commandBuffer, this, presentationState);
    }
    @Override
    void finishRendering(VkCommandBuffer commandBuffer, VulkanPresentationState presentationState) {
        device.finishPresentationImage(this, commandBuffer, presentationState);
    }
    @Override public int width() { requireAlive(); return swapchainWidth; }
    @Override public int height() { requireAlive(); return swapchainHeight; }
    @Override public List<TextureFormat> colorFormats() { requireAlive(); return List.of(colorFormat); }
    @Override public TextureFormat depthFormat() { requireAlive(); return null; }
    @Override void deleteNative() { device.destroyPresentationTarget(this); }
}
