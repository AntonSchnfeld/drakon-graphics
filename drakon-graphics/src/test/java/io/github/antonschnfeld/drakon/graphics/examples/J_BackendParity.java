package io.github.antonschnfeld.drakon.graphics.examples;

import io.github.antonschnfeld.drakon.graphics.backend.GraphicsBackends;

public final class J_BackendParity {
    public static void run() {
        // No direct dependency on either backend implementation class.
        if (GraphicsBackends.find("opengl").isEmpty()) throw new AssertionError("OpenGL provider not discovered");
        if (GraphicsBackends.find("vulkan").isEmpty()) throw new AssertionError("Vulkan provider not discovered");

        A_TexturedMesh.run("opengl");
        A_TexturedMesh.run("vulkan");
    }
}
