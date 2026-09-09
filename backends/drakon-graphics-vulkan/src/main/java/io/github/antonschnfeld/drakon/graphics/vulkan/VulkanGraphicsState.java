package io.github.antonschnfeld.drakon.graphics.vulkan;

import io.github.antonschnfeld.drakon.graphics.resource.GraphicsState;
import io.github.antonschnfeld.drakon.graphics.resource.GraphicsStateDescriptor;

import java.util.List;

final class VulkanGraphicsState extends VulkanResource implements GraphicsState {
    final long pipeline;
    final long pipelineLayout;
    final GraphicsStateDescriptor descriptor;
    final List<VulkanDescriptorLayout> setLayouts;

    VulkanGraphicsState(VulkanDevice device, long pipeline, long pipelineLayout,
                        GraphicsStateDescriptor descriptor, List<VulkanDescriptorLayout> setLayouts) {
        super(device);
        this.pipeline = pipeline;
        this.pipelineLayout = pipelineLayout;
        this.descriptor = descriptor;
        this.setLayouts = List.copyOf(setLayouts);
    }

    @Override void deleteNative() { device.destroyPipeline(pipeline, pipelineLayout); }
}
