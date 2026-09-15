package io.github.antonschnfeld.drakon.graphics.opengl;

import io.github.antonschnfeld.drakon.graphics.resource.Binding;
import io.github.antonschnfeld.drakon.graphics.resource.BindingLayout;
import io.github.antonschnfeld.drakon.graphics.resource.BindingType;
import io.github.antonschnfeld.drakon.graphics.resource.BufferBinding;
import io.github.antonschnfeld.drakon.graphics.resource.IndexType;
import io.github.antonschnfeld.drakon.graphics.resource.ResourceState;
import io.github.antonschnfeld.drakon.graphics.resource.ScissorRect;
import io.github.antonschnfeld.drakon.graphics.resource.ShaderStage;
import io.github.antonschnfeld.drakon.graphics.resource.TextureBinding;
import io.github.antonschnfeld.drakon.graphics.resource.VertexFormat;
import io.github.antonschnfeld.drakon.graphics.resource.VertexInputRate;
import io.github.antonschnfeld.drakon.graphics.resource.VertexLayout;

import java.util.IdentityHashMap;
import java.util.List;

/** Hardware-free checks for portable validation arithmetic and OpenGL binding plans. */
public final class OpenGLBackendLogicChecks {
    private OpenGLBackendLogicChecks() {}

    /** Runs the backend validation checks. */
    public static void main(String[] args) {
        scissorClippingIsSafe();
        uniformAlignmentIsChecked();
        drawRangesAreChecked();
        inactiveBindingsKeepLogicalSlots();
        stateChangesRemapSharedBindings();
        transitionFromValidationFollowsConfig();
        System.out.println("OpenGL backend logic checks passed.");
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
        OpenGLValidation.validateUniformOffset(0, 256);
        OpenGLValidation.validateUniformOffset(512, 256);
        expect(IllegalArgumentException.class, () -> OpenGLValidation.validateUniformOffset(4, 256));
    }

    private static void drawRangesAreChecked() {
        VertexLayout layout = VertexLayout.builder()
                .binding(0, 16, VertexInputRate.PER_VERTEX)
                .attribute(0, 0, VertexFormat.FLOAT2, 0)
                .attribute(1, 0, VertexFormat.FLOAT2, 8)
                .build();
        OpenGLValidation.validateVertexRange(
                64, 0, layout.bindings().get(0), layout.attributes(), false, 4, 1, 0, 0);
        expect(IllegalArgumentException.class, () -> OpenGLValidation.validateVertexRange(
                64, 0, layout.bindings().get(0), layout.attributes(), false, 4, 1, 1, 0));
        expect(IllegalArgumentException.class, () -> OpenGLValidation.validateVertexRange(
                Long.MAX_VALUE, Long.MAX_VALUE - 4, layout.bindings().get(0),
                layout.attributes(), false, 2, 1, 0, 0));

        VertexLayout instances = VertexLayout.builder()
                .binding(1, 16, VertexInputRate.PER_INSTANCE)
                .attribute(2, 1, VertexFormat.FLOAT4, 0)
                .build();
        OpenGLValidation.validateVertexRange(
                80, 0, instances.bindings().get(0), instances.attributes(), true, 1, 2, 3, 3);
        expect(IllegalArgumentException.class, () -> OpenGLValidation.validateVertexRange(
                79, 0, instances.bindings().get(0), instances.attributes(), true, 1, 2, 3, 3));

        OpenGLValidation.validateIndexRange(12, 0, IndexType.UINT16, 0, 6);
        expect(IllegalArgumentException.class,
                () -> OpenGLValidation.validateIndexRange(12, 0, IndexType.UINT16, 6, 1));
        expect(IllegalArgumentException.class,
                () -> OpenGLValidation.validateIndexRange(Long.MAX_VALUE, Long.MAX_VALUE - 1,
                        IndexType.UINT32, Integer.MAX_VALUE, 1));
    }

