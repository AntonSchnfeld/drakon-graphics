package io.github.antonschnfeld.drakon.graphics.vulkan;

import io.github.antonschnfeld.drakon.graphics.resource.RenderTarget;

import java.util.Objects;

/**
 * Result of presentation-aware Vulkan device bootstrap.
 *
 * @param device created presentation-capable device
 * @param target first independently owned presentation target
 */
public record VulkanPresentation(VulkanDevice device, RenderTarget target) {
    /** Validates the created device and its first compatible target. */
    public VulkanPresentation {
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(target, "target");
    }
}
