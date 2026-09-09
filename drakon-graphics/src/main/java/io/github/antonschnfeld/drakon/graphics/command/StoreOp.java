package io.github.antonschnfeld.drakon.graphics.command;

/** Describes whether an attachment's produced contents must survive pass end. */
public enum StoreOp {
    /** Preserve the attachment contents for later use. */
    STORE,
    /** The attachment contents are not needed after the pass and may be discarded. */
    DONT_CARE
}
