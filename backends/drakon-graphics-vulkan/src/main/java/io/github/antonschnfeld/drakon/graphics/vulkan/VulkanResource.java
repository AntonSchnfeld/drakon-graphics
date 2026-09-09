package io.github.antonschnfeld.drakon.graphics.vulkan;

import io.github.antonschnfeld.drakon.graphics.resource.GpuResource;

abstract class VulkanResource implements GpuResource {
    final VulkanDevice device;
    private boolean closed;

    VulkanResource(VulkanDevice device) { this.device = device; }

    final void requireAlive() {
        if (closed) throw new IllegalStateException("resource is closed");
        device.requireOpen();
    }

    final boolean isClosed() { return closed; }

    @Override
    public final void close() {
        if (!closed) {
            closed = true;
            if (!device.isClosed()) deleteNative();
        }
    }

    abstract void deleteNative();
}
