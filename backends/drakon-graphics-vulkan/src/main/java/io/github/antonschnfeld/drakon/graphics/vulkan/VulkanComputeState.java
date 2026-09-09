package io.github.antonschnfeld.drakon.graphics.vulkan;

import io.github.antonschnfeld.drakon.graphics.resource.ComputeState;
import io.github.antonschnfeld.drakon.graphics.resource.ComputeStateDescriptor;

import java.util.List;

final class VulkanComputeState extends VulkanResource implements ComputeState {
    final long pipeline;
    final long pipelineLayout;
    final ComputeStateDescriptor descriptor;
    final List<VulkanDescriptorLayout> setLayouts;

    VulkanComputeState(VulkanDevice device, long pipeline, long pipelineLayout,
                       ComputeStateDescriptor descriptor, List<VulkanDescriptorLayout> setLayouts) {
        super(device);
        this.pipeline = pipeline;
        this.pipelineLayout = pipelineLayout;
        this.descriptor = descriptor;
        this.setLayouts = List.copyOf(setLayouts);
    }

    @Override void deleteNative() { device.destroyPipeline(pipeline, pipelineLayout); }
}
