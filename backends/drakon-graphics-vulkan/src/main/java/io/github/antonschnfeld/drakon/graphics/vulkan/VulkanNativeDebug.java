package io.github.antonschnfeld.drakon.graphics.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackDataEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCreateInfoEXT;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkLayerProperties;

import java.lang.foreign.Arena;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_VERBOSE_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME;
import static org.lwjgl.vulkan.EXTDebugUtils.vkCreateDebugUtilsMessengerEXT;
import static org.lwjgl.vulkan.EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT;
import static org.lwjgl.vulkan.VK10.VK_FALSE;
import static org.lwjgl.vulkan.VK10.VK_INCOMPLETE;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkEnumerateInstanceExtensionProperties;
import static org.lwjgl.vulkan.VK10.vkEnumerateInstanceLayerProperties;

/** Selects and owns optional Vulkan native diagnostics for one instance. */
final class VulkanNativeDebug {
    static final String VALIDATION_LAYER = "VK_LAYER_KHRONOS_validation";
    private static final int SEVERITIES = VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT
            | VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT;
    private static final int TYPES = VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT
            | VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT
            | VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT;

    private final Selection selection;
    private final VkDebugUtilsMessengerCallbackEXT callback;
    private long messenger;
    private boolean callbackReleased;

    private VulkanNativeDebug(Selection selection, VkDebugUtilsMessengerCallbackEXT callback) {
        this.selection = selection;
        this.callback = callback;
    }

    static VulkanNativeDebug prepare(boolean requested, Collection<String> requiredExtensions, Arena arena) {
        Set<String> layers = requested ? availableLayers(arena) : Set.of();
        Set<String> extensions = requested ? availableExtensions(arena) : Set.of();
        Selection selection = select(requested, requiredExtensions, layers, extensions);
        if (requested && !selection.validationLayer()) {
            System.err.println("[drakon-graphics][vulkan] " + VALIDATION_LAYER + " unavailable");
        }
        if (requested && !selection.debugUtils()) {
            System.err.println("[drakon-graphics][vulkan] " + VK_EXT_DEBUG_UTILS_EXTENSION_NAME + " unavailable");
        }
        VkDebugUtilsMessengerCallbackEXT callback = selection.debugUtils()
                ? VkDebugUtilsMessengerCallbackEXT.create(VulkanNativeDebug::report)
                : null;
        return new VulkanNativeDebug(selection, callback);
    }

