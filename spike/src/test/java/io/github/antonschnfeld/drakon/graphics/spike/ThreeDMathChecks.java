package io.github.antonschnfeld.drakon.graphics.spike;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Hardware-free checks for the spike-private 3D matrix and instance layout. */
public final class ThreeDMathChecks {
    private static final float EPSILON = 0.0001f;

    private ThreeDMathChecks() {}

    /** Runs all checks. */
    public static void main(String[] args) {
        checkColumnMajorPacking();
        checkMultiplicationOrder();
        checkCanonicalDepthMapping();
        checkLookAt();
        checkInstanceLayout();
        System.out.println("3D spike matrix and instance-layout checks passed.");
    }

    private static void checkColumnMajorPacking() {
        float[] translation = ThreeDMath.translation(2, 3, 4);
        requireNear(translation[12], 2, "translation X column");
        requireNear(translation[13], 3, "translation Y column");
        requireNear(translation[14], 4, "translation Z column");

        ByteBuffer packed = ByteBuffer.allocate(16 * Float.BYTES).order(ByteOrder.nativeOrder());
        ThreeDMath.put(packed, translation);
        packed.flip();
        requireNear(packed.getFloat(12 * Float.BYTES), 2, "packed translation X");
        requireNear(packed.getFloat(13 * Float.BYTES), 3, "packed translation Y");
        requireNear(packed.getFloat(14 * Float.BYTES), 4, "packed translation Z");
    }

    private static void checkMultiplicationOrder() {
        float[] model = ThreeDMath.multiply(
                ThreeDMath.translation(2, 0, 0),
                ThreeDMath.rotationY((float) (Math.PI * 0.5)));
        float[] transformed = ThreeDMath.transform(model, 0, 0, -1, 1);
        requireNear(transformed[0], 1, "translation after rotation X");
        requireNear(transformed[2], 0, "translation after rotation Z");
        requireNear(transformed[3], 1, "affine W");
    }

    private static void checkCanonicalDepthMapping() {
        float near = 0.1f;
        float far = 100;
        float[] projection = ThreeDMath.perspective((float) Math.toRadians(60), 1.6f, near, far);
        float[] nearClip = ThreeDMath.transform(projection, 0, 0, -near, 1);
        float[] farClip = ThreeDMath.transform(projection, 0, 0, -far, 1);
        requireNear(nearClip[2] / nearClip[3], 0, "canonical near depth");
        requireNear(farClip[2] / farClip[3], 1, "canonical far depth");
        if (!(nearClip[3] > 0 && farClip[3] > nearClip[3])) {
            throw new AssertionError("perspective divide W must be positive and increase with distance");
        }
    }

    private static void checkLookAt() {
        float[] view = ThreeDMath.lookAt(0, 0, 5, 0, 0, 0, 0, 1, 0);
        float[] eye = ThreeDMath.transform(view, 0, 0, 5, 1);
        float[] center = ThreeDMath.transform(view, 0, 0, 0, 1);
        requireNear(eye[0], 0, "eye X");
        requireNear(eye[1], 0, "eye Y");
        requireNear(eye[2], 0, "eye Z");
        requireNear(center[2], -5, "forward view depth");
    }

    private static void checkInstanceLayout() {
        if (ThreeDWorkload.INSTANCE_STRIDE != 20 * Float.BYTES
                || ThreeDWorkload.INSTANCE_BUFFER_BYTES
                != ThreeDWorkload.INSTANCE_COUNT * ThreeDWorkload.INSTANCE_STRIDE) {
            throw new AssertionError("instance matrix+tint layout must be 80 bytes per record");
        }
    }

    private static void requireNear(float actual, float expected, String label) {
        if (Math.abs(actual - expected) > EPSILON) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }
}
