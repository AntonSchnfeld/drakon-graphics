package io.github.antonschnfeld.drakon.graphics.spike;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/** Sequential native-layout writer used to populate spike-owned segments. */
final class SegmentWriter {
    private final MemorySegment destination;
    private long offset;

    SegmentWriter(MemorySegment destination) {
        this.destination = destination;
    }

    SegmentWriter putFloat(float value) {
        destination.set(ValueLayout.JAVA_FLOAT, offset, value);
        offset += Float.BYTES;
        return this;
    }

    SegmentWriter putShort(short value) {
        destination.set(ValueLayout.JAVA_SHORT, offset, value);
        offset += Short.BYTES;
        return this;
    }

    SegmentWriter putByte(byte value) {
        destination.set(ValueLayout.JAVA_BYTE, offset, value);
        offset++;
        return this;
    }

    void rewind() {
        offset = 0;
    }

    MemorySegment writtenSegment() {
        return destination.asSlice(0, offset);
    }
}