    private static void inactiveBindingsKeepLogicalSlots() {
        Binding<TextureBinding> binding = Binding.sampledTexture(
                "possiblyInactive", 0, ShaderStage.FRAGMENT);
        BindingLayout layout = BindingLayout.of(binding);
        IdentityHashMap<Binding<?>, Integer> slots = new IdentityHashMap<>();
        slots.put(binding, 0);
        OpenGLBindingPlan plan = new OpenGLBindingPlan(List.of(layout), slots);
        require(plan.slot(binding) == 0, "inactive binding lost its logical OpenGL slot");
    }

    private static void stateChangesRemapSharedBindings() {
        Binding<TextureBinding> aTexture = Binding.sampledTexture("aTexture", 0, ShaderStage.FRAGMENT);
        Binding<BufferBinding> aUniform = Binding.uniformBuffer("aUniform", 1, ShaderStage.VERTEX);
        Binding<TextureBinding> bTexture0 = Binding.sampledTexture("bTexture0", 0, ShaderStage.FRAGMENT);
        Binding<TextureBinding> bTexture1 = Binding.sampledTexture("bTexture1", 1, ShaderStage.FRAGMENT);
        Binding<BufferBinding> bUniform0 = Binding.uniformBuffer("bUniform0", 2, ShaderStage.VERTEX);
        Binding<BufferBinding> bUniform1 = Binding.uniformBuffer("bUniform1", 3, ShaderStage.VERTEX);
        Binding<TextureBinding> sharedTexture = Binding.sampledTexture("sharedTexture", 0, ShaderStage.FRAGMENT);
        Binding<BufferBinding> sharedUniform = Binding.uniformBuffer("sharedUniform", 1, ShaderStage.VERTEX);
        BindingLayout layoutA = BindingLayout.of(aTexture, aUniform);
        BindingLayout layoutB = BindingLayout.of(bTexture0, bTexture1, bUniform0, bUniform1);
        BindingLayout shared = BindingLayout.of(sharedTexture, sharedUniform);

        OpenGLBindingPlan planA = plan(List.of(layoutA, shared));
        OpenGLBindingPlan planB = plan(List.of(layoutB, shared));
        require(nativeSlot(planA, 1, sharedTexture) == 1, "state A sampled slot is wrong");
        require(nativeSlot(planB, 1, sharedTexture) == 2, "state B sampled slot was not remapped");
        require(nativeSlot(planA, 1, sharedUniform) == 1, "state A uniform slot is wrong");
        require(nativeSlot(planB, 1, sharedUniform) == 2, "state B uniform slot was not remapped");
    }

    private static OpenGLBindingPlan plan(List<BindingLayout> layouts) {
        IdentityHashMap<Binding<?>, Integer> slots = new IdentityHashMap<>();
        java.util.EnumMap<BindingType, Integer> next = new java.util.EnumMap<>(BindingType.class);
        for (BindingType type : BindingType.values()) next.put(type, 0);
        for (BindingLayout layout : layouts) {
            for (Binding<?> binding : layout.bindings()) {
                int slot = next.get(binding.type());
                slots.put(binding, slot);
                next.put(binding.type(), slot + 1);
            }
        }
        return new OpenGLBindingPlan(layouts, slots);
    }

    private static int nativeSlot(OpenGLBindingPlan plan, int group, Binding<?> binding) {
        return plan.nativeBindings(group).stream()
                .filter(candidate -> candidate.binding() == binding)
                .findFirst()
                .orElseThrow()
                .slot();
    }

    private static void transitionFromValidationFollowsConfig() {
        OpenGLValidation.validateTransitionFrom(
                false, ResourceState.UNDEFINED, ResourceState.VERTEX_READ, "buffer");
        expect(IllegalStateException.class, () -> OpenGLValidation.validateTransitionFrom(
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
        OpenGLValidation.ClippedScissor expected =
                new OpenGLValidation.ClippedScissor(x, y, width, height);
        OpenGLValidation.ClippedScissor actual =
                OpenGLValidation.clipScissor(input, targetWidth, targetHeight);
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
}
