package io.github.antonschnfeld.drakon.graphics.vulkan;

import io.github.antonschnfeld.drakon.graphics.resource.ResourceState;

import java.util.IdentityHashMap;

final class VulkanRecordingState {
    private final IdentityHashMap<VulkanStateResource, ResourceState> expectedStates = new IdentityHashMap<>();
    private final IdentityHashMap<VulkanStateResource, ResourceState> finalStates = new IdentityHashMap<>();

    ResourceState effectiveState(VulkanStateResource resource) {
        ResourceState committed = expectedStates.computeIfAbsent(resource, VulkanStateResource::committedState);
        return finalStates.getOrDefault(resource, committed);
    }

    void transition(VulkanStateResource resource, ResourceState from, ResourceState to) {
        ResourceState actual = effectiveState(resource);
        if (actual != from) {
            throw new IllegalStateException("resource state is " + actual + " but transition expected " + from);
        }
        finalStates.put(resource, to);
    }

    VulkanCommandState finish() {
        return new VulkanCommandState(expectedStates, finalStates);
    }

    void clear() {
        expectedStates.clear();
        finalStates.clear();
    }
}
