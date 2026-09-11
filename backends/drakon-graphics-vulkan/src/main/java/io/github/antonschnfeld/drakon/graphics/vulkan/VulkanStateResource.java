package io.github.antonschnfeld.drakon.graphics.vulkan;

import io.github.antonschnfeld.drakon.graphics.resource.ResourceState;

interface VulkanStateResource {
    ResourceState committedState();

    void commitState(ResourceState state);
}
