package io.github.antonschnfeld.drakon.graphics.vulkan;

/** Backend-private handoff for a presentation frame skipped during zero-extent deferral. */
final class VulkanSkippedPresentation {
    private boolean submitted;

    void submit() {
        submitted = true;
    }

    boolean consume() {
        boolean wasSubmitted = submitted;
        submitted = false;
        return wasSubmitted;
    }

    void clear() {
        submitted = false;
    }
}
