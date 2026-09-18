package io.github.antonschnfeld.drakon.graphics.reference;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/** Compact generated geometry and texture data used by the example scene. */
final class SceneGeometry {
    static final int CUBE_INDEX_COUNT = 36;
    static final int PANEL_INDEX_COUNT = 6;
    static final int TEXTURE_SIZE = 8;

    private SceneGeometry() {}

    static MemorySegment checkerTexture(Arena arena) {
        MemorySegment pixels = arena.allocate(TEXTURE_SIZE * TEXTURE_SIZE * 4);
        int[][] palette = {
                {239, 92, 68},
                {72, 202, 145},
                {72, 126, 236},
                {244, 194, 72}
        };
        long byteIndex = 0;
        for (int y = 0; y < TEXTURE_SIZE; y++) {
            for (int x = 0; x < TEXTURE_SIZE; x++) {
                int[] color = palette[((y / 2) * 2 + x / 2) % palette.length];
                float shade = ((x + y) & 1) == 0 ? 1.0f : 0.72f;
                pixels.setAtIndex(ValueLayout.JAVA_BYTE, byteIndex++, (byte) (color[0] * shade));
                pixels.setAtIndex(ValueLayout.JAVA_BYTE, byteIndex++, (byte) (color[1] * shade));
                pixels.setAtIndex(ValueLayout.JAVA_BYTE, byteIndex++, (byte) (color[2] * shade));
                pixels.setAtIndex(ValueLayout.JAVA_BYTE, byteIndex++, (byte) 255);
            }
        }
        return pixels;
    }

    static MemorySegment cubeVertices(Arena arena) {
        MemorySegment vertices = arena.allocate(24 * 5 * Float.BYTES, Float.BYTES);
        long floatIndex = putFace(vertices, 0,
                -0.7f, -0.7f, 0.7f, 0.7f, -0.7f, 0.7f,
                0.7f, 0.7f, 0.7f, -0.7f, 0.7f, 0.7f);
        floatIndex = putFace(vertices, floatIndex,
                0.7f, -0.7f, -0.7f, -0.7f, -0.7f, -0.7f,
                -0.7f, 0.7f, -0.7f, 0.7f, 0.7f, -0.7f);
        floatIndex = putFace(vertices, floatIndex,
                0.7f, -0.7f, 0.7f, 0.7f, -0.7f, -0.7f,
                0.7f, 0.7f, -0.7f, 0.7f, 0.7f, 0.7f);
        floatIndex = putFace(vertices, floatIndex,
                -0.7f, -0.7f, -0.7f, -0.7f, -0.7f, 0.7f,
                -0.7f, 0.7f, 0.7f, -0.7f, 0.7f, -0.7f);
        floatIndex = putFace(vertices, floatIndex,
                -0.7f, 0.7f, 0.7f, 0.7f, 0.7f, 0.7f,
                0.7f, 0.7f, -0.7f, -0.7f, 0.7f, -0.7f);
        putFace(vertices, floatIndex,
                -0.7f, -0.7f, -0.7f, 0.7f, -0.7f, -0.7f,
                0.7f, -0.7f, 0.7f, -0.7f, -0.7f, 0.7f);
        return vertices;
    }

    static MemorySegment cubeIndices(Arena arena) {
        MemorySegment indices = arena.allocate(CUBE_INDEX_COUNT * Short.BYTES, Short.BYTES);
        long shortIndex = 0;
        for (int face = 0; face < 6; face++) {
            int start = face * 4;
            indices.setAtIndex(ValueLayout.JAVA_SHORT, shortIndex++, (short) start);
            indices.setAtIndex(ValueLayout.JAVA_SHORT, shortIndex++, (short) (start + 1));
            indices.setAtIndex(ValueLayout.JAVA_SHORT, shortIndex++, (short) (start + 2));
            indices.setAtIndex(ValueLayout.JAVA_SHORT, shortIndex++, (short) (start + 2));
            indices.setAtIndex(ValueLayout.JAVA_SHORT, shortIndex++, (short) (start + 3));
            indices.setAtIndex(ValueLayout.JAVA_SHORT, shortIndex++, (short) start);
        }
        return indices;
    }

    static MemorySegment panelVertices(Arena arena) {
        return floats(arena,
                -1, -1, 0, 0, 0,
                1, -1, 0, 1, 0,
                1, 1, 0, 1, 1,
                -1, 1, 0, 0, 1);
    }

    static MemorySegment panelIndices(Arena arena) {
        return shorts(arena, 0, 1, 2, 2, 3, 0);
    }

    static MemorySegment fullscreenVertices(Arena arena) {
        return floats(arena,
                -1, 1, 0, 0,
                -1, -1, 0, 1,
                1, -1, 1, 1,
                1, 1, 1, 0);
    }

    static MemorySegment fullscreenIndices(Arena arena) {
        return shorts(arena, 0, 1, 2, 2, 3, 0);
    }

    private static long putFace(MemorySegment vertices, long floatIndex, float... positions) {
        for (int vertex = 0; vertex < 4; vertex++) {
            int position = vertex * 3;
            vertices.setAtIndex(ValueLayout.JAVA_FLOAT, floatIndex++, positions[position]);
            vertices.setAtIndex(ValueLayout.JAVA_FLOAT, floatIndex++, positions[position + 1]);
            vertices.setAtIndex(ValueLayout.JAVA_FLOAT, floatIndex++, positions[position + 2]);
            vertices.setAtIndex(
                    ValueLayout.JAVA_FLOAT, floatIndex++, vertex == 1 || vertex == 2 ? 1 : 0);
            vertices.setAtIndex(ValueLayout.JAVA_FLOAT, floatIndex++, vertex >= 2 ? 1 : 0);
        }
        return floatIndex;
    }

    private static MemorySegment floats(Arena arena, float... values) {
        MemorySegment data = arena.allocate(values.length * (long) Float.BYTES, Float.BYTES);
        for (int index = 0; index < values.length; index++) {
            data.setAtIndex(ValueLayout.JAVA_FLOAT, index, values[index]);
        }
        return data;
    }

    private static MemorySegment shorts(Arena arena, int... values) {
        MemorySegment data = arena.allocate(values.length * (long) Short.BYTES, Short.BYTES);
        for (int index = 0; index < values.length; index++) {
            data.setAtIndex(ValueLayout.JAVA_SHORT, index, (short) values[index]);
        }
        return data;
    }
}
