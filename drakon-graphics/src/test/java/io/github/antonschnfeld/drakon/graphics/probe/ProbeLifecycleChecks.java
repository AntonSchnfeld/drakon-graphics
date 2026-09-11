package io.github.antonschnfeld.drakon.graphics.probe;

import io.github.antonschnfeld.drakon.graphics.backend.GraphicsDevice;
import io.github.antonschnfeld.drakon.graphics.pipeline.RenderPipeline;
import io.github.antonschnfeld.drakon.graphics.render.Renderer;
import io.github.antonschnfeld.drakon.graphics.shader.OpenGLShaderTarget;

/** Test-only checks for renderer cleanup against the instrumented probe backend. */
public final class ProbeLifecycleChecks {
    private ProbeLifecycleChecks() {}

    /** Verifies encoder/list cleanup when recording or submission fails. */
    public static void run() {
        try (GraphicsDevice device = new ProbeGraphicsDevice(
                "lifecycle", true, new OpenGLShaderTarget(4, 3, 430))) {
            ProbeGraphicsDevice probe = (ProbeGraphicsDevice) device;
            Renderer renderer = new Renderer(device);

            expect(TestFailure.class, () -> renderer.execute(
                    RenderPipeline.of(commands -> { throw new TestFailure(); })));
            require(probe.lastEncoder().terminal(), "Renderer leaked an encoder after a pass failure");

            expect(IllegalStateException.class, () -> renderer.execute(RenderPipeline.of()));
            require(probe.lastEncoder().terminal(), "Renderer leaked an encoder after submit failure");
            require(probe.lastCommandList().terminal(), "Renderer leaked a command list after submit failure");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void expect(Class<? extends Throwable> type, Runnable action) {
        try {
            action.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError("expected " + type.getSimpleName() + " but got " + failure, failure);
        }
        throw new AssertionError("expected " + type.getSimpleName());
    }

    private static final class TestFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
