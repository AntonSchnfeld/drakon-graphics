package io.github.antonschnfeld.drakon.graphics.vulkan;

import io.github.antonschnfeld.drakon.graphics.resource.GpuResource;

import java.util.List;

abstract class VulkanResource implements GpuResource {
    final VulkanDevice device;
    private final VulkanResourceLifetime lifetime = new VulkanResourceLifetime();

    VulkanResource(VulkanDevice device) { this.device = device; }

    final void requireAlive() {
        lifetime.requireLogicallyOpen();
        device.requireOpen();
    }

    final boolean isClosed() { return lifetime.isLogicalClosed(); }

    final void retainForSubmission() { lifetime.retainForSubmission(); }

    final void releaseFromSubmission() {
        if (lifetime.releaseAndClaimNativeDestruction() && !device.isClosed()) deleteNative();
    }

    final void destroyForDeviceClose() {
        if (lifetime.closeForDeviceAndClaimNativeDestruction()) deleteNative();
    }

    List<VulkanResource> dependencies() { return List.of(); }

    @Override
    public final void close() {
        if (lifetime.closeAndClaimNativeDestruction() && !device.isClosed()) deleteNative();
    }

    abstract void deleteNative();
}
