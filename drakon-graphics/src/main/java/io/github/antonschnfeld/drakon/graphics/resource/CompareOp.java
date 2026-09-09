package io.github.antonschnfeld.drakon.graphics.resource;

/** Comparison operation used by depth testing. */
public enum CompareOp {
    /** Comparison never passes. */
    NEVER,
    /** Incoming value must be less than the stored value. */
    LESS,
    /** Incoming value must be less than or equal to the stored value. */
    LESS_OR_EQUAL,
    /** Incoming value must equal the stored value. */
    EQUAL,
    /** Incoming value must be greater than or equal to the stored value. */
    GREATER_OR_EQUAL,
    /** Incoming value must be greater than the stored value. */
    GREATER,
    /** Comparison always passes. */
    ALWAYS
}
