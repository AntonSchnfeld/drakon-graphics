package io.github.antonschnfeld.drakon.graphics.probe;

import io.github.antonschnfeld.drakon.graphics.command.CommandList;
import java.util.List;

final class ProbeCommandList implements CommandList {
    private final ProbeGraphicsDevice owner;
    private List<String> operations;
    private Status status = Status.READY;

    ProbeCommandList(ProbeGraphicsDevice owner, List<String> operations) {
        this.owner = owner;
        this.operations = operations;
    }

    ProbeGraphicsDevice owner() { return owner; }
    List<String> operations() { return operations; }
    void beginSubmission() {
        if (status != Status.READY) throw new IllegalStateException("command list is single-submit");
        status = Status.SUBMITTING;
    }
    void markSubmitted() {
        if (status != Status.SUBMITTING) throw new IllegalStateException("command list is not submitting");
        status = Status.SUBMITTED;
        operations = List.of();
    }
    void markFailed() {
        if (status == Status.SUBMITTING) {
            status = Status.FAILED;
            operations = List.of();
        }
    }
    boolean terminal() { return status == Status.SUBMITTED || status == Status.FAILED || status == Status.CLOSED; }
    @Override public void close() {
        if (status == Status.READY) {
            status = Status.CLOSED;
            operations = List.of();
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
