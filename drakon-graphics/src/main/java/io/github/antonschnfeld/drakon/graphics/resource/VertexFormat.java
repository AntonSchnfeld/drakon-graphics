package io.github.antonschnfeld.drakon.graphics.resource;

/** Portable vertex-attribute formats currently exposed by the API. */
public enum VertexFormat {
    /** Two 32-bit floating-point components. */
    FLOAT2(2 * Float.BYTES),
    /** Three 32-bit floating-point components. */
    FLOAT3(3 * Float.BYTES),
    /** Four 32-bit floating-point components. */
    FLOAT4(4 * Float.BYTES);

    private final int bytes;

    VertexFormat(int bytes) {
        this.bytes = bytes;
    }

    /**
     * Returns the packed byte width of this attribute format.
     *
     * @return size in bytes
     */
    public int bytes() {
        return bytes;
    }
}
