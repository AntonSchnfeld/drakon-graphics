package io.github.antonschnfeld.drakon.graphics.vulkan;

import io.github.antonschnfeld.drakon.graphics.resource.Buffer;
import io.github.antonschnfeld.drakon.graphics.resource.BufferDescriptor;
import io.github.antonschnfeld.drakon.graphics.resource.BufferUsage;
import io.github.antonschnfeld.drakon.graphics.resource.ResourceState;

import java.util.Set;

final class VulkanBuffer extends VulkanResource implements Buffer, VulkanStateResource {
    final long handle;
    final long memory;
    private final BufferDescriptor descriptor;
    ResourceState state = ResourceState.UNDEFINED;

    VulkanBuffer(VulkanDevice device, long handle, long memory, BufferDescriptor descriptor) {
        super(device);
        this.handle = handle;
        this.memory = memory;
        this.descriptor = descriptor;
    }

    @Override public long size() { requireAlive(); return descriptor.size(); }
    @Override public Set<BufferUsage> usage() { requireAlive(); return descriptor.usage(); }
    @Override public ResourceState committedState() { return state; }
    @Override public void commitState(ResourceState state) { this.state = state; }
    @Override void deleteNative() { device.destroyBuffer(handle, memory); }
}
