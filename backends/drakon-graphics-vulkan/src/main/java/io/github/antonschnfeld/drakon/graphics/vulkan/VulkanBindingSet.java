package io.github.antonschnfeld.drakon.graphics.vulkan;

import io.github.antonschnfeld.drakon.graphics.resource.BindingLayout;
import io.github.antonschnfeld.drakon.graphics.resource.BindingSet;
import io.github.antonschnfeld.drakon.graphics.resource.BindingSetDescriptor;

import java.util.List;

final class VulkanBindingSet extends VulkanResource implements BindingSet {
    final long descriptorSet;
    final BindingSetDescriptor descriptor;
    private final List<VulkanResource> dependencies;

    VulkanBindingSet(
            VulkanDevice device,
            long descriptorSet,
            BindingSetDescriptor descriptor,
            List<VulkanResource> dependencies) {
        super(device);
        this.descriptorSet = descriptorSet;
        this.descriptor = descriptor;
        this.dependencies = List.copyOf(dependencies);
    }

    @Override public BindingLayout layout() { requireAlive(); return descriptor.layout(); }
    @Override List<VulkanResource> dependencies() { return dependencies; }
    @Override void deleteNative() { device.destroyDescriptorSet(descriptorSet); }
}
