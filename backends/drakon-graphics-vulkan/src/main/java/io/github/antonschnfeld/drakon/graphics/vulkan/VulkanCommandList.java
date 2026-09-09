package io.github.antonschnfeld.drakon.graphics.vulkan;

import io.github.antonschnfeld.drakon.graphics.command.CommandList;

import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.List;

final class VulkanCommandList implements CommandList {
    final VulkanDevice device;
    final VkCommandBuffer commandBuffer;
    final VulkanPresentationTarget presentationTarget;
    private boolean submitted;

    VulkanCommandList(VulkanDevice device, VkCommandBuffer commandBuffer, VulkanPresentationTarget presentationTarget) {
        this.device = device;
        this.commandBuffer = commandBuffer;
        this.presentationTarget = presentationTarget;
    }

    void markSubmitted() {
        if (submitted) throw new IllegalStateException("command list is single-submit");
        submitted = true;
    }
}
