package io.github.antonschnfeld.drakon.graphics.vulkan;

import io.github.antonschnfeld.drakon.graphics.resource.ResourceState;

/** Hardware-free checks for Vulkan submission-state and resource-lifetime bookkeeping. */
public final class VulkanBackendLogicChecks {
    private VulkanBackendLogicChecks() {}

    /** Runs the backend bookkeeping checks. */
    public static void main(String[] args) {
        recordingStateIsLocalUntilCommit();
        staleRecordingIsRejected();
        staleObservedUsageIsRejected();
        rejectedRecordingDoesNotCommit();
        presentationStateIsRecordingLocal();
        resourceLifetimeDefersNativeDeletion();
        resourceLifetimeIsIdempotentAndExactlyOnce();
        commandOwnershipTransfersOrReleasesExactlyOnce();
        System.out.println("Vulkan backend logic checks passed.");
    }

    private static void recordingStateIsLocalUntilCommit() {
        FakeStateResource resource = new FakeStateResource(ResourceState.UNDEFINED);
        VulkanRecordingState recording = new VulkanRecordingState();
        recording.transition(resource, ResourceState.UNDEFINED, ResourceState.COPY_DST);
        require(resource.committedState() == ResourceState.UNDEFINED, "recording mutated committed state");
        require(recording.effectiveState(resource) == ResourceState.COPY_DST, "recording state did not advance");
        recording.transition(resource, ResourceState.COPY_DST, ResourceState.COPY_SRC);
        require(recording.effectiveState(resource) == ResourceState.COPY_SRC, "local transitions did not compose");

        VulkanCommandState finished = recording.finish();
        require(resource.committedState() == ResourceState.UNDEFINED, "finish mutated committed state");
        finished.validateCommittedStates();
        finished.commitFinalStates();
        require(resource.committedState() == ResourceState.COPY_SRC, "successful commit did not apply final state");
        require(resource.commitCount == 1, "duplicate transitions committed the same resource more than once");
    }

    private static void staleRecordingIsRejected() {
        FakeStateResource resource = new FakeStateResource(ResourceState.UNDEFINED);
        VulkanRecordingState first = new VulkanRecordingState();
        VulkanRecordingState stale = new VulkanRecordingState();
        first.transition(resource, ResourceState.UNDEFINED, ResourceState.COPY_DST);
        stale.transition(resource, ResourceState.UNDEFINED, ResourceState.COPY_SRC);

        VulkanCommandState firstList = first.finish();
        VulkanCommandState staleList = stale.finish();
        firstList.validateCommittedStates();
        firstList.commitFinalStates();
        expect(IllegalStateException.class, staleList::validateCommittedStates);
        require(resource.committedState() == ResourceState.COPY_DST, "stale validation changed committed state");
    }

    private static void rejectedRecordingDoesNotCommit() {
        FakeStateResource resource = new FakeStateResource(ResourceState.UNDEFINED);
        VulkanRecordingState recording = new VulkanRecordingState();
        recording.transition(resource, ResourceState.UNDEFINED, ResourceState.COPY_DST);
        VulkanCommandState list = recording.finish();
        resource.commitState(ResourceState.COPY_SRC);

        expect(IllegalStateException.class, list::validateCommittedStates);
        require(resource.committedState() == ResourceState.COPY_SRC, "rejected list committed its final state");
    }

    private static void staleObservedUsageIsRejected() {
        FakeStateResource resource = new FakeStateResource(ResourceState.SAMPLED_READ);
        VulkanRecordingState recording = new VulkanRecordingState();
        require(recording.effectiveState(resource) == ResourceState.SAMPLED_READ, "usage observed wrong state");
        VulkanCommandState list = recording.finish();
        resource.commitState(ResourceState.COPY_DST);
        expect(IllegalStateException.class, list::validateCommittedStates);
    }

    private static void presentationStateIsRecordingLocal() {
        VulkanPresentationState state = new VulkanPresentationState(null, 7L, 2, false);
        require(!state.expectedInitialized, "presentation expectation started initialized");
        require(!state.recordingInitialized(), "presentation recording started initialized");
        state.markInitialized();
        require(state.recordingInitialized(), "presentation recording state did not advance");
        require(!state.expectedInitialized, "recording mutated committed presentation expectation");
    }

    private static void resourceLifetimeDefersNativeDeletion() {
        VulkanResourceLifetime lifetime = new VulkanResourceLifetime();
        lifetime.retainForSubmission();
        require(!lifetime.closeAndClaimNativeDestruction(), "in-flight close claimed native deletion");
        require(lifetime.isLogicalClosed(), "close was not immediately logical");
        expect(IllegalStateException.class, lifetime::requireLogicallyOpen);
        require(lifetime.inFlightReferences() == 1, "in-flight reference was lost");
        require(lifetime.releaseAndClaimNativeDestruction(), "retirement did not claim native deletion");
        require(lifetime.isNativeDestroyed(), "native lifetime was not marked destroyed");
    }

    private static void resourceLifetimeIsIdempotentAndExactlyOnce() {
        VulkanResourceLifetime immediate = new VulkanResourceLifetime();
        require(immediate.closeAndClaimNativeDestruction(), "first close did not claim immediate deletion");
        require(!immediate.closeAndClaimNativeDestruction(), "repeated close claimed deletion twice");
        require(!immediate.closeForDeviceAndClaimNativeDestruction(), "device close claimed deletion twice");

        VulkanResourceLifetime retainedTwice = new VulkanResourceLifetime();
        retainedTwice.retainForSubmission();
        retainedTwice.retainForSubmission();
        require(!retainedTwice.closeAndClaimNativeDestruction(), "multi-retained close deleted early");
        require(!retainedTwice.releaseAndClaimNativeDestruction(), "first retirement deleted early");
        require(retainedTwice.releaseAndClaimNativeDestruction(), "last retirement did not delete");
    }

    private static void commandOwnershipTransfersOrReleasesExactlyOnce() {
        VulkanCommandOwnership abandoned = new VulkanCommandOwnership();
        require(abandoned.closeAndClaimRelease(), "unsubmitted close did not release command ownership");
        require(!abandoned.closeAndClaimRelease(), "repeated command-list close released twice");
        expect(IllegalStateException.class, abandoned::beginSubmission);

        VulkanCommandOwnership failed = new VulkanCommandOwnership();
        failed.beginSubmission();
        require(failed.failAndClaimRelease(), "failed submission did not release command ownership");
        require(!failed.failAndClaimRelease(), "failed submission released command ownership twice");

        VulkanCommandOwnership submitted = new VulkanCommandOwnership();
        submitted.beginSubmission();
        submitted.transferToDevice();
        require(!submitted.closeAndClaimRelease(), "submitted-list close reclaimed device ownership");
        require(!submitted.failAndClaimRelease(), "submitted list transitioned back to failed ownership");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void expect(Class<? extends Throwable> type, Runnable operation) {
        try {
            operation.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError("expected " + type.getSimpleName() + " but got " + failure, failure);
        }
        throw new AssertionError("expected " + type.getSimpleName());
    }

    private static final class FakeStateResource implements VulkanStateResource {
        private ResourceState state;
        private int commitCount;

        FakeStateResource(ResourceState state) {
            this.state = state;
        }

        @Override
        public ResourceState committedState() {
            return state;
        }

        @Override
        public void commitState(ResourceState state) {
            this.state = state;
            commitCount++;
        }
    }
}
