package io.github.antonschnfeld.drakon.graphics.opengl;

@FunctionalInterface
interface OpenGLCommand {
    void execute(OpenGLExecutionContext context);
}
