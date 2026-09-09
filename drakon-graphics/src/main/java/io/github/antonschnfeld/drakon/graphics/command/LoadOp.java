package io.github.antonschnfeld.drakon.graphics.command;

/** Describes how an attachment's previous contents are treated at pass start. */
public enum LoadOp {
    /** Preserve and make the attachment's existing contents available. */
    LOAD,
    /** Replace the attachment contents with the configured clear value. */
    CLEAR,
    /** Previous contents are not needed and may be discarded. */
    DONT_CARE
}