    static Selection select(
            boolean requested,
            Collection<String> requiredExtensions,
            Set<String> availableLayers,
            Set<String> availableExtensions) {
        LinkedHashSet<String> extensions = new LinkedHashSet<>(requiredExtensions);
        boolean validationLayer = requested && availableLayers.contains(VALIDATION_LAYER);
        boolean debugUtils = requested && availableExtensions.contains(VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
        if (debugUtils) extensions.add(VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
        return new Selection(
                validationLayer ? List.of(VALIDATION_LAYER) : List.of(),
                List.copyOf(extensions),
                validationLayer,
                debugUtils);
    }

    List<String> layers() {
        return selection.layers();
    }

    List<String> extensions() {
        return selection.extensions();
    }

    VkDebugUtilsMessengerCreateInfoEXT createInfo(Arena arena) {
        if (callback == null) return null;
        return VulkanFfm.struct(
                        arena,
                        VkDebugUtilsMessengerCreateInfoEXT.SIZEOF,
                        VkDebugUtilsMessengerCreateInfoEXT.ALIGNOF,
                        VkDebugUtilsMessengerCreateInfoEXT::create)
                .sType$Default()
                .messageSeverity(SEVERITIES)
                .messageType(TYPES)
                .pfnUserCallback(callback);
    }

    void createMessenger(VkInstance instance, Arena arena) {
        if (callback == null) return;
        LongBuffer handle = VulkanFfm.longs(arena, 1);
        VulkanDevice.check(
                vkCreateDebugUtilsMessengerEXT(instance, createInfo(arena), null, handle),
                "vkCreateDebugUtilsMessengerEXT");
        messenger = handle.get(0);
    }

    /** Destroys the instance-owned messenger while retaining the callback. */
    void destroyMessenger(VkInstance instance) {
        if (messenger != 0L) {
            vkDestroyDebugUtilsMessengerEXT(instance, messenger, null);
            messenger = 0L;
        }
    }

    /** Releases the callback after its instance pNext callback can no longer run. */
    void releaseCallback() {
        if (callbackReleased) return;
        callbackReleased = true;
        if (callback != null) callback.free();
    }

    private static Set<String> availableLayers(Arena arena) {
        IntBuffer count = VulkanFfm.ints(arena, 1);
        int result = vkEnumerateInstanceLayerProperties(count, null);
        if (result != VK_SUCCESS) {
            throw new IllegalStateException("vkEnumerateInstanceLayerProperties(count) failed with VkResult " + result);
        }
        if (count.get(0) == 0) return Set.of();
        VkLayerProperties.Buffer properties = VulkanFfm.structBuffer(
                arena, VkLayerProperties.SIZEOF, VkLayerProperties.ALIGNOF, count.get(0), VkLayerProperties::create);
        result = vkEnumerateInstanceLayerProperties(count, properties);
        if (result != VK_SUCCESS && result != VK_INCOMPLETE) {
            throw new IllegalStateException("vkEnumerateInstanceLayerProperties failed with VkResult " + result);
        }
        Set<String> names = new LinkedHashSet<>();
        for (int i = 0; i < Math.min(count.get(0), properties.capacity()); i++) {
            names.add(properties.get(i).layerNameString());
        }
        return names;
    }

    private static Set<String> availableExtensions(Arena arena) {
        IntBuffer count = VulkanFfm.ints(arena, 1);
        int result = vkEnumerateInstanceExtensionProperties((String) null, count, null);
        if (result != VK_SUCCESS) {
            throw new IllegalStateException("vkEnumerateInstanceExtensionProperties(count) failed with VkResult " + result);
        }
        if (count.get(0) == 0) return Set.of();
        VkExtensionProperties.Buffer properties = VulkanFfm.structBuffer(
                arena,
                VkExtensionProperties.SIZEOF,
                VkExtensionProperties.ALIGNOF,
                count.get(0),
                VkExtensionProperties::create);
        result = vkEnumerateInstanceExtensionProperties((String) null, count, properties);
        if (result != VK_SUCCESS && result != VK_INCOMPLETE) {
            throw new IllegalStateException("vkEnumerateInstanceExtensionProperties failed with VkResult " + result);
        }
        Set<String> names = new LinkedHashSet<>();
        for (int i = 0; i < Math.min(count.get(0), properties.capacity()); i++) {
            names.add(properties.get(i).extensionNameString());
        }
        return names;
    }

    private static int report(int severity, int types, long callbackDataAddress, long userData) {
        try {
            if (!accepts(severity)) return VK_FALSE;
            VkDebugUtilsMessengerCallbackDataEXT data =
                    VkDebugUtilsMessengerCallbackDataEXT.create(callbackDataAddress);
            System.err.println(format(
                    severity,
                    types,
                    data.pMessageIdNameString(),
                    data.messageIdNumber(),
                    data.pMessageString()));
        } catch (Throwable failure) {
            System.err.println("[drakon-graphics][vulkan] failed to format native diagnostic: " + failure);
        }
        return VK_FALSE;
    }

    static boolean accepts(int severity) {
        return (severity & SEVERITIES) != 0;
    }

    static String format(int severity, int types, String idName, int idNumber, String message) {
        String id = idName == null || idName.isBlank()
                ? Integer.toString(idNumber)
                : idName + "(" + idNumber + ")";
        return "[drakon-graphics][vulkan][" + severityName(severity) + "][" + typeName(types) + "] "
                + "id=" + id + ": " + compact(message);
    }

    private static String severityName(int severity) {
        if ((severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0) return "ERROR";
        if ((severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT) != 0) return "WARNING";
        if ((severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT) != 0) return "INFO";
        if ((severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_VERBOSE_BIT_EXT) != 0) return "VERBOSE";
        return "UNKNOWN";
    }

    private static String typeName(int types) {
        List<String> names = new ArrayList<>(3);
        if ((types & VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT) != 0) names.add("GENERAL");
        if ((types & VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT) != 0) names.add("VALIDATION");
        if ((types & VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT) != 0) names.add("PERFORMANCE");
        return names.isEmpty() ? "UNKNOWN" : String.join("|", names);
    }

    private static String compact(String message) {
        if (message == null) return "";
        return message.replace('\r', ' ').replace('\n', ' ').strip();
    }

    record Selection(
            List<String> layers,
            List<String> extensions,
            boolean validationLayer,
            boolean debugUtils) {}
}
