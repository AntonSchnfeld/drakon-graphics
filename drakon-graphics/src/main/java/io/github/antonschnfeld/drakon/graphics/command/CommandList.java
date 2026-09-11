package io.github.antonschnfeld.drakon.graphics.command;

import io.github.antonschnfeld.drakon.graphics.backend.GraphicsDevice;

/**
 * Finished, immutable unit of GPU work produced by a {@link CommandEncoder}.
 *
 * <p>A command list is associated with the device that created its encoder and
 * is single-submit. It owns its backend command resources until successful
 * submission transfers them to the device. Closing an unsubmitted list releases
 * those resources; closing a successfully submitted list is harmless and does
 * not cancel or invalidate GPU work.</p>
 */
public interface CommandList extends AutoCloseable {
    /**
     * Releases this list's backend command resources if it has not been
     * submitted successfully.
     *
     * <p>This method is idempotent. A closed unsubmitted list cannot later be
     * submitted. Once submission succeeds, the device owns any in-flight native
     * work and this method has no effect on it.</p>
     */
    @Override
    void close();
}
