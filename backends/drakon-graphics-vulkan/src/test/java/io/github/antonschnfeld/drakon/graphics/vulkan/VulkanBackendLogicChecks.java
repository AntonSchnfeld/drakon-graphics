package io.github.antonschnfeld.drakon.graphics.vulkan;

import io.github.antonschnfeld.drakon.graphics.resource.ResourceState;
import io.github.antonschnfeld.drakon.graphics.resource.IndexType;
import io.github.antonschnfeld.drakon.graphics.resource.ScissorRect;
import io.github.antonschnfeld.drakon.graphics.resource.VertexFormat;
import io.github.antonschnfeld.drakon.graphics.resource.VertexInputRate;
import io.github.antonschnfeld.drakon.graphics.resource.VertexLayout;

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
        scissorClippingIsSafe();
        uniformAlignmentIsChecked();
        drawRangesAreChecked();
        transitionFromValidationFollowsConfig();
        System.out.println("Vulkan backend logic checks passed.");
    }

    private static void recordingStateIsLocalUntilCommit() {
        FakeStateResource resource = new FakeStateResource(ResourceState.UNDEFINED);
        VulkanRecordingState recording = new VulkanRecordingState();
        recording.transition(resource, ResourceState.COPY_DST);
        require(resource.committedState() == ResourceState.UNDEFINED, "recording mutated committed state");
        require(recording.effectiveState(resource) == ResourceState.COPY_DST, "recording state did not advance");
        recording.transition(resource, ResourceState.COPY_SRC);
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
        first.transition(resource, ResourceState.COPY_DST);
        stale.transition(resource, ResourceState.COPY_SRC);

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
        recording.transition(resource, ResourceState.COPY_DST);
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

    private static void scissorClippingIsSafe() {
        assertScissor(new ScissorRect(0, 0, 100, 80), 100, 80, 0, 0, 100, 80);
        assertScissor(new ScissorRect(-10, 5, 30, 20), 100, 80, 0, 5, 20, 20);
        assertScissor(new ScissorRect(5, -10, 20, 30), 100, 80, 5, 0, 20, 20);
        assertScissor(new ScissorRect(90, 5, 30, 20), 100, 80, 90, 5, 10, 20);
        assertScissor(new ScissorRect(5, 70, 20, 30), 100, 80, 5, 70, 20, 10);
        assertScissor(new ScissorRect(-10, -20, 130, 120), 100, 80, 0, 0, 100, 80);
        assertScissor(new ScissorRect(-30, -20, 10, 10), 100, 80, 0, 0, 0, 0);
        assertScissor(new ScissorRect(120, 90, 10, 10), 100, 80, 100, 80, 0, 0);
        assertScissor(new ScissorRect(Integer.MAX_VALUE - 4, 0, 10, 10),
                100, 80, 100, 0, 0, 10);
    }

    private static void uniformAlignmentIsChecked() {
        VulkanValidation.validateUniformOffset(0, 256);
        VulkanValidation.validateUniformOffset(512, 256);
        expect(IllegalArgumentException.class, () -> VulkanValidation.validateUniformOffset(4, 256));
    }

    private static void drawRangesAreChecked() {
        VertexLayout layout = VertexLayout.builder()
                .binding(0, 16, VertexInputRate.PER_VERTEX)
                .attribute(0, 0, VertexFormat.FLOAT2, 0)
                .attribute(1, 0, VertexFormat.FLOAT2, 8)
                .build();
        VulkanValidation.validateVertexRange(
                64, 0, layout.bindings().get(0), layout.attributes(), false, 4, 1, 0, 0);
        expect(IllegalArgumentException.class, () -> VulkanValidation.validateVertexRange(
                64, 0, layout.bindings().get(0), layout.attributes(), false, 4, 1, 1, 0));
        expect(IllegalArgumentException.class, () -> VulkanValidation.validateVertexRange(
                Long.MAX_VALUE, Long.MAX_VALUE - 4, layout.bindings().get(0),
                layout.attributes(), false, 2, 1, 0, 0));

        VertexLayout instances = VertexLayout.builder()
                .binding(1, 16, VertexInputRate.PER_INSTANCE)
                .attribute(2, 1, VertexFormat.FLOAT4, 0)
                .build();
        VulkanValidation.validateVertexRange(
                80, 0, instances.bindings().get(0), instances.attributes(), true, 1, 2, 3, 3);
        expect(IllegalArgumentException.class, () -> VulkanValidation.validateVertexRange(
                79, 0, instances.bindings().get(0), instances.attributes(), true, 1, 2, 3, 3));

        VulkanValidation.validateIndexRange(12, 0, IndexType.UINT16, 0, 6);
        expect(IllegalArgumentException.class,
                () -> VulkanValidation.validateIndexRange(12, 0, IndexType.UINT16, 6, 1));
        expect(IllegalArgumentException.class,
                () -> VulkanValidation.validateIndexRange(Long.MAX_VALUE, Long.MAX_VALUE - 1,
                        IndexType.UINT32, Integer.MAX_VALUE, 1));
    }

    private static void transitionFromValidationFollowsConfig() {
        VulkanValidation.validateTransitionFrom(
                false, ResourceState.UNDEFINED, ResourceState.VERTEX_READ, "buffer");
        expect(IllegalStateException.class, () -> VulkanValidation.validateTransitionFrom(
                true, ResourceState.UNDEFINED, ResourceState.VERTEX_READ, "buffer"));
    }

    private static void assertScissor(
            ScissorRect input,
            int targetWidth,
            int targetHeight,
            int x,
            int y,
            int width,
            int height) {
        VulkanValidation.ClippedScissor expected =
                new VulkanValidation.ClippedScissor(x, y, width, height);
        VulkanValidation.ClippedScissor actual =
                VulkanValidation.clipScissor(input, targetWidth, targetHeight);
        require(expected.equals(actual), "expected " + expected + " but got " + actual);
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
