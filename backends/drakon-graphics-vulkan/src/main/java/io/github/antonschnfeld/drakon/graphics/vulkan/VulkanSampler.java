package io.github.antonschnfeld.drakon.graphics.vulkan;

import io.github.antonschnfeld.drakon.graphics.resource.Sampler;

final class VulkanSampler extends VulkanResource implements Sampler {
    final long handle;
    VulkanSampler(VulkanDevice device, long handle) { super(device); this.handle = handle; }
    @Override void deleteNative() { device.destroySampler(handle); }
}
