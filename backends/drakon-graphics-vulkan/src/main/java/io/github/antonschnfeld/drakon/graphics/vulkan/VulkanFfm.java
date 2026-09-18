package io.github.antonschnfeld.drakon.graphics.vulkan;

import org.lwjgl.PointerBuffer;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.LongFunction;

/** Package-private FFM/LWJGL interop for operation-local Vulkan scratch memory. */
final class VulkanFfm {
    private VulkanFfm() {}

    static MemorySegment storage(Arena arena, long size, long alignment) {
        return arena.allocate(size, alignment);
    }

    static <T> T struct(Arena arena, int size, int alignment, LongFunction<T> wrapper) {
        Objects.requireNonNull(arena, "arena");
        Objects.requireNonNull(wrapper, "wrapper");
        return wrapper.apply(storage(arena, size, alignment).address());
    }

    static <T> T structBuffer(
            Arena arena, int size, int alignment, int count, BiFunction<Long, Integer, T> wrapper) {
        Objects.requireNonNull(arena, "arena");
        Objects.requireNonNull(wrapper, "wrapper");
        if (count < 0) throw new IllegalArgumentException("count must not be negative");
        long bytes = Math.multiplyExact((long) size, count);
        return wrapper.apply(storage(arena, bytes, alignment).address(), count);
    }

    static IntBuffer ints(Arena arena, int count) {
        return arena.allocate(ValueLayout.JAVA_INT, count).asByteBuffer()
                .order(ByteOrder.nativeOrder()).asIntBuffer();
    }

    static IntBuffer intValues(Arena arena, int... values) {
        IntBuffer buffer = ints(arena, values.length);
        buffer.put(values).flip();
        return buffer;
    }

    static LongBuffer longs(Arena arena, int count) {
        return arena.allocate(ValueLayout.JAVA_LONG, count).asByteBuffer()
                .order(ByteOrder.nativeOrder()).asLongBuffer();
    }

    static LongBuffer longs(Arena arena, long... values) {
        LongBuffer buffer = longs(arena, values.length);
        buffer.put(values).flip();
        return buffer;
    }

    static FloatBuffer floats(Arena arena, float... values) {
        FloatBuffer buffer = arena.allocate(ValueLayout.JAVA_FLOAT, values.length).asByteBuffer()
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        buffer.put(values).flip();
        return buffer;
    }

    static PointerBuffer pointers(Arena arena, int count) {
        MemorySegment storage = arena.allocate(ValueLayout.ADDRESS, count);
        return PointerBuffer.create(storage.address(), count);
    }

    static PointerBuffer pointers(Arena arena, long... values) {
        PointerBuffer buffer = pointers(arena, values.length);
        buffer.put(values).flip();
        return buffer;
    }

    static PointerBuffer pointers(Arena arena, ByteBuffer... values) {
        PointerBuffer buffer = pointers(arena, values.length);
        for (ByteBuffer value : values) buffer.put(value);
        return buffer.flip();
    }

    static ByteBuffer utf8(Arena arena, String value) {
        return arena.allocateFrom(Objects.requireNonNull(value, "value"))
                .asByteBuffer().order(ByteOrder.nativeOrder());
    }

    static MemorySegment nativeData(Arena arena, MemorySegment source, long alignment) {
        Objects.requireNonNull(arena, "arena");
        Objects.requireNonNull(source, "source");
        if (source.isNative() && source.address() % alignment == 0L) return source;
        MemorySegment storage = arena.allocate(source.byteSize(), alignment);
        storage.copyFrom(source);
        return storage;
    }

    @SuppressWarnings("restricted") // Bounds a Vulkan-owned address; this method never acquires ownership.
    static void copyToBorrowed(MemorySegment source, long address, long byteCount) {
        Objects.requireNonNull(source, "source");
        if (source.byteSize() != byteCount) {
            throw new IllegalArgumentException("source range does not match destination range");
        }
        // Borrowed from vkMapMemory; Vulkan owns the allocation and vkUnmapMemory ends its lifetime.
        MemorySegment mapped = MemorySegment.ofAddress(address).reinterpret(byteCount);
        mapped.copyFrom(source);
    }
}
