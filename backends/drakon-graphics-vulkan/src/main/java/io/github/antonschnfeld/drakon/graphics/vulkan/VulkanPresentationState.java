package io.github.antonschnfeld.drakon.graphics.vulkan;

final class VulkanPresentationState {
    final VulkanPresentationTarget target;
    final long acquisition;
    final int imageIndex;
    final boolean expectedInitialized;
    final boolean expectedDepthInitialized;
    private boolean recordingInitialized;
    private boolean recordingDepthInitialized;

    VulkanPresentationState(
            VulkanPresentationTarget target,
            long acquisition,
            int imageIndex,
            boolean expectedInitialized,
            boolean expectedDepthInitialized) {
        this.target = target;
        this.acquisition = acquisition;
        this.imageIndex = imageIndex;
        this.expectedInitialized = expectedInitialized;
        this.expectedDepthInitialized = expectedDepthInitialized;
        recordingInitialized = expectedInitialized;
        recordingDepthInitialized = expectedDepthInitialized;
    }

    boolean recordingInitialized() {
        return recordingInitialized;
    }

    void markInitialized() {
        recordingInitialized = true;
    }

    boolean recordingDepthInitialized() {
        return recordingDepthInitialized;
    }

    void markDepthInitialized() {
        recordingDepthInitialized = true;
    }
}
