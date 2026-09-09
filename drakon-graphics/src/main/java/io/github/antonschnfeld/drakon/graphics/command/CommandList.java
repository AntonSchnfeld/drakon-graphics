package io.github.antonschnfeld.drakon.graphics.command;

import io.github.antonschnfeld.drakon.graphics.backend.GraphicsDevice;

/**
 * Finished, immutable unit of GPU work produced by a {@link CommandEncoder}.
 *
 * <p>A command list is associated with the device that created its encoder and
 * is single-submit in the current API. Applications normally pass it directly
 * to {@link GraphicsDevice#submit(CommandList)}.</p>
 */
public interface CommandList {}
