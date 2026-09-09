package io.github.antonschnfeld.drakon.graphics.vulkan;

import io.github.antonschnfeld.drakon.graphics.resource.BindingLayout;
import io.github.antonschnfeld.drakon.graphics.resource.BindingSet;
import io.github.antonschnfeld.drakon.graphics.resource.BindingSetDescriptor;

final class VulkanBindingSet extends VulkanResource implements BindingSet {
    final long descriptorSet;
    final BindingSetDescriptor descriptor;

    VulkanBindingSet(VulkanDevice device, long descriptorSet, BindingSetDescriptor descriptor) {
        super(device);
        this.descriptorSet = descriptorSet;
        this.descriptor = descriptor;
    }

    @Override public BindingLayout layout() { requireAlive(); return descriptor.layout(); }
    @Override void deleteNative() { /* Pool owns allocations for this spike. */ }
}
