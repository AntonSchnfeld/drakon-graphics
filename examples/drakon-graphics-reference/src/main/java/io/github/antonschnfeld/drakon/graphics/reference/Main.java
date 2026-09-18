package io.github.antonschnfeld.drakon.graphics.reference;

import java.util.Locale;

/** Entry point for the Drakon 0.1 reference application. */
public final class Main {
    private static final int INITIAL_WIDTH = 960;
    private static final int INITIAL_HEIGHT = 600;
    private static final int FINITE_FRAMES = 240;

    private Main() {}

    /**
     * Runs the reference scene with OpenGL or Vulkan.
     *
     * @param args {@code opengl} or {@code vulkan}, optionally followed by {@code finite}
     */
    public static void main(String[] args) {
        RunOptions options = RunOptions.parse(args);
        try (GraphicsSession graphics = GraphicsSession.open(
                        options.backend(), INITIAL_WIDTH, INITIAL_HEIGHT);
                ReferenceRenderer renderer = new ReferenceRenderer(
                        graphics.device(),
                        graphics.presentationTarget(),
                        ShaderResources.prepare(graphics.device()))) {
            run(graphics, renderer, options.finite());
        }
    }

    private static void run(
            GraphicsSession graphics,
            ReferenceRenderer renderer,
            boolean finite) {
        long startNanos = System.nanoTime();
        int renderedFrames = 0;

        while (!graphics.shouldClose() && (!finite || renderedFrames < FINITE_FRAMES)) {
            graphics.processEvents();

            // A minimized native window has no framebuffer. Keep the event loop alive,
            // but do not intentionally start a frame until the window is renderable.
            if (!graphics.hasRenderableFramebuffer()) {
                graphics.waitForEvents();
                continue;
            }

            renderer.resizeIfNeeded();
            double elapsedSeconds = (System.nanoTime() - startNanos) * 1.0e-9;
            renderer.render(elapsedSeconds);
            graphics.device().present(graphics.presentationTarget());
            renderedFrames++;
        }

        double totalSeconds = (System.nanoTime() - startNanos) * 1.0e-9;
        System.out.printf(
                Locale.ROOT,
                "%s reference application exited normally after %d frames in %.3f s.%n",
                graphics.backend().displayName(),
                renderedFrames,
                totalSeconds);
    }

    enum Backend {
        OPENGL("OpenGL"),
        VULKAN("Vulkan");

        private final String displayName;

        Backend(String displayName) {
            this.displayName = displayName;
        }

        String displayName() {
            return displayName;
        }

        static Backend parse(String value) {
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "opengl" -> OPENGL;
                case "vulkan" -> VULKAN;
                default -> throw new IllegalArgumentException("expected opengl or vulkan");
            };
        }
    }

    private record RunOptions(Backend backend, boolean finite) {
        static RunOptions parse(String[] args) {
            if (args.length > 2
                    || (args.length == 2 && !"finite".equalsIgnoreCase(args[1]))) {
                throw new IllegalArgumentException(
                        "expected opengl or vulkan, optionally followed by finite");
            }
            Backend backend = Backend.parse(args.length == 0 ? "opengl" : args[0]);
            return new RunOptions(backend, args.length == 2);
        }
    }
}
