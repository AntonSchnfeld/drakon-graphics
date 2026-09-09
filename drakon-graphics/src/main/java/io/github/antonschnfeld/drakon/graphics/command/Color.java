package io.github.antonschnfeld.drakon.graphics.command;

/**
 * Linear RGBA color value used by graphics commands such as attachment clears.
 *
 * <p>Components are not clamped; floating-point render targets may legitimately
 * use values outside {@code [0,1]}.</p>
 *
 * @param r red component
 * @param g green component
 * @param b blue component
 * @param a alpha component
 */
public record Color(float r, float g, float b, float a) {
    /** Opaque black. */
    public static final Color BLACK = new Color(0, 0, 0, 1);
}
