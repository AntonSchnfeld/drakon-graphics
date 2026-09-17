package io.github.antonschnfeld.drakon.graphics.vulkan;

import io.github.antonschnfeld.drakon.graphics.resource.ResourceState;
import io.github.antonschnfeld.drakon.graphics.resource.IndexType;
import io.github.antonschnfeld.drakon.graphics.resource.ScissorRect;
import io.github.antonschnfeld.drakon.graphics.resource.VertexFormat;
import io.github.antonschnfeld.drakon.graphics.resource.VertexInputRate;
import io.github.antonschnfeld.drakon.graphics.resource.VertexLayout;
import io.github.antonschnfeld.drakon.graphics.resource.BufferUsage;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.List;
import java.util.Set;
import java.util.ArrayList;

import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_VERBOSE_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;

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
        nativeDebugSelectionFollowsConfigAndAvailability();
        presentationExtensionsAreComposedWithoutDuplicates();
        nativeDebugMessagesAreFilteredAndFormatted();
        ffmScratchViewsHaveExactNativeLayout();
        mappedCopyUsesTheSelectedRange();
        bufferUpdatePlanningIsExact();
        swapchainExtentPlanningHandlesZeroAndVariableExtents();
        swapchainRecreationLifecycleDefersAndRecovers();
        skippedPresentationIsBackendPrivateAndOneShot();
        System.out.println("Vulkan backend logic checks passed.");
    }

    private static void nativeDebugSelectionFollowsConfigAndAvailability() {
        Set<String> allLayers = Set.of(VulkanNativeDebug.VALIDATION_LAYER);
        Set<String> allExtensions = Set.of(VK_EXT_DEBUG_UTILS_EXTENSION_NAME);

        VulkanNativeDebug.Selection disabled = VulkanNativeDebug.select(
                false, List.of(), allLayers, allExtensions);
        require(disabled.layers().isEmpty(), "default mode selected the validation layer");
        require(disabled.extensions().isEmpty(), "default mode selected debug utils");
        require(!disabled.debugUtils(), "default mode requested a debug messenger");

        VulkanNativeDebug.Selection available = VulkanNativeDebug.select(
                true, List.of(), allLayers, allExtensions);
        require(available.layers().equals(List.of(VulkanNativeDebug.VALIDATION_LAYER)),
                "debug mode did not select the available validation layer");
        require(available.extensions().equals(List.of(VK_EXT_DEBUG_UTILS_EXTENSION_NAME)),
                "debug mode did not select available debug utils");
        require(available.debugUtils(), "debug mode did not request a messenger");

        VulkanNativeDebug.Selection missingLayer = VulkanNativeDebug.select(
                true, List.of(), Set.of(), allExtensions);
        require(missingLayer.layers().isEmpty(), "unavailable validation layer was selected");
        require(missingLayer.debugUtils(), "missing validation layer incorrectly disabled debug utils");

        VulkanNativeDebug.Selection missingDebugUtils = VulkanNativeDebug.select(
                true, List.of(), allLayers, Set.of());
        require(missingDebugUtils.validationLayer(), "missing debug utils incorrectly disabled validation layer");
        require(!missingDebugUtils.debugUtils(), "unavailable debug utils requested a messenger");
    }

    private static void presentationExtensionsAreComposedWithoutDuplicates() {
        String surface = "VK_KHR_surface";
        VulkanNativeDebug.Selection selection = VulkanNativeDebug.select(
                true,
                List.of(surface, surface, VK_EXT_DEBUG_UTILS_EXTENSION_NAME),
                Set.of(VulkanNativeDebug.VALIDATION_LAYER),
                Set.of(VK_EXT_DEBUG_UTILS_EXTENSION_NAME));
        require(selection.extensions().equals(List.of(surface, VK_EXT_DEBUG_UTILS_EXTENSION_NAME)),
                "presentation/debug extensions were not composed in stable unique order");
    }

    private static void nativeDebugMessagesAreFilteredAndFormatted() {
        require(VulkanNativeDebug.accepts(VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT),
                "Vulkan warning was filtered");
        require(VulkanNativeDebug.accepts(VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT),
                "Vulkan error was filtered");
        require(!VulkanNativeDebug.accepts(VK_DEBUG_UTILS_MESSAGE_SEVERITY_VERBOSE_BIT_EXT),
                "Vulkan verbose message was accepted");
        require(!VulkanNativeDebug.accepts(VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT),
                "Vulkan info message was accepted");
        String formatted = VulkanNativeDebug.format(
                VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT,
                VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT,
                "VUID-test",
                17,
                "bad\nstate");
        require(formatted.contains("[ERROR][VALIDATION]"), "Vulkan message omitted severity/type");
        require(formatted.contains("VUID-test(17)"), "Vulkan message omitted its ID");
        require(!formatted.contains("\n"), "Vulkan message was not compacted");
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

    private static void ffmScratchViewsHaveExactNativeLayout() {
        try (Arena arena = Arena.ofConfined()) {
            IntBuffer ints = VulkanFfm.ints(arena, 3);
            require(ints.capacity() == 3, "FFM IntBuffer capacity is wrong");
            require(ints.order().equals(ByteOrder.nativeOrder()), "FFM IntBuffer byte order is not native");

            MemorySegment storage = VulkanFfm.storage(arena, 96, 16);
            require(storage.byteSize() == 96, "FFM scratch storage capacity is wrong");
            require(storage.address() % 16 == 0, "FFM scratch storage is misaligned");
        }
    }

    private static void mappedCopyUsesTheSelectedRange() {
        ByteBuffer source = ByteBuffer.allocate(8);
        for (int i = 0; i < source.capacity(); i++) source.put(i, (byte) (20 + i));
        source.position(3).limit(7);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment destination = arena.allocate(4, Byte.BYTES);
            VulkanFfm.copyToBorrowed(source, destination.address(), destination.byteSize());
            require(source.position() == 3, "mapped copy changed caller position");
            require(source.limit() == 7, "mapped copy changed caller limit");
            for (int i = 0; i < destination.byteSize(); i++) {
                require(destination.get(ValueLayout.JAVA_BYTE, i) == (byte) (23 + i),
                        "mapped copy changed bytes");
            }
            expect(IllegalArgumentException.class,
                    () -> VulkanFfm.copyToBorrowed(source, destination.address(), 3));
        }
    }

    private static void bufferUpdatePlanningIsExact() {
        require((VulkanMappings.bufferUsage(Set.of(BufferUsage.VERTEX))
                & VK_BUFFER_USAGE_TRANSFER_DST_BIT) != 0,
                "portable Vulkan buffers lack the internal transfer-destination usage");
        VulkanBufferUpdates.validateRange(16, 0, 4);
        VulkanBufferUpdates.validateRange(16, 12, 4);
        expect(IllegalArgumentException.class,
                () -> VulkanBufferUpdates.validateRange(16, -4, 4));
        expect(IllegalArgumentException.class,
                () -> VulkanBufferUpdates.validateRange(16, 2, 4));
        expect(IllegalArgumentException.class,
                () -> VulkanBufferUpdates.validateRange(16, 0, 2));
        expect(IllegalArgumentException.class,
                () -> VulkanBufferUpdates.validateRange(16, 0, 0));
        expect(IllegalArgumentException.class,
                () -> VulkanBufferUpdates.validateRange(Long.MAX_VALUE, Long.MAX_VALUE - 3, 4));

        assertUpdatePlan(12, VulkanBufferUpdates.MAX_UPDATE_BYTES, 1);
        assertUpdatePlan(12, VulkanBufferUpdates.MAX_UPDATE_BYTES + 4L, 2);
        assertUpdatePlan(12, VulkanBufferUpdates.MAX_UPDATE_BYTES * 3L + 12L, 4);
        assertUpdatePlan(4, Integer.MAX_VALUE - 3L,
                (int) ((Integer.MAX_VALUE - 3L + VulkanBufferUpdates.MAX_UPDATE_BYTES - 1L)
                        / VulkanBufferUpdates.MAX_UPDATE_BYTES));
    }

    private static void swapchainExtentPlanningHandlesZeroAndVariableExtents() {
        VulkanSwapchainExtent.Plan fixed = VulkanSwapchainExtent.plan(
                800, 600,
                () -> { throw new AssertionError("fixed extent queried surface width"); },
                () -> { throw new AssertionError("fixed extent queried surface height"); },
                64, 64, 4096, 4096);
        require(fixed.recreatable() && fixed.width() == 800 && fixed.height() == 600,
                "fixed positive extent was not recreatable");

        assertDeferredExtent(0, 600);
        assertDeferredExtent(800, 0);
        assertDeferredExtent(0, 0);

        VulkanSwapchainExtent.Plan variable = VulkanSwapchainExtent.plan(
                VulkanSwapchainExtent.VARIABLE,
                VulkanSwapchainExtent.VARIABLE,
                () -> 2000,
                () -> 120,
                320, 240, 1280, 720);
        require(variable.recreatable() && variable.width() == 1280 && variable.height() == 240,
                "variable extent did not use and clamp the surface-factory extent");
    }

    private static void assertDeferredExtent(int width, int height) {
        VulkanSwapchainExtent.Plan extent = VulkanSwapchainExtent.plan(
                width, height,
                () -> { throw new AssertionError("fixed zero extent queried surface width"); },
                () -> { throw new AssertionError("fixed zero extent queried surface height"); },
                64, 64, 4096, 4096);
        require(!extent.recreatable(), "zero fixed extent was considered recreatable");
    }

    private static void swapchainRecreationLifecycleDefersAndRecovers() {
        VulkanPresentationTarget target = new VulkanPresentationTarget(null, null, 0L);
        target.swapchainWidth = 800;
        target.swapchainHeight = 600;

        target.swapchainRecreation.request();
        require(target.swapchainRecreation.pending(), "recreation request was not retained");
        require(target.swapchainRecreation.completedCount() == 0,
                "deferred recreation counted as completed");
        require(target.swapchainWidth == 800 && target.swapchainHeight == 600,
                "deferred recreation changed the last valid target extent");

        target.swapchainWidth = 1280;
        target.swapchainHeight = 720;
        target.swapchainRecreation.complete();
        require(!target.swapchainRecreation.pending(), "successful retry left recreation pending");
        require(target.swapchainRecreation.completedCount() == 1,
                "successful retry did not increment completed recreation count");
        require(target.swapchainWidth == 1280 && target.swapchainHeight == 720,
                "successful retry did not preserve its new target extent");
    }

    private static void skippedPresentationIsBackendPrivateAndOneShot() {
        VulkanSkippedPresentation skipped = new VulkanSkippedPresentation();
        require(!skipped.consume(), "normal presentation was mistaken for a skipped frame");

        skipped.submit();
        skipped.submit();
        require(skipped.consume(), "submitted skipped presentations were not consumed");
        require(!skipped.consume(), "skipped presentation was consumed more than once");

        skipped.submit();
        skipped.clear();
        require(!skipped.consume(), "successful acquisition did not clear a stale skipped frame");
    }

    private static void assertUpdatePlan(long offset, long size, int expectedChunks) {
        List<UpdateChunk> chunks = new ArrayList<>();
        VulkanBufferUpdates.forEachChunk(offset, size,
                (chunkOffset, chunkSize) -> chunks.add(new UpdateChunk(chunkOffset, chunkSize)));
        require(chunks.size() == expectedChunks, "unexpected Vulkan update chunk count");
        long covered = 0L;
        for (UpdateChunk chunk : chunks) {
            require(chunk.offset() == offset + covered, "Vulkan update chunks are not contiguous");
            require((chunk.offset() & 3L) == 0L, "Vulkan update chunk offset is unaligned");
            require((chunk.size() & 3) == 0, "Vulkan update chunk size is unaligned");
            require(chunk.size() <= VulkanBufferUpdates.MAX_UPDATE_BYTES,
                    "Vulkan update chunk exceeds vkCmdUpdateBuffer maximum");
            covered += chunk.size();
        }
        require(covered == size, "Vulkan update chunks did not cover the exact range");
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

    private record UpdateChunk(long offset, int size) {}
}
