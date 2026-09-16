package io.github.antonschnfeld.drakon.graphics.opengl;

import org.lwjgl.opengl.GLDebugMessageCallback;

import static org.lwjgl.opengl.GL11C.glDisable;
import static org.lwjgl.opengl.GL11C.glEnable;
import static org.lwjgl.opengl.GL11C.glGetPointer;
import static org.lwjgl.opengl.GL11C.glIsEnabled;
import static org.lwjgl.opengl.GL43C.GL_DEBUG_CALLBACK_FUNCTION;
import static org.lwjgl.opengl.GL43C.GL_DEBUG_OUTPUT;
import static org.lwjgl.opengl.GL43C.GL_DEBUG_OUTPUT_SYNCHRONOUS;
import static org.lwjgl.opengl.GL43C.GL_DEBUG_SEVERITY_HIGH;
import static org.lwjgl.opengl.GL43C.GL_DEBUG_SEVERITY_MEDIUM;
import static org.lwjgl.opengl.GL43C.GL_DEBUG_SOURCE_API;
import static org.lwjgl.opengl.GL43C.GL_DEBUG_SOURCE_APPLICATION;
import static org.lwjgl.opengl.GL43C.GL_DEBUG_SOURCE_OTHER;
import static org.lwjgl.opengl.GL43C.GL_DEBUG_SOURCE_SHADER_COMPILER;
import static org.lwjgl.opengl.GL43C.GL_DEBUG_SOURCE_THIRD_PARTY;
import static org.lwjgl.opengl.GL43C.GL_DEBUG_SOURCE_WINDOW_SYSTEM;
import static org.lwjgl.opengl.GL43C.GL_DEBUG_TYPE_DEPRECATED_BEHAVIOR;
import static org.lwjgl.opengl.GL43C.GL_DEBUG_TYPE_ERROR;
import static org.lwjgl.opengl.GL43C.GL_DEBUG_TYPE_MARKER;
import static org.lwjgl.opengl.GL43C.GL_DEBUG_TYPE_OTHER;
import static org.lwjgl.opengl.GL43C.GL_DEBUG_TYPE_PERFORMANCE;
import static org.lwjgl.opengl.GL43C.GL_DEBUG_TYPE_POP_GROUP;
import static org.lwjgl.opengl.GL43C.GL_DEBUG_TYPE_PORTABILITY;
import static org.lwjgl.opengl.GL43C.GL_DEBUG_TYPE_PUSH_GROUP;
import static org.lwjgl.opengl.GL43C.GL_DEBUG_TYPE_UNDEFINED_BEHAVIOR;
import static org.lwjgl.opengl.GL43C.glDebugMessageCallback;
import static org.lwjgl.opengl.GL43C.nglDebugMessageCallback;

/** Owns an OpenGL callback only when the external context did not already have one. */
final class OpenGLNativeDebug {
    private final GLDebugMessageCallback callback;
    private final boolean outputWasEnabled;
    private final boolean synchronousWasEnabled;
    private boolean closed;

    private OpenGLNativeDebug(
            GLDebugMessageCallback callback,
            boolean outputWasEnabled,
            boolean synchronousWasEnabled) {
        this.callback = callback;
        this.outputWasEnabled = outputWasEnabled;
        this.synchronousWasEnabled = synchronousWasEnabled;
    }

    static OpenGLNativeDebug install(boolean requested) {
        if (!requested || !shouldInstall(glGetPointer(GL_DEBUG_CALLBACK_FUNCTION))) return null;

        boolean outputEnabled = glIsEnabled(GL_DEBUG_OUTPUT);
        boolean synchronousEnabled = glIsEnabled(GL_DEBUG_OUTPUT_SYNCHRONOUS);
        GLDebugMessageCallback callback = GLDebugMessageCallback.create(OpenGLNativeDebug::report);
        try {
            glDebugMessageCallback(callback, 0L);
            glEnable(GL_DEBUG_OUTPUT);
            glEnable(GL_DEBUG_OUTPUT_SYNCHRONOUS);
            return new OpenGLNativeDebug(callback, outputEnabled, synchronousEnabled);
        } catch (RuntimeException | Error failure) {
            if (ownsInstalledCallback(glGetPointer(GL_DEBUG_CALLBACK_FUNCTION), callback.address())) {
                nglDebugMessageCallback(0L, 0L);
                restoreCapabilities(outputEnabled, synchronousEnabled);
            }
            callback.free();
            throw failure;
        }
    }

