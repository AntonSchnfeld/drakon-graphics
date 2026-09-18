package io.github.antonschnfeld.drakon.graphics.reference;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** JOML-based camera, model animation, and packed per-frame GPU data. */
final class ReferenceScene {
    static final int OBJECT_COUNT = 3;
    static final int INSTANCE_STRIDE = 20 * Float.BYTES;

    private static final Vector3f CAMERA_FOCUS = new Vector3f(0.15f, 0.0f, -0.2f);
    private static final Vector3f CAMERA_UP = new Vector3f(0.0f, 1.0f, 0.0f);

    private final Matrix4f transform = new Matrix4f();
    private final Vector3f cameraPosition = new Vector3f();
    private final ByteBuffer cameraData = allocate(16 * Float.BYTES);
    private final ByteBuffer objectData = allocate(OBJECT_COUNT * INSTANCE_STRIDE);
    private final ByteBuffer glassData = allocate(INSTANCE_STRIDE);

    void update(double elapsedSeconds, int width, int height) {
        float time = (float) elapsedSeconds;
        cameraPosition.set(
                0.72f * (float) Math.sin(time * 0.24f),
                1.30f + 0.14f * (float) Math.sin(time * 0.19f),
                6.65f + 0.30f * (float) Math.cos(time * 0.17f));

        transform.identity()
                .perspective((float) Math.toRadians(50.0), (float) width / height, 0.1f, 100.0f, true)
                .lookAt(cameraPosition, CAMERA_FOCUS, CAMERA_UP);
        cameraData.clear();
        putMatrix(cameraData, transform);
        cameraData.flip();

        objectData.clear();
        putObject(
                objectData,
                transform.identity()
                        .translation(-1.45f, -0.08f, 0.45f)
                        .rotateY(time * 0.58f)
                        .rotateX(time * 0.31f),
                1.00f, 0.46f, 0.28f, 1.0f);
        putObject(
                objectData,
                transform.identity()
                        .translation(0.05f, -0.18f, -0.85f)
                        .rotateY(0.45f - time * 0.37f)
                        .rotateX(0.18f),
                0.36f, 0.62f, 1.00f, 1.0f);
        putObject(
                objectData,
                transform.identity()
                        .translation(1.65f, 0.10f, -0.15f)
                        .rotateY(time * 0.73f)
                        .rotateX(-time * 0.27f)
                        .scale(0.82f),
                0.38f, 1.00f, 0.58f, 1.0f);
        objectData.flip();

        glassData.clear();
        putObject(
                glassData,
                transform.identity()
                        .translation(
                                -0.18f + 0.34f * (float) Math.sin(time * 0.55f),
                                0.30f + 0.16f * (float) Math.cos(time * 0.41f),
                                1.28f)
                        .rotateY(0.18f * (float) Math.sin(time * 0.48f))
                        .rotateZ(0.08f * (float) Math.cos(time * 0.37f))
                        .scale(1.55f, 0.92f, 1.0f),
                0.20f, 0.88f, 1.00f, 0.42f);
        glassData.flip();
    }

    ByteBuffer cameraData() {
        return cameraData;
    }

    ByteBuffer objectData() {
        return objectData;
    }

    ByteBuffer glassData() {
        return glassData;
    }

    private static ByteBuffer allocate(int bytes) {
        return ByteBuffer.allocate(bytes).order(ByteOrder.nativeOrder());
    }

    private static void putObject(
            ByteBuffer destination,
            Matrix4f model,
            float red,
            float green,
            float blue,
            float alpha) {
        putMatrix(destination, model);
        destination.putFloat(red).putFloat(green).putFloat(blue).putFloat(alpha);
    }

    private static void putMatrix(ByteBuffer destination, Matrix4f matrix) {
        destination
                .putFloat(matrix.m00()).putFloat(matrix.m01())
                .putFloat(matrix.m02()).putFloat(matrix.m03())
                .putFloat(matrix.m10()).putFloat(matrix.m11())
                .putFloat(matrix.m12()).putFloat(matrix.m13())
                .putFloat(matrix.m20()).putFloat(matrix.m21())
                .putFloat(matrix.m22()).putFloat(matrix.m23())
                .putFloat(matrix.m30()).putFloat(matrix.m31())
                .putFloat(matrix.m32()).putFloat(matrix.m33());
    }
}
