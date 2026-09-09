package io.github.antonschnfeld.drakon.graphics.resource;

import io.github.antonschnfeld.drakon.graphics.backend.GraphicsDevice;

/**
 * Base contract for resources owned by a graphics device.
 *
 * <p>Resources are opaque Java objects; backend-native handles are intentionally
 * not part of this interface. A resource may only be used with the
 * {@link GraphicsDevice} that owns it.</p>
 *
 * <p>{@link #close()} releases the caller's ownership. A backend may defer the
 * actual native destruction until already-submitted GPU work no longer refers
 * to the resource. Using a resource after it has been closed is invalid.</p>
 */
public interface GpuResource extends AutoCloseable {
    /**
     * Releases this resource.
     *
     * <p>This operation is idempotent. Closing a parent-like resource such as a
     * render target or binding set does not close other resources referenced by
     * it unless that type explicitly states otherwise.</p>
     */
    @Override
    void close();
}
