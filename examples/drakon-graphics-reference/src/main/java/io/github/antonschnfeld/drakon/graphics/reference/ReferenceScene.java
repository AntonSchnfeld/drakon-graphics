package io.github.antonschnfeld.drakon.graphics.reference;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/** JOML-based camera, model animation, and packed per-frame GPU data. */
final class ReferenceScene implements AutoCloseable {
    static final int OBJECT_COUNT = 3;
    static final int INSTANCE_STRIDE = 20 * Float.BYTES;

    private static final Vector3f CAMERA_FOCUS = new Vector3f(0.15f, 0.0f, -0.2f);
    private static final Vector3f CAMERA_UP = new Vector3f(0.0f, 1.0f, 0.0f);

    private final Matrix4f transform = new Matrix4f();
    private final Vector3f cameraPosition = new Vector3f();
    private final Arena arena = Arena.ofConfined();
    private final MemorySegment cameraData = arena.allocate(16 * Float.BYTES, Float.BYTES);
    private final MemorySegment objectData = arena.allocate(
            OBJECT_COUNT * INSTANCE_STRIDE, Float.BYTES);
    private final MemorySegment glassData = arena.allocate(INSTANCE_STRIDE, Float.BYTES);

    void update(double elapsedSeconds, int width, int height) {
        float time = (float) elapsedSeconds;
        cameraPosition.set(
                0.72f * (float) Math.sin(time * 0.24f),
                1.30f + 0.14f * (float) Math.sin(time * 0.19f),
                6.65f + 0.30f * (float) Math.cos(time * 0.17f));

        transform.identity()
                .perspective((float) Math.toRadians(50.0), (float) width / height, 0.1f, 100.0f, true)
                .lookAt(cameraPosition, CAMERA_FOCUS, CAMERA_UP);
        putMatrix(cameraData, 0, transform);

        long objectOffset = putObject(
                objectData,
                0,
                transform.identity()
                        .translation(-1.45f, -0.08f, 0.45f)
                        .rotateY(time * 0.58f)
                        .rotateX(time * 0.31f),
                1.00f, 0.46f, 0.28f, 1.0f);
        objectOffset = putObject(
                objectData,
                objectOffset,
                transform.identity()
                        .translation(0.05f, -0.18f, -0.85f)
                        .rotateY(0.45f - time * 0.37f)
                        .rotateX(0.18f),
                0.36f, 0.62f, 1.00f, 1.0f);
        putObject(
                objectData,
                objectOffset,
                transform.identity()
                        .translation(1.65f, 0.10f, -0.15f)
                        .rotateY(time * 0.73f)
                        .rotateX(-time * 0.27f)
                        .scale(0.82f),
                0.38f, 1.00f, 0.58f, 1.0f);
        putObject(
                glassData,
                0,
                transform.identity()
                        .translation(
                                -0.18f + 0.34f * (float) Math.sin(time * 0.55f),
                                0.30f + 0.16f * (float) Math.cos(time * 0.41f),
                                1.28f)
                        .rotateY(0.18f * (float) Math.sin(time * 0.48f))
                        .rotateZ(0.08f * (float) Math.cos(time * 0.37f))
                        .scale(1.55f, 0.92f, 1.0f),
                0.20f, 0.88f, 1.00f, 0.42f);
    }

    MemorySegment cameraData() {
        return cameraData;
    }

    MemorySegment objectData() {
        return objectData;
    }

    MemorySegment glassData() {
        return glassData;
    }

    private static long putObject(
            MemorySegment destination,
            long byteOffset,
            Matrix4f model,
            float red,
            float green,
            float blue,
            float alpha) {
        long floatIndex = putMatrix(destination, byteOffset, model) / Float.BYTES;
        destination.setAtIndex(ValueLayout.JAVA_FLOAT, floatIndex++, red);
        destination.setAtIndex(ValueLayout.JAVA_FLOAT, floatIndex++, green);
        destination.setAtIndex(ValueLayout.JAVA_FLOAT, floatIndex++, blue);
        destination.setAtIndex(ValueLayout.JAVA_FLOAT, floatIndex++, alpha);
        return floatIndex * Float.BYTES;
    }

    private static long putMatrix(MemorySegment destination, long byteOffset, Matrix4f matrix) {
        long floatIndex = byteOffset / Float.BYTES;
        destination.setAtIndex(ValueLayout.JAVA_FLOAT, floatIndex++, matrix.m00());
        destination.setAtIndex(ValueLayout.JAVA_FLOAT, floatIndex++, matrix.m01());
        destination.setAtIndex(ValueLayout.JAVA_FLOAT, floatIndex++, matrix.m02());
        destination.setAtIndex(ValueLayout.JAVA_FLOAT, floatIndex++, matrix.m03());
        destination.setAtIndex(ValueLayout.JAVA_FLOAT, floatIndex++, matrix.m10());
        destination.setAtIndex(ValueLayout.JAVA_FLOAT, floatIndex++, matrix.m11());
        destination.setAtIndex(ValueLayout.JAVA_FLOAT, floatIndex++, matrix.m12());
        destination.setAtIndex(ValueLayout.JAVA_FLOAT, floatIndex++, matrix.m13());
        destination.setAtIndex(ValueLayout.JAVA_FLOAT, floatIndex++, matrix.m20());
        destination.setAtIndex(ValueLayout.JAVA_FLOAT, floatIndex++, matrix.m21());
        destination.setAtIndex(ValueLayout.JAVA_FLOAT, floatIndex++, matrix.m22());
        destination.setAtIndex(ValueLayout.JAVA_FLOAT, floatIndex++, matrix.m23());
        destination.setAtIndex(ValueLayout.JAVA_FLOAT, floatIndex++, matrix.m30());
        destination.setAtIndex(ValueLayout.JAVA_FLOAT, floatIndex++, matrix.m31());
        destination.setAtIndex(ValueLayout.JAVA_FLOAT, floatIndex++, matrix.m32());
        destination.setAtIndex(ValueLayout.JAVA_FLOAT, floatIndex++, matrix.m33());
        return floatIndex * Float.BYTES;
    }

    @Override
    public void close() {
        arena.close();
    }
}
