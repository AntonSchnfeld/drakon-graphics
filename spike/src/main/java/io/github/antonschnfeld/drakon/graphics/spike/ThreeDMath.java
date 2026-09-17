package io.github.antonschnfeld.drakon.graphics.spike;

import java.nio.ByteBuffer;

/** Minimal column-major matrix math for the real-backend 3D spike. */
final class ThreeDMath {
    private ThreeDMath() {}

    static float[] identity() {
        float[] result = new float[16];
        result[0] = 1;
        result[5] = 1;
        result[10] = 1;
        result[15] = 1;
        return result;
    }

    static float[] multiply(float[] left, float[] right) {
        requireMatrix(left, "left");
        requireMatrix(right, "right");
        float[] result = new float[16];
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                float value = 0;
                for (int index = 0; index < 4; index++) {
                    value += left[index * 4 + row] * right[column * 4 + index];
                }
                result[column * 4 + row] = value;
            }
        }
        return result;
    }

    static float[] translation(float x, float y, float z) {
        float[] result = identity();
        result[12] = x;
        result[13] = y;
        result[14] = z;
        return result;
    }

    static float[] rotationX(float radians) {
        float[] result = identity();
        float cosine = (float) Math.cos(radians);
        float sine = (float) Math.sin(radians);
        result[5] = cosine;
        result[6] = sine;
        result[9] = -sine;
        result[10] = cosine;
        return result;
    }

    static float[] rotationY(float radians) {
        float[] result = identity();
        float cosine = (float) Math.cos(radians);
        float sine = (float) Math.sin(radians);
        result[0] = cosine;
        result[2] = -sine;
        result[8] = sine;
        result[10] = cosine;
        return result;
    }

    /** Returns a right-handed projection whose post-divide depth range is [0, 1]. */
    static float[] perspective(float verticalFieldOfViewRadians, float aspect, float near, float far) {
        if (!(verticalFieldOfViewRadians > 0 && verticalFieldOfViewRadians < Math.PI)) {
            throw new IllegalArgumentException("vertical field of view must be in (0, PI)");
        }
        if (!(aspect > 0) || !(near > 0) || !(far > near)) {
            throw new IllegalArgumentException("perspective requires aspect > 0 and far > near > 0");
        }
        float focalLength = 1.0f / (float) Math.tan(verticalFieldOfViewRadians * 0.5f);
        float[] result = new float[16];
        result[0] = focalLength / aspect;
        result[5] = focalLength;
        result[10] = far / (near - far);
        result[11] = -1;
        result[14] = far * near / (near - far);
        return result;
    }

    static float[] lookAt(
            float eyeX,
            float eyeY,
            float eyeZ,
            float centerX,
            float centerY,
            float centerZ,
            float upX,
            float upY,
            float upZ) {
        float[] forward = normalize(centerX - eyeX, centerY - eyeY, centerZ - eyeZ);
        float[] side = normalize(
                forward[1] * upZ - forward[2] * upY,
                forward[2] * upX - forward[0] * upZ,
                forward[0] * upY - forward[1] * upX);
        float[] up = new float[]{
                side[1] * forward[2] - side[2] * forward[1],
                side[2] * forward[0] - side[0] * forward[2],
                side[0] * forward[1] - side[1] * forward[0]
        };

        float[] result = identity();
        result[0] = side[0];
        result[1] = up[0];
        result[2] = -forward[0];
        result[4] = side[1];
        result[5] = up[1];
        result[6] = -forward[1];
        result[8] = side[2];
        result[9] = up[2];
        result[10] = -forward[2];
        result[12] = -dot(side, eyeX, eyeY, eyeZ);
        result[13] = -dot(up, eyeX, eyeY, eyeZ);
        result[14] = dot(forward, eyeX, eyeY, eyeZ);
        return result;
    }

    static void put(ByteBuffer destination, float[] matrix) {
        requireMatrix(matrix, "matrix");
        for (float value : matrix) destination.putFloat(value);
    }

    static float[] transform(float[] matrix, float x, float y, float z, float w) {
        requireMatrix(matrix, "matrix");
        return new float[]{
                matrix[0] * x + matrix[4] * y + matrix[8] * z + matrix[12] * w,
                matrix[1] * x + matrix[5] * y + matrix[9] * z + matrix[13] * w,
                matrix[2] * x + matrix[6] * y + matrix[10] * z + matrix[14] * w,
                matrix[3] * x + matrix[7] * y + matrix[11] * z + matrix[15] * w
        };
    }

    private static float[] normalize(float x, float y, float z) {
        float length = (float) Math.sqrt(x * x + y * y + z * z);
        if (!(length > 0)) throw new IllegalArgumentException("cannot normalize a zero-length vector");
        return new float[]{x / length, y / length, z / length};
    }

    private static float dot(float[] vector, float x, float y, float z) {
        return vector[0] * x + vector[1] * y + vector[2] * z;
    }

    private static void requireMatrix(float[] matrix, String name) {
        if (matrix == null || matrix.length != 16) {
            throw new IllegalArgumentException(name + " must contain 16 column-major elements");
        }
    }
}
