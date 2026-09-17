package io.github.antonschnfeld.drakon.graphics.vulkan;

import io.github.antonschnfeld.drakon.graphics.command.CommandList;

import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.List;

final class VulkanCommandList implements CommandList {
    final VulkanDevice device;
    final VkCommandBuffer commandBuffer;
    final VulkanPresentationState presentationState;
    final VulkanPresentationTarget skippedPresentationTarget;
    final VulkanCommandState commandState;
    final List<VulkanResource> resources;
    private final VulkanCommandOwnership ownership = new VulkanCommandOwnership();

    VulkanCommandList(
            VulkanDevice device,
            VkCommandBuffer commandBuffer,
            VulkanPresentationState presentationState,
            VulkanPresentationTarget skippedPresentationTarget,
            VulkanCommandState commandState,
            List<VulkanResource> resources) {
        this.device = device;
        this.commandBuffer = commandBuffer;
        this.presentationState = presentationState;
        this.skippedPresentationTarget = skippedPresentationTarget;
        this.commandState = commandState;
        this.resources = List.copyOf(resources);
    }

    void requireReady() { ownership.requireReady(); }

    void beginSubmission() { ownership.beginSubmission(); }

    void markSubmitted() { ownership.transferToDevice(); }

    void failAndRelease() {
        if (ownership.failAndClaimRelease()) device.freeCommandBufferIfOpen(commandBuffer);
    }

    @Override
    public void close() {
        if (ownership.closeAndClaimRelease()) device.freeCommandBufferIfOpen(commandBuffer);
    }
}
