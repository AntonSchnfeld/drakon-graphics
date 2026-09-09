package io.github.antonschnfeld.drakon.graphics.vulkan;

import io.github.antonschnfeld.drakon.graphics.resource.RenderTarget;
import org.lwjgl.vulkan.VkCommandBuffer;

abstract class VulkanTarget extends VulkanResource implements RenderTarget {
    VulkanTarget(VulkanDevice device) { super(device); }

    abstract long colorView(int index);
    abstract long depthView();
    abstract void validateWritableStates();

    /** Gives presentation targets a chance to acquire/transition their backing image. */
    void prepareForRendering(VkCommandBuffer commandBuffer) {}

    /** Gives presentation targets a chance to transition their backing image for presentation. */
    void finishRendering(VkCommandBuffer commandBuffer) {}

    boolean presentable() { return false; }
}
