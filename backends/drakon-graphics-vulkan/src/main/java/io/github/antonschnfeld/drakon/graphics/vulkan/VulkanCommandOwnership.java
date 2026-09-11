package io.github.antonschnfeld.drakon.graphics.vulkan;

final class VulkanCommandOwnership {
    private Status status = Status.READY;

    synchronized void requireReady() {
        if (status != Status.READY) throw new IllegalStateException("command list is single-submit");
    }

    synchronized void beginSubmission() {
        requireReady();
        status = Status.SUBMITTING;
    }

    synchronized void transferToDevice() {
        if (status != Status.SUBMITTING) throw new IllegalStateException("command list is not submitting");
        status = Status.TRANSFERRED;
    }

    synchronized boolean failAndClaimRelease() {
        if (status != Status.READY && status != Status.SUBMITTING) return false;
        status = Status.RELEASED;
        return true;
    }

    synchronized boolean closeAndClaimRelease() {
        if (status != Status.READY) return false;
        status = Status.RELEASED;
        return true;
    }

    private enum Status {
        READY,
        SUBMITTING,
        TRANSFERRED,
        RELEASED
    }
}
