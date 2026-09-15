package io.github.antonschnfeld.drakon.graphics.vulkan;

import io.github.antonschnfeld.drakon.graphics.resource.RenderTarget;
import org.lwjgl.vulkan.VkCommandBuffer;

abstract class VulkanTarget extends VulkanResource implements RenderTarget {
    VulkanTarget(VulkanDevice device) { super(device); }

    abstract long colorView(int index);
    abstract long depthView();
    abstract void validateWritableStates(VulkanRecordingState states);

    /** Gives presentation targets a chance to acquire/transition their backing image. */
    VulkanPresentationState prepareForRendering(
            VkCommandBuffer commandBuffer, VulkanPresentationState presentationState) {
        return presentationState;
    }

    /** Gives presentation targets a chance to transition their backing image for presentation. */
    void finishRendering(VkCommandBuffer commandBuffer, VulkanPresentationState presentationState) {}
}
