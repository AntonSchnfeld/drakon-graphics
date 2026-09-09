package io.github.antonschnfeld.drakon.graphics.probe;

import io.github.antonschnfeld.drakon.graphics.backend.GraphicsDevice;
import io.github.antonschnfeld.drakon.graphics.resource.RenderTarget;
import io.github.antonschnfeld.drakon.graphics.resource.TextureFormat;

import java.util.List;

/** Test-only factory for exercising the portable presentation contract. */
public final class ProbePresentationTargets {
    private ProbePresentationTargets() {}

    public static RenderTarget create(
            GraphicsDevice device,
            int width,
            int height,
            List<TextureFormat> colorFormats,
            TextureFormat depthFormat) {
        if (!(device instanceof ProbeGraphicsDevice probe)) {
            throw new IllegalArgumentException("not a probe device");
        }
        return probe.createPresentationTargetForTest(
                width, height, colorFormats, depthFormat);
    }
}
