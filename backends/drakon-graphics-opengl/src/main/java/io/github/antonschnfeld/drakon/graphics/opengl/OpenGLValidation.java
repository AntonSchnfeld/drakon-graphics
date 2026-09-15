package io.github.antonschnfeld.drakon.graphics.opengl;

import io.github.antonschnfeld.drakon.graphics.resource.IndexType;
import io.github.antonschnfeld.drakon.graphics.resource.ScissorRect;
import io.github.antonschnfeld.drakon.graphics.resource.VertexAttribute;
import io.github.antonschnfeld.drakon.graphics.resource.VertexBinding;
import io.github.antonschnfeld.drakon.graphics.resource.VertexInputRate;

import java.util.List;

/** Checked portable arithmetic used before issuing OpenGL draw and scissor calls. */
final class OpenGLValidation {
    private OpenGLValidation() {}

    static ClippedScissor clipScissor(ScissorRect requested, int targetWidth, int targetHeight) {
        long left = clamp(requested.x(), 0, targetWidth);
        long top = clamp(requested.y(), 0, targetHeight);
        long right = clamp((long) requested.x() + requested.width(), 0, targetWidth);
        long bottom = clamp((long) requested.y() + requested.height(), 0, targetHeight);
        return new ClippedScissor(
                (int) left,
                (int) top,
                (int) Math.max(0L, right - left),
                (int) Math.max(0L, bottom - top));
    }

    static void validateUniformOffset(long offset, long alignment) {
        if (alignment <= 0) throw new IllegalArgumentException("uniform-buffer alignment must be positive");
        if (offset < 0) throw new IllegalArgumentException("uniform-buffer offset must be non-negative");
        if (offset % alignment != 0) {
            throw new IllegalArgumentException(
                    "uniform-buffer offset " + offset + " is not aligned to " + alignment + " bytes");
        }
    }

    static void validateVertexRange(
            long bufferSize,
            long bindingOffset,
            VertexBinding binding,
            List<VertexAttribute> attributes,
            boolean indexed,
            int vertexCount,
            int instanceCount,
            int firstVertex,
            int firstInstance) {
        long attributeEnd = attributes.stream()
                .filter(attribute -> attribute.binding() == binding.binding())
                .mapToLong(attribute -> (long) attribute.offset() + attribute.format().bytes())
                .max()
                .orElse(0L);
        if (attributeEnd == 0L) return;

        long firstRecord;
        long recordCount;
        if (binding.inputRate() == VertexInputRate.PER_INSTANCE) {
            firstRecord = firstInstance;
            recordCount = instanceCount;
        } else if (!indexed) {
            firstRecord = firstVertex;
            recordCount = vertexCount;
        } else {
            // Index contents are intentionally opaque. Only the first structurally
            // addressable record can be checked for an indexed per-vertex binding.
            firstRecord = 0L;
            recordCount = 1L;
        }

        try {
            long lastRecord = Math.addExact(firstRecord, recordCount - 1L);
            long recordOffset = Math.multiplyExact(lastRecord, binding.stride());
            long end = Math.addExact(Math.addExact(bindingOffset, recordOffset), attributeEnd);
            if (end > bufferSize) {
                throw new IllegalArgumentException(
                        "draw exceeds vertex buffer range for binding " + binding.binding());
            }
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException(
                    "vertex buffer range overflows for binding " + binding.binding(), failure);
        }
    }

    static void validateIndexRange(
            long bufferSize, long bindingOffset, IndexType type, int firstIndex, int indexCount) {
        try {
            long endIndex = Math.addExact((long) firstIndex, indexCount);
            long bytes = Math.multiplyExact(endIndex, type.bytes());
            long end = Math.addExact(bindingOffset, bytes);
            if (end > bufferSize) throw new IllegalArgumentException("draw exceeds bound index-buffer range");
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("index-buffer range overflows", failure);
        }
    }

    private static long clamp(long value, long minimum, long maximum) {
        return Math.min(Math.max(value, minimum), maximum);
    }

    record ClippedScissor(int x, int y, int width, int height) {
        boolean empty() {
            return width == 0 || height == 0;
        }
    }
}
