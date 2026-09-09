package io.github.antonschnfeld.drakon.graphics.vulkan;

import io.github.antonschnfeld.drakon.graphics.resource.BindingLayout;

final class VulkanDescriptorLayout {
    final BindingLayout logical;
    final long handle;
    VulkanDescriptorLayout(BindingLayout logical, long handle) { this.logical = logical; this.handle = handle; }
}
