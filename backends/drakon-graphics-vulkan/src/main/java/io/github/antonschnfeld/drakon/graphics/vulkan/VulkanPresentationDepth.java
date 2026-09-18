package io.github.antonschnfeld.drakon.graphics.vulkan;

import java.util.function.LongConsumer;

/** Native depth storage owned by one Vulkan swapchain image. */
final class VulkanPresentationDepth {
    final long image;
    final long memory;
    final long view;

    VulkanPresentationDepth(long image, long memory, long view) {
        if (image == 0L || memory == 0L || view == 0L) {
            throw new IllegalArgumentException("presentation depth handles must be nonzero");
        }
        this.image = image;
        this.memory = memory;
        this.view = view;
    }

    static void destroyAll(
            VulkanPresentationDepth[] depths,
            LongConsumer destroyView,
            LongConsumer destroyImage,
            LongConsumer freeMemory) {
        for (VulkanPresentationDepth depth : depths) {
            if (depth == null) continue;
            destroyView.accept(depth.view);
            destroyImage.accept(depth.image);
            freeMemory.accept(depth.memory);
        }
    }
}
