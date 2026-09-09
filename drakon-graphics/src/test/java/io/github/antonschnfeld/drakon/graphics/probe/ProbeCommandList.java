package io.github.antonschnfeld.drakon.graphics.probe;

import io.github.antonschnfeld.drakon.graphics.command.CommandList;
import java.util.List;

final class ProbeCommandList implements CommandList {
    private final ProbeGraphicsDevice owner;
    private final List<String> operations;
    private boolean submitted;

    ProbeCommandList(ProbeGraphicsDevice owner, List<String> operations) {
        this.owner = owner;
        this.operations = operations;
    }

    ProbeGraphicsDevice owner() { return owner; }
    List<String> operations() { return operations; }
    boolean submitted() { return submitted; }
    void markSubmitted() { submitted = true; }
}
