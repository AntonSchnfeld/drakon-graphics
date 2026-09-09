package io.github.antonschnfeld.drakon.graphics.opengl;

import io.github.antonschnfeld.drakon.graphics.command.CommandList;

import java.util.List;

final class OpenGLCommandList implements CommandList {
    final OpenGLDevice device;
    final List<OpenGLCommand> commands;
    private boolean submitted;

    OpenGLCommandList(OpenGLDevice device, List<OpenGLCommand> commands) {
        this.device = device;
        this.commands = List.copyOf(commands);
    }

    void markSubmitted() {
        if (submitted) {
            throw new IllegalStateException("command list is single-submit");
        }
        submitted = true;
    }
}
