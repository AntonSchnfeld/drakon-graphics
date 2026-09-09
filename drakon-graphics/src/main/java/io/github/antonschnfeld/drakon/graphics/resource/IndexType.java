package io.github.antonschnfeld.drakon.graphics.resource;

/** Unsigned integer representation used by an index buffer. */
public enum IndexType {
    /** Unsigned 16-bit indices. */
    UINT16(Short.BYTES),
    /** Unsigned 32-bit indices. */
    UINT32(Integer.BYTES);

    private final int bytes;

    IndexType(int bytes) {
        this.bytes = bytes;
    }

    /**
     * Returns the byte width of one index.
     *
     * @return index width in bytes
     */
    public int bytes() {
        return bytes;
    }
}
