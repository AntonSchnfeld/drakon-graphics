package io.github.antonschnfeld.drakon.graphics.probe;

import io.github.antonschnfeld.drakon.graphics.backend.*;
import io.github.antonschnfeld.drakon.graphics.backend.GraphicsBackend;
import io.github.antonschnfeld.drakon.graphics.backend.GraphicsDevice;
import io.github.antonschnfeld.drakon.graphics.backend.GraphicsDeviceConfig;
import io.github.antonschnfeld.drakon.graphics.shader.ShaderTarget;

abstract class AbstractProbeBackend implements GraphicsBackend {
    private final String id;
    private final String label;
    private final ShaderTarget shaderTarget;

    AbstractProbeBackend(String id, String label, ShaderTarget shaderTarget) {
        this.id = id;
        this.label = label;
        this.shaderTarget = shaderTarget;
    }

    @Override public String id() { return id; }
    @Override public boolean isSupported() { return true; }
    @Override public GraphicsDevice createDevice(GraphicsDeviceConfig config) {
        return new ProbeGraphicsDevice(label, config.validation(), shaderTarget);
    }
}
