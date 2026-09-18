package io.github.antonschnfeld.drakon.graphics.opengl;

import io.github.antonschnfeld.drakon.graphics.command.CommandList;

import java.util.List;

final class OpenGLCommandList implements CommandList {
    final OpenGLDevice device;
    List<OpenGLCommand> commands;
    private final OpenGLCommandMemory commandMemory;
    private Status status = Status.READY;

    OpenGLCommandList(
            OpenGLDevice device,
            List<OpenGLCommand> commands,
            OpenGLCommandMemory commandMemory) {
        this.device = device;
        this.commands = List.copyOf(commands);
        this.commandMemory = commandMemory;
    }

    synchronized void beginSubmission() {
        if (status != Status.READY) throw new IllegalStateException("command list is single-submit");
        status = Status.SUBMITTING;
    }

    synchronized void markSubmitted() {
        if (status != Status.SUBMITTING) throw new IllegalStateException("command list is not submitting");
        status = Status.SUBMITTED;
        commands = List.of();
        commandMemory.close();
    }

    synchronized void markFailed() {
        if (status == Status.SUBMITTING) {
            status = Status.FAILED;
            commands = List.of();
            commandMemory.close();
        }
    }

    @Override
    public synchronized void close() {
        if (status == Status.READY) {
            status = Status.CLOSED;
            commands = List.of();
            commandMemory.close();
        }
    }

    private enum Status {
        READY,
        SUBMITTING,
        SUBMITTED,
        FAILED,
        CLOSED
    }
}