    private static void report(
            int source,
            int type,
            int id,
            int severity,
            int length,
            long messageAddress,
            long userParameter) {
        try {
            if (!accepts(type, severity)) return;
            String message = GLDebugMessageCallback.getMessage(length, messageAddress);
            System.err.println(format(source, type, id, severity, message));
        } catch (Throwable failure) {
            System.err.println("[drakon-graphics][opengl] failed to format native diagnostic: " + failure);
        }
    }

    static boolean shouldInstall(long installedCallback) {
        return installedCallback == 0L;
    }

    static boolean ownsInstalledCallback(long installedCallback, long ownedCallback) {
        return installedCallback != 0L && installedCallback == ownedCallback;
    }

    static boolean accepts(int type, int severity) {
        return type == GL_DEBUG_TYPE_ERROR
                || severity == GL_DEBUG_SEVERITY_HIGH
                || severity == GL_DEBUG_SEVERITY_MEDIUM;
    }

    static String format(int source, int type, int id, int severity, String message) {
        return "[drakon-graphics][opengl][" + severityName(severity) + "][" + typeName(type) + "] "
                + sourceName(source) + " id=" + id + ": " + compact(message);
    }

    void close() {
        if (closed) return;
        closed = true;
        if (ownsInstalledCallback(glGetPointer(GL_DEBUG_CALLBACK_FUNCTION), callback.address())) {
            // Clear the native pointer while the Java callback is still alive.
            nglDebugMessageCallback(0L, 0L);
            restoreCapabilities(outputWasEnabled, synchronousWasEnabled);
        }
        callback.free();
    }

    private static void restoreCapabilities(boolean outputEnabled, boolean synchronousEnabled) {
        if (synchronousEnabled) glEnable(GL_DEBUG_OUTPUT_SYNCHRONOUS);
        else glDisable(GL_DEBUG_OUTPUT_SYNCHRONOUS);
        if (outputEnabled) glEnable(GL_DEBUG_OUTPUT);
        else glDisable(GL_DEBUG_OUTPUT);
    }

    private static String severityName(int severity) {
        return switch (severity) {
            case GL_DEBUG_SEVERITY_HIGH -> "HIGH";
            case GL_DEBUG_SEVERITY_MEDIUM -> "MEDIUM";
            default -> "OTHER";
        };
    }

    private static String typeName(int type) {
        return switch (type) {
            case GL_DEBUG_TYPE_ERROR -> "ERROR";
            case GL_DEBUG_TYPE_DEPRECATED_BEHAVIOR -> "DEPRECATED";
            case GL_DEBUG_TYPE_UNDEFINED_BEHAVIOR -> "UNDEFINED";
            case GL_DEBUG_TYPE_PORTABILITY -> "PORTABILITY";
            case GL_DEBUG_TYPE_PERFORMANCE -> "PERFORMANCE";
            case GL_DEBUG_TYPE_MARKER -> "MARKER";
            case GL_DEBUG_TYPE_PUSH_GROUP -> "PUSH_GROUP";
            case GL_DEBUG_TYPE_POP_GROUP -> "POP_GROUP";
            case GL_DEBUG_TYPE_OTHER -> "OTHER";
            default -> "UNKNOWN";
        };
    }

    private static String sourceName(int source) {
        return switch (source) {
            case GL_DEBUG_SOURCE_API -> "API";
            case GL_DEBUG_SOURCE_WINDOW_SYSTEM -> "WINDOW_SYSTEM";
            case GL_DEBUG_SOURCE_SHADER_COMPILER -> "SHADER_COMPILER";
            case GL_DEBUG_SOURCE_THIRD_PARTY -> "THIRD_PARTY";
            case GL_DEBUG_SOURCE_APPLICATION -> "APPLICATION";
            case GL_DEBUG_SOURCE_OTHER -> "OTHER";
            default -> "UNKNOWN";
        };
    }

    private static String compact(String message) {
        return message.replace('\r', ' ').replace('\n', ' ').strip();
    }
}
