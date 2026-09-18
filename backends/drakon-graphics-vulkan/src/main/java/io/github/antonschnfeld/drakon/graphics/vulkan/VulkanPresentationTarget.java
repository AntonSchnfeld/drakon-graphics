package io.github.antonschnfeld.drakon.graphics.vulkan;

import io.github.antonschnfeld.drakon.graphics.resource.RenderTarget;
import io.github.antonschnfeld.drakon.graphics.resource.TextureFormat;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Stable backend render-target facade over one externally supplied surface. */
final class VulkanPresentationTarget extends VulkanTarget {
    static final TextureFormat DEPTH_FORMAT = TextureFormat.D32_FLOAT;
    final VulkanSurfaceFactory surfaceFactory;
    final long surface;
    long swapchain;
    long[] swapchainImages = new long[0];
    long[] swapchainViews = new long[0];
    boolean[] swapchainInitialized = new boolean[0];
    VulkanPresentationDepth[] presentationDepths = new VulkanPresentationDepth[0];
    boolean[] presentationDepthInitialized = new boolean[0];
    int swapchainWidth;
    int swapchainHeight;
    TextureFormat colorFormat;
    VulkanDevice.FrameSync[] frames = new VulkanDevice.FrameSync[0];
    int frameSlot;
    int imageIndex = -1;
    long acquisitionSerial;
    long currentAcquisition;
    boolean imageAcquired;
    Throwable terminalFailure;
    final VulkanSwapchainRecreation swapchainRecreation = new VulkanSwapchainRecreation();
    final VulkanSkippedPresentation skippedPresentation = new VulkanSkippedPresentation();

    VulkanPresentationTarget(VulkanDevice device, VulkanSurfaceFactory surfaceFactory, long surface) {
        super(device);
        this.surfaceFactory = surfaceFactory;
        this.surface = surface;
    }

    @Override long colorView(int index) { return device.presentationColorView(this, index); }
    @Override long depthView() {
        requireOperational();
        if (!imageAcquired) throw new IllegalStateException("no swapchain image is currently acquired");
        return presentationDepths[imageIndex].view;
    }
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
    @Override public int width() { requireOperational(); return swapchainWidth; }
    @Override public int height() { requireOperational(); return swapchainHeight; }
    @Override public List<TextureFormat> colorFormats() {
        requireOperational();
        return List.of(colorFormat);
    }
    @Override public TextureFormat depthFormat() { requireOperational(); return DEPTH_FORMAT; }
    @Override void deleteNative() { device.destroyPresentationTarget(this); }

    void requireOperational() {
        requireAlive();
        if (terminalFailure != null) {
            throw new IllegalStateException(
                    "Vulkan presentation target is terminal after recreation failure",
                    terminalFailure);
        }
    }

    static void validateDepthStorage(
            long[] swapchainImages,
            VulkanPresentationDepth[] depths) {
        if (depths.length != swapchainImages.length) {
            throw new IllegalStateException(
                    "presentation depth count must match swapchain image count");
        }
        Set<Long> images = new HashSet<>();
        Set<Long> memories = new HashSet<>();
        Set<Long> views = new HashSet<>();
        for (VulkanPresentationDepth depth : depths) {
            if (depth == null
                    || !images.add(depth.image)
                    || !memories.add(depth.memory)
                    || !views.add(depth.view)) {
                throw new IllegalStateException(
                        "each swapchain image requires unique presentation depth storage");
            }
        }
    }
}
