package io.github.antonschnfeld.drakon.graphics.vulkan;

final class VulkanPresentationState {
    final VulkanPresentationTarget target;
    final long acquisition;
    final int imageIndex;
    final boolean expectedInitialized;
    private boolean recordingInitialized;

    VulkanPresentationState(
            VulkanPresentationTarget target,
            long acquisition,
            int imageIndex,
            boolean expectedInitialized) {
        this.target = target;
        this.acquisition = acquisition;
        this.imageIndex = imageIndex;
        this.expectedInitialized = expectedInitialized;
        recordingInitialized = expectedInitialized;
    }

    boolean recordingInitialized() {
        return recordingInitialized;
    }

    void markInitialized() {
        recordingInitialized = true;
    }
}
