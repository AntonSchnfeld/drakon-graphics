package io.github.antonschnfeld.drakon.graphics.reference;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Compact generated geometry and texture data used by the example scene. */
final class SceneGeometry {
    static final int CUBE_INDEX_COUNT = 36;
    static final int PANEL_INDEX_COUNT = 6;
    static final int TEXTURE_SIZE = 8;

    private SceneGeometry() {}

    static ByteBuffer checkerTexture() {
        ByteBuffer pixels = ByteBuffer.allocate(TEXTURE_SIZE * TEXTURE_SIZE * 4);
        int[][] palette = {
                {239, 92, 68},
                {72, 202, 145},
                {72, 126, 236},
                {244, 194, 72}
        };
        for (int y = 0; y < TEXTURE_SIZE; y++) {
            for (int x = 0; x < TEXTURE_SIZE; x++) {
                int[] color = palette[((y / 2) * 2 + x / 2) % palette.length];
                float shade = ((x + y) & 1) == 0 ? 1.0f : 0.72f;
                pixels.put((byte) (color[0] * shade));
                pixels.put((byte) (color[1] * shade));
                pixels.put((byte) (color[2] * shade));
                pixels.put((byte) 255);
            }
        }
        return pixels.flip();
    }

    static ByteBuffer cubeVertices() {
        ByteBuffer vertices = allocate(24 * 5 * Float.BYTES);
        putFace(vertices,
                -0.7f, -0.7f, 0.7f, 0.7f, -0.7f, 0.7f,
                0.7f, 0.7f, 0.7f, -0.7f, 0.7f, 0.7f);
        putFace(vertices,
                0.7f, -0.7f, -0.7f, -0.7f, -0.7f, -0.7f,
                -0.7f, 0.7f, -0.7f, 0.7f, 0.7f, -0.7f);
        putFace(vertices,
                0.7f, -0.7f, 0.7f, 0.7f, -0.7f, -0.7f,
                0.7f, 0.7f, -0.7f, 0.7f, 0.7f, 0.7f);
        putFace(vertices,
                -0.7f, -0.7f, -0.7f, -0.7f, -0.7f, 0.7f,
                -0.7f, 0.7f, 0.7f, -0.7f, 0.7f, -0.7f);
        putFace(vertices,
                -0.7f, 0.7f, 0.7f, 0.7f, 0.7f, 0.7f,
                0.7f, 0.7f, -0.7f, -0.7f, 0.7f, -0.7f);
        putFace(vertices,
                -0.7f, -0.7f, -0.7f, 0.7f, -0.7f, -0.7f,
                0.7f, -0.7f, 0.7f, -0.7f, -0.7f, 0.7f);
        return vertices.flip();
    }

    static ByteBuffer cubeIndices() {
        ByteBuffer indices = allocate(CUBE_INDEX_COUNT * Short.BYTES);
        for (int face = 0; face < 6; face++) {
            int start = face * 4;
            indices.putShort((short) start);
            indices.putShort((short) (start + 1));
            indices.putShort((short) (start + 2));
            indices.putShort((short) (start + 2));
            indices.putShort((short) (start + 3));
            indices.putShort((short) start);
        }
        return indices.flip();
    }

    static ByteBuffer panelVertices() {
        return allocate(4 * 5 * Float.BYTES)
                .putFloat(-1).putFloat(-1).putFloat(0).putFloat(0).putFloat(0)
                .putFloat(1).putFloat(-1).putFloat(0).putFloat(1).putFloat(0)
                .putFloat(1).putFloat(1).putFloat(0).putFloat(1).putFloat(1)
                .putFloat(-1).putFloat(1).putFloat(0).putFloat(0).putFloat(1)
                .flip();
    }

    static ByteBuffer panelIndices() {
        return allocate(PANEL_INDEX_COUNT * Short.BYTES)
                .putShort((short) 0).putShort((short) 1).putShort((short) 2)
                .putShort((short) 2).putShort((short) 3).putShort((short) 0)
                .flip();
    }

    static ByteBuffer fullscreenVertices() {
        return allocate(4 * 4 * Float.BYTES)
                .putFloat(-1).putFloat(1).putFloat(0).putFloat(0)
                .putFloat(-1).putFloat(-1).putFloat(0).putFloat(1)
                .putFloat(1).putFloat(-1).putFloat(1).putFloat(1)
                .putFloat(1).putFloat(1).putFloat(1).putFloat(0)
                .flip();
    }

    static ByteBuffer fullscreenIndices() {
        return allocate(6 * Short.BYTES)
                .putShort((short) 0).putShort((short) 1).putShort((short) 2)
                .putShort((short) 2).putShort((short) 3).putShort((short) 0)
                .flip();
    }

    private static void putFace(ByteBuffer vertices, float... positions) {
        for (int vertex = 0; vertex < 4; vertex++) {
            int position = vertex * 3;
            vertices.putFloat(positions[position]);
            vertices.putFloat(positions[position + 1]);
            vertices.putFloat(positions[position + 2]);
            vertices.putFloat(vertex == 1 || vertex == 2 ? 1 : 0);
            vertices.putFloat(vertex >= 2 ? 1 : 0);
        }
    }

    private static ByteBuffer allocate(int bytes) {
        return ByteBuffer.allocate(bytes).order(ByteOrder.nativeOrder());
    }
}
