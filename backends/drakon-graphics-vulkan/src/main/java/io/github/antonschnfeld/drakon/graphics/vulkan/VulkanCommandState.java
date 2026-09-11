package io.github.antonschnfeld.drakon.graphics.vulkan;

import io.github.antonschnfeld.drakon.graphics.resource.ResourceState;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

final class VulkanCommandState {
    private final Map<VulkanStateResource, ResourceState> expectedStates;
    private final Map<VulkanStateResource, ResourceState> finalStates;

    VulkanCommandState(
            IdentityHashMap<VulkanStateResource, ResourceState> expectedStates,
            IdentityHashMap<VulkanStateResource, ResourceState> finalStates) {
        this.expectedStates = immutableIdentityCopy(expectedStates);
        this.finalStates = immutableIdentityCopy(finalStates);
    }

    void validateCommittedStates() {
        for (Map.Entry<VulkanStateResource, ResourceState> entry : expectedStates.entrySet()) {
            ResourceState actual = entry.getKey().committedState();
            if (actual != entry.getValue()) {
                throw new IllegalStateException(
                        "command list recorded resource state " + entry.getValue()
                                + " but committed state is now " + actual);
            }
        }
    }

    void commitFinalStates() {
        for (Map.Entry<VulkanStateResource, ResourceState> entry : finalStates.entrySet()) {
            entry.getKey().commitState(entry.getValue());
        }
    }

    private static <K, V> Map<K, V> immutableIdentityCopy(IdentityHashMap<K, V> source) {
        return Collections.unmodifiableMap(new IdentityHashMap<>(source));
    }
}
