package io.github.antonschnfeld.drakon.graphics.vulkan;

import io.github.antonschnfeld.drakon.graphics.backend.GraphicsDevice;
import io.github.antonschnfeld.drakon.graphics.backend.GraphicsDeviceConfig;
import io.github.antonschnfeld.drakon.graphics.command.CommandEncoder;
import io.github.antonschnfeld.drakon.graphics.command.CommandList;
import io.github.antonschnfeld.drakon.graphics.resource.*;
import io.github.antonschnfeld.drakon.graphics.shader.ShaderDescriptor;
import io.github.antonschnfeld.drakon.graphics.shader.ShaderTarget;
import io.github.antonschnfeld.drakon.graphics.shader.SpirvShaderCode;
import io.github.antonschnfeld.drakon.graphics.shader.VulkanShaderTarget;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.glfw.GLFWVulkan.*;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;

/**
 * Minimal real LWJGL Vulkan 1.3 device used to pressure-test the portable API.
 *
 * <p>This is intentionally a correctness-first spike, not a production Vulkan
 * allocator or scheduler. Buffers use host-visible coherent memory, images use
 * dedicated device-local allocations, descriptor sets come from one generously
 * sized pool, and submission waits for the queue after every command list. Those
 * choices are deliberately documented here so they cannot accidentally become
 * performance assumptions in {@code drakon-graphics}.</p>
 *
 * <p>The important part of this implementation is that all native Vulkan state
 * is derived from existing portable descriptors. If a Vulkan concept cannot be
 * derived without guessing, that is treated as API pressure and recorded in the
 * spike findings instead of being hidden behind backend magic.</p>
 */
public final class VulkanDevice implements GraphicsDevice {
    private static final Object GLFW_LOCK = new Object();
    private static int glfwUsers;
    private final GraphicsDeviceConfig config;
    private final VkInstance instance;
    private final VkPhysicalDevice physicalDevice;
    private final VkDevice device;
    private final int queueFamily;
    private final VkQueue queue;
    private final long commandPool;
    private final long descriptorPool;
    private final VulkanShaderTarget shaderTarget = new VulkanShaderTarget(1, 3, 1, 6);
    private final List<VulkanResource> resources = new ArrayList<>();
    private final IdentityHashMap<BindingLayout, VulkanDescriptorLayout> descriptorLayouts = new IdentityHashMap<>();
    private long window;
    private long surface;
    private long swapchain;
    private long[] swapchainImages = new long[0];
    private long[] swapchainViews = new long[0];
    private boolean[] swapchainInitialized = new boolean[0];
    private int swapchainWidth;
    private int swapchainHeight;
    private int swapchainVkFormat;
    private TextureFormat swapchainFormat;
    private VulkanPresentationTarget presentationTarget;
    private FrameSync[] frames = new FrameSync[0];
    private int frameSlot;
    private int imageIndex = -1;
    private boolean imageAcquired;
    private boolean glfwOwned;
    private boolean closed;

    private VulkanDevice(
            GraphicsDeviceConfig config,
            VkInstance instance,
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            int queueFamily,
            VkQueue queue,
            long commandPool,
            long descriptorPool) {
        this.config = config;
        this.instance = instance;
        this.physicalDevice = physicalDevice;
        this.device = device;
        this.queueFamily = queueFamily;
        this.queue = queue;
        this.commandPool = commandPool;
        this.descriptorPool = descriptorPool;
    }

    /** Creates a headless Vulkan 1.3 device with graphics support. */
    static VulkanDevice createHeadless(GraphicsDeviceConfig config) {
        Objects.requireNonNull(config, "config");
        try (MemoryStack stack = stackPush()) {
            VkApplicationInfo app = VkApplicationInfo.calloc(stack)
                    .sType$Default()
                    .pApplicationName(stack.UTF8("drakon-graphics backend spike"))
                    .applicationVersion(VK_MAKE_VERSION(0, 1, 0))
                    .pEngineName(stack.UTF8("drakon-graphics"))
                    .engineVersion(VK_MAKE_VERSION(0, 1, 0))
                    .apiVersion(VK_API_VERSION_1_3);

            VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.calloc(stack)
                    .sType$Default()
                    .pApplicationInfo(app);

            PointerBuffer pInstance = stack.mallocPointer(1);
            check(vkCreateInstance(createInfo, null, pInstance), "vkCreateInstance");
            VkInstance instance = new VkInstance(pInstance.get(0), createInfo);

            try {
                VkPhysicalDevice physicalDevice = pickPhysicalDevice(instance, stack);
                int queueFamily = pickQueueFamily(physicalDevice, stack);

                FloatBufferCompat priority = new FloatBufferCompat(stack);
                VkDeviceQueueCreateInfo.Buffer queueInfo = VkDeviceQueueCreateInfo.calloc(1, stack);
                queueInfo.get(0)
                        .sType$Default()
                        .queueFamilyIndex(queueFamily)
                        .pQueuePriorities(priority.buffer());

                // Dynamic rendering and synchronization2 are core in Vulkan 1.3,
                // but still need to be explicitly enabled in the feature chain.
                VkPhysicalDeviceVulkan13Features v13 = VkPhysicalDeviceVulkan13Features.calloc(stack)
                        .sType$Default()
                        .dynamicRendering(true)
                        .synchronization2(true);

                VkDeviceCreateInfo deviceInfo = VkDeviceCreateInfo.calloc(stack)
                        .sType$Default()
                        .pNext(v13.address())
                        .pQueueCreateInfos(queueInfo);

                PointerBuffer pDevice = stack.mallocPointer(1);
                check(vkCreateDevice(physicalDevice, deviceInfo, null, pDevice), "vkCreateDevice");
                VkDevice device = new VkDevice(pDevice.get(0), physicalDevice, deviceInfo);

                PointerBuffer pQueue = stack.mallocPointer(1);
                vkGetDeviceQueue(device, queueFamily, 0, pQueue);
                VkQueue queue = new VkQueue(pQueue.get(0), device);

                VkCommandPoolCreateInfo commandPoolInfo = VkCommandPoolCreateInfo.calloc(stack)
                        .sType$Default()
                        .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                        .queueFamilyIndex(queueFamily);
                LongBuffer pCommandPool = stack.mallocLong(1);
                check(vkCreateCommandPool(device, commandPoolInfo, null, pCommandPool), "vkCreateCommandPool");

                VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
                sizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1024);
                sizes.get(1).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1024);
                VkDescriptorPoolCreateInfo descriptorPoolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                        .sType$Default()
                        .flags(VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT)
                        .maxSets(1024)
                        .pPoolSizes(sizes);
                LongBuffer pDescriptorPool = stack.mallocLong(1);
                check(vkCreateDescriptorPool(device, descriptorPoolInfo, null, pDescriptorPool), "vkCreateDescriptorPool");

                return new VulkanDevice(
                        config,
                        instance,
                        physicalDevice,
                        device,
                        queueFamily,
                        queue,
                        pCommandPool.get(0),
                        pDescriptorPool.get(0));
            } catch (RuntimeException | Error failure) {
                vkDestroyInstance(instance, null);
                throw failure;
            }
        }
    }


    /**
     * Creates a visible GLFW/Vulkan device whose swapchain is exposed through
     * one stable presentation-backed {@link RenderTarget} facade.
     */
    static VulkanDevice createWindowed(
            GraphicsDeviceConfig config,
            int width,
            int height,
            String title) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(title, "title");
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("window dimensions must be > 0");
        acquireGlfw();
        long window = 0L;
        VkInstance instance = null;
        long surface = 0L;
        try (MemoryStack stack = stackPush()) {
            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
            glfwWindowHint(GLFW_VISIBLE, GLFW_TRUE);
            window = glfwCreateWindow(width, height, title, 0L, 0L);
            if (window == 0L) throw new IllegalStateException("GLFW could not create a Vulkan window");
            if (!glfwVulkanSupported()) throw new IllegalStateException("GLFW reports Vulkan is unavailable");

            PointerBuffer requiredExtensions = glfwGetRequiredInstanceExtensions();
            if (requiredExtensions == null) {
                throw new IllegalStateException("GLFW did not provide required Vulkan instance extensions");
            }

            VkApplicationInfo app = VkApplicationInfo.calloc(stack)
                    .sType$Default()
                    .pApplicationName(stack.UTF8("drakon-graphics backend spike"))
                    .applicationVersion(VK_MAKE_VERSION(0, 1, 0))
                    .pEngineName(stack.UTF8("drakon-graphics"))
                    .engineVersion(VK_MAKE_VERSION(0, 1, 0))
                    .apiVersion(VK_API_VERSION_1_3);
            VkInstanceCreateInfo instanceInfo = VkInstanceCreateInfo.calloc(stack)
                    .sType$Default()
                    .pApplicationInfo(app)
                    .ppEnabledExtensionNames(requiredExtensions);
            PointerBuffer pInstance = stack.mallocPointer(1);
            check(vkCreateInstance(instanceInfo, null, pInstance), "vkCreateInstance(windowed)");
            instance = new VkInstance(pInstance.get(0), instanceInfo);

            LongBuffer pSurface = stack.mallocLong(1);
            check(glfwCreateWindowSurface(instance, window, null, pSurface), "glfwCreateWindowSurface");
            surface = pSurface.get(0);

            VkPhysicalDevice physicalDevice = pickPhysicalDevice(instance, surface, stack);
            int queueFamily = pickQueueFamily(physicalDevice, surface, stack);

            VkDeviceQueueCreateInfo.Buffer queueInfo = VkDeviceQueueCreateInfo.calloc(1, stack);
            queueInfo.get(0)
                    .sType$Default()
                    .queueFamilyIndex(queueFamily)
                    .pQueuePriorities(stack.floats(1.0f));
            VkPhysicalDeviceVulkan13Features v13 = VkPhysicalDeviceVulkan13Features.calloc(stack)
                    .sType$Default()
                    .dynamicRendering(true)
                    .synchronization2(true);
            PointerBuffer deviceExtensions = stack.pointers(stack.UTF8(VK_KHR_SWAPCHAIN_EXTENSION_NAME));
            VkDeviceCreateInfo deviceInfo = VkDeviceCreateInfo.calloc(stack)
                    .sType$Default()
                    .pNext(v13.address())
                    .pQueueCreateInfos(queueInfo)
                    .ppEnabledExtensionNames(deviceExtensions);
            PointerBuffer pDevice = stack.mallocPointer(1);
            check(vkCreateDevice(physicalDevice, deviceInfo, null, pDevice), "vkCreateDevice(windowed)");
            VkDevice device = new VkDevice(pDevice.get(0), physicalDevice, deviceInfo);

            PointerBuffer pQueue = stack.mallocPointer(1);
            vkGetDeviceQueue(device, queueFamily, 0, pQueue);
            VkQueue queue = new VkQueue(pQueue.get(0), device);

            VkCommandPoolCreateInfo commandPoolInfo = VkCommandPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(queueFamily);
            LongBuffer pCommandPool = stack.mallocLong(1);
            check(vkCreateCommandPool(device, commandPoolInfo, null, pCommandPool), "vkCreateCommandPool");

            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1024);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1024);
            VkDescriptorPoolCreateInfo descriptorPoolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT)
                    .maxSets(1024)
                    .pPoolSizes(sizes);
            LongBuffer pDescriptorPool = stack.mallocLong(1);
            check(vkCreateDescriptorPool(device, descriptorPoolInfo, null, pDescriptorPool), "vkCreateDescriptorPool");

            VulkanDevice result = new VulkanDevice(
                    config, instance, physicalDevice, device, queueFamily, queue,
                    pCommandPool.get(0), pDescriptorPool.get(0));
            result.window = window;
            result.surface = surface;
            result.glfwOwned = true;
            result.createSwapchain(0L);
            result.createFrameSync(2);
            result.presentationTarget = result.track(new VulkanPresentationTarget(result, result.swapchainFormat));
            return result;
        } catch (RuntimeException | Error failure) {
            if (surface != 0L && instance != null) vkDestroySurfaceKHR(instance, surface, null);
            if (instance != null) vkDestroyInstance(instance, null);
            if (window != 0L) glfwDestroyWindow(window);
            releaseGlfw();
            throw failure;
        }
    }

    private static void acquireGlfw() {
        synchronized (GLFW_LOCK) {
            if (glfwUsers == 0 && !glfwInit()) throw new IllegalStateException("GLFW initialization failed");
            glfwUsers++;
        }
    }

    private static void releaseGlfw() {
        synchronized (GLFW_LOCK) {
            if (glfwUsers <= 0) return;
            glfwUsers--;
            if (glfwUsers == 0) glfwTerminate();
        }
    }

    private static VkPhysicalDevice pickPhysicalDevice(VkInstance instance, long surface, MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        check(vkEnumeratePhysicalDevices(instance, count, null), "vkEnumeratePhysicalDevices(count)");
        if (count.get(0) == 0) throw new IllegalStateException("no Vulkan physical device available");
        PointerBuffer devices = stack.mallocPointer(count.get(0));
        check(vkEnumeratePhysicalDevices(instance, count, devices), "vkEnumeratePhysicalDevices");
        for (int i = 0; i < count.get(0); i++) {
            VkPhysicalDevice candidate = new VkPhysicalDevice(devices.get(i), instance);
            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.malloc(stack);
            vkGetPhysicalDeviceProperties(candidate, properties);
            boolean versionOk = VK_API_VERSION_MAJOR(properties.apiVersion()) > 1
                    || (VK_API_VERSION_MAJOR(properties.apiVersion()) == 1
                    && VK_API_VERSION_MINOR(properties.apiVersion()) >= 3);
            if (!versionOk) continue;
            try {
                pickQueueFamily(candidate, surface, stack);
                if (supportsSwapchain(candidate, stack)) return candidate;
            } catch (IllegalStateException ignored) {
                // Try next device.
            }
        }
        throw new IllegalStateException("no Vulkan 1.3 device supports graphics and presentation");
    }

    private static int pickQueueFamily(VkPhysicalDevice physicalDevice, long surface, MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, count, null);
        VkQueueFamilyProperties.Buffer properties = VkQueueFamilyProperties.malloc(count.get(0), stack);
        vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, count, properties);
        IntBuffer supported = stack.mallocInt(1);
        for (int i = 0; i < properties.capacity(); i++) {
            int flags = properties.get(i).queueFlags();
            if ((flags & VK_QUEUE_GRAPHICS_BIT) == 0) continue;
            check(vkGetPhysicalDeviceSurfaceSupportKHR(physicalDevice, i, surface, supported), "vkGetPhysicalDeviceSurfaceSupportKHR");
            if (supported.get(0) == VK_TRUE) return i;
        }
        throw new IllegalStateException("no queue family supports graphics and presentation");
    }

    private static boolean supportsSwapchain(VkPhysicalDevice physicalDevice, MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        check(vkEnumerateDeviceExtensionProperties(physicalDevice, (ByteBuffer) null, count, null),
                "vkEnumerateDeviceExtensionProperties(count)");
        VkExtensionProperties.Buffer extensions = VkExtensionProperties.malloc(count.get(0), stack);
        check(vkEnumerateDeviceExtensionProperties(physicalDevice, (ByteBuffer) null, count, extensions),
                "vkEnumerateDeviceExtensionProperties");
        for (int i = 0; i < extensions.capacity(); i++) {
            if (VK_KHR_SWAPCHAIN_EXTENSION_NAME.equals(extensions.get(i).extensionNameString())) return true;
        }
        return false;
    }

    private static VkPhysicalDevice pickPhysicalDevice(VkInstance instance, MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        check(vkEnumeratePhysicalDevices(instance, count, null), "vkEnumeratePhysicalDevices(count)");
        if (count.get(0) == 0) throw new IllegalStateException("no Vulkan physical device available");
        PointerBuffer devices = stack.mallocPointer(count.get(0));
        check(vkEnumeratePhysicalDevices(instance, count, devices), "vkEnumeratePhysicalDevices");

        for (int i = 0; i < count.get(0); i++) {
            VkPhysicalDevice candidate = new VkPhysicalDevice(devices.get(i), instance);
            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.malloc(stack);
            vkGetPhysicalDeviceProperties(candidate, properties);
            if (VK_API_VERSION_MAJOR(properties.apiVersion()) > 1
                    || (VK_API_VERSION_MAJOR(properties.apiVersion()) == 1
                    && VK_API_VERSION_MINOR(properties.apiVersion()) >= 3)) {
                try {
                    pickQueueFamily(candidate, stack);
                    return candidate;
                } catch (IllegalStateException ignored) {
                    // Try the next physical device; this one lacks a usable queue.
                }
            }
        }
        throw new IllegalStateException("no Vulkan 1.3 device with a graphics queue available");
    }

    private static int pickQueueFamily(VkPhysicalDevice physicalDevice, MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, count, null);
        VkQueueFamilyProperties.Buffer properties = VkQueueFamilyProperties.malloc(count.get(0), stack);
        vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, count, properties);
        for (int i = 0; i < properties.capacity(); i++) {
            int flags = properties.get(i).queueFlags();
            if ((flags & VK_QUEUE_GRAPHICS_BIT) != 0) return i;
        }
        throw new IllegalStateException("no queue family supports graphics");
    }


    /** Returns the GLFW window handle for the backend spike. */
    public long windowHandle() {
        requireOpen();
        if (window == 0L) throw new IllegalStateException("this Vulkan device is headless");
        return window;
    }

    /** Returns the stable swapchain-backed render-target facade. */
    public RenderTarget defaultRenderTarget() {
        requireOpen();
        if (presentationTarget == null) throw new IllegalStateException("this Vulkan device is headless");
        return presentationTarget;
    }

    /** Returns whether the spike-created GLFW window requested closure. */
    public boolean shouldClose() {
        requireOpen();
        if (window == 0L) return false;
        return glfwWindowShouldClose(window);
    }

    /** Polls GLFW events. */
    public void pollEvents() {
        requireOpen();
        if (window != 0L) glfwPollEvents();
    }

    int presentationWidth() { requireOpen(); return swapchainWidth; }
    int presentationHeight() { requireOpen(); return swapchainHeight; }

    long presentationColorView(int index) {
        requireOpen();
        if (!imageAcquired) throw new IllegalStateException("no swapchain image is currently acquired");
        if (index != 0) throw new IndexOutOfBoundsException("presentation target has one color attachment");
        return swapchainViews[imageIndex];
    }

    /**
     * Acquires the frame lazily on first rendering use and transitions the
     * backend-owned swapchain image into color-attachment layout. The public API
     * never exposes this image as a Texture, which is why this transition is
     * intentionally backend-managed rather than expressed through ResourceState.
     */
    void preparePresentationImage(VkCommandBuffer commandBuffer) {
        requireOpen();
        ensurePresentationAcquired();
        try (MemoryStack stack = stackPush()) {
            int oldLayout = swapchainInitialized[imageIndex]
                    ? VK_IMAGE_LAYOUT_PRESENT_SRC_KHR : VK_IMAGE_LAYOUT_UNDEFINED;
            long srcStage = swapchainInitialized[imageIndex]
                    ? VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT : VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT;
            long srcAccess = 0L;
            VkImageMemoryBarrier2.Buffer barrier = VkImageMemoryBarrier2.calloc(1, stack);
            barrier.get(0)
                    .sType$Default()
                    .srcStageMask(srcStage)
                    .srcAccessMask(srcAccess)
                    .dstStageMask(VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT)
                    .dstAccessMask(VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT)
                    .oldLayout(oldLayout)
                    .newLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(swapchainImages[imageIndex])
                    .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                            .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
            VkDependencyInfo dependency = VkDependencyInfo.calloc(stack)
                    .sType$Default().pImageMemoryBarriers(barrier);
            vkCmdPipelineBarrier2(commandBuffer, dependency);
        }
    }

    void finishPresentationImage(VkCommandBuffer commandBuffer) {
        requireOpen();
        if (!imageAcquired) throw new IllegalStateException("no swapchain image is acquired");
        try (MemoryStack stack = stackPush()) {
            VkImageMemoryBarrier2.Buffer barrier = VkImageMemoryBarrier2.calloc(1, stack);
            barrier.get(0)
                    .sType$Default()
                    .srcStageMask(VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT)
                    .srcAccessMask(VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT)
                    .dstAccessMask(0L)
                    .oldLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .newLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(swapchainImages[imageIndex])
                    .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                            .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
            VkDependencyInfo dependency = VkDependencyInfo.calloc(stack)
                    .sType$Default().pImageMemoryBarriers(barrier);
            vkCmdPipelineBarrier2(commandBuffer, dependency);
        }
        swapchainInitialized[imageIndex] = true;
    }

    private void ensurePresentationAcquired() {
        if (imageAcquired) return;
        if (presentationTarget == null) throw new IllegalStateException("device has no presentation target");
        FrameSync frame = frames[frameSlot];
        retireFrame(frame);

        while (true) {
            try (MemoryStack stack = stackPush()) {
                IntBuffer pImage = stack.mallocInt(1);
                int result = vkAcquireNextImageKHR(device, swapchain, Long.MAX_VALUE, frame.imageAvailable, VK_NULL_HANDLE, pImage);
                if (result == VK_ERROR_OUT_OF_DATE_KHR) {
                    recreateSwapchain();
                    continue;
                }
                if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR) check(result, "vkAcquireNextImageKHR");
                imageIndex = pImage.get(0);
                imageAcquired = true;
                return;
            }
        }
    }

    private void retireFrame(FrameSync frame) {
        if (!frame.inFlight) return;
        try (MemoryStack stack = stackPush()) {
            check(vkWaitForFences(device, stack.longs(frame.fence), true, Long.MAX_VALUE), "vkWaitForFences");
            if (!frame.commandBuffers.isEmpty()) {
                PointerBuffer buffers = stack.mallocPointer(frame.commandBuffers.size());
                for (VkCommandBuffer commandBuffer : frame.commandBuffers) buffers.put(commandBuffer.address());
                buffers.flip();
                vkFreeCommandBuffers(device, commandPool, buffers);
                frame.commandBuffers.clear();
            }
        }
        frame.inFlight = false;
        frame.presentationSubmitted = false;
    }

    private void createFrameSync(int count) {
        frames = new FrameSync[count];
        try (MemoryStack stack = stackPush()) {
            VkSemaphoreCreateInfo semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack)
                    .sType$Default().flags(VK_FENCE_CREATE_SIGNALED_BIT);
            for (int i = 0; i < count; i++) {
                LongBuffer pAvailable = stack.callocLong(1);
                LongBuffer pFinished = stack.callocLong(1);
                LongBuffer pFence = stack.callocLong(1);
                check(vkCreateSemaphore(device, semaphoreInfo, null, pAvailable), "vkCreateSemaphore(imageAvailable)");
                try {
                    check(vkCreateSemaphore(device, semaphoreInfo, null, pFinished), "vkCreateSemaphore(renderFinished)");
                    check(vkCreateFence(device, fenceInfo, null, pFence), "vkCreateFence(frame)");
                    frames[i] = new FrameSync(pAvailable.get(0), pFinished.get(0), pFence.get(0));
                } catch (RuntimeException | Error failure) {
                    vkDestroySemaphore(device, pAvailable.get(0), null);
                    if (pFinished.get(0) != 0L) vkDestroySemaphore(device, pFinished.get(0), null);
                    throw failure;
                }
            }
        }
    }

    private void createSwapchain(long oldSwapchain) {
        try (MemoryStack stack = stackPush()) {
            VkSurfaceCapabilitiesKHR capabilities = VkSurfaceCapabilitiesKHR.malloc(stack);
            check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice, surface, capabilities),
                    "vkGetPhysicalDeviceSurfaceCapabilitiesKHR");

            IntBuffer formatCount = stack.mallocInt(1);
            check(vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, formatCount, null),
                    "vkGetPhysicalDeviceSurfaceFormatsKHR(count)");
            if (formatCount.get(0) == 0) throw new IllegalStateException("surface exposes no formats");
            VkSurfaceFormatKHR.Buffer formats = VkSurfaceFormatKHR.malloc(formatCount.get(0), stack);
            check(vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, formatCount, formats),
                    "vkGetPhysicalDeviceSurfaceFormatsKHR");
            VkSurfaceFormatKHR chosen = chooseSurfaceFormat(formats);

            int width;
            int height;
            if (capabilities.currentExtent().width() != 0xFFFFFFFF) {
                width = capabilities.currentExtent().width();
                height = capabilities.currentExtent().height();
            } else {
                IntBuffer pWidth = stack.mallocInt(1);
                IntBuffer pHeight = stack.mallocInt(1);
                glfwGetFramebufferSize(window, pWidth, pHeight);
                width = clamp(Math.max(pWidth.get(0), 1), capabilities.minImageExtent().width(), capabilities.maxImageExtent().width());
                height = clamp(Math.max(pHeight.get(0), 1), capabilities.minImageExtent().height(), capabilities.maxImageExtent().height());
            }

            int imageCount = capabilities.minImageCount() + 1;
            if (capabilities.maxImageCount() > 0) imageCount = Math.min(imageCount, capabilities.maxImageCount());

            VkSwapchainCreateInfoKHR info = VkSwapchainCreateInfoKHR.calloc(stack)
                    .sType$Default()
                    .surface(surface)
                    .minImageCount(imageCount)
                    .imageFormat(chosen.format())
                    .imageColorSpace(chosen.colorSpace())
                    .imageExtent(e -> e.width(width).height(height))
                    .imageArrayLayers(1)
                    .imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)
                    .imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .preTransform(capabilities.currentTransform())
                    .compositeAlpha(chooseCompositeAlpha(capabilities.supportedCompositeAlpha()))
                    .presentMode(VK_PRESENT_MODE_FIFO_KHR)
                    .clipped(true)
                    .oldSwapchain(oldSwapchain);
            LongBuffer pSwapchain = stack.mallocLong(1);
            check(vkCreateSwapchainKHR(device, info, null, pSwapchain), "vkCreateSwapchainKHR");
            long newSwapchain = pSwapchain.get(0);

            IntBuffer imageCountOut = stack.mallocInt(1);
            check(vkGetSwapchainImagesKHR(device, newSwapchain, imageCountOut, null), "vkGetSwapchainImagesKHR(count)");
            LongBuffer images = stack.mallocLong(imageCountOut.get(0));
            check(vkGetSwapchainImagesKHR(device, newSwapchain, imageCountOut, images), "vkGetSwapchainImagesKHR");
            long[] newImages = new long[imageCountOut.get(0)];
            long[] newViews = new long[imageCountOut.get(0)];
            for (int i = 0; i < newImages.length; i++) {
                newImages[i] = images.get(i);
                VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                        .sType$Default()
                        .image(newImages[i])
                        .viewType(VK_IMAGE_VIEW_TYPE_2D)
                        .format(chosen.format())
                        .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                                .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
                LongBuffer pView = stack.mallocLong(1);
                check(vkCreateImageView(device, viewInfo, null, pView), "vkCreateImageView(swapchain)");
                newViews[i] = pView.get(0);
            }

            destroySwapchainViews();
            if (oldSwapchain != 0L) vkDestroySwapchainKHR(device, oldSwapchain, null);
            swapchain = newSwapchain;
            swapchainImages = newImages;
            swapchainViews = newViews;
            swapchainInitialized = new boolean[newImages.length];
            swapchainWidth = width;
            swapchainHeight = height;
            swapchainVkFormat = chosen.format();
            swapchainFormat = switch (chosen.format()) {
                case VK_FORMAT_B8G8R8A8_UNORM -> TextureFormat.BGRA8_UNORM;
                case VK_FORMAT_R8G8B8A8_UNORM -> TextureFormat.RGBA8_UNORM;
                default -> throw new IllegalStateException("chosen swapchain format lacks portable TextureFormat mapping");
            };
        }
    }

    private void recreateSwapchain() {
        check(vkDeviceWaitIdle(device), "vkDeviceWaitIdle(recreate swapchain)");
        imageAcquired = false;
        imageIndex = -1;
        long old = swapchain;
        createSwapchain(old);
        if (presentationTarget != null && !presentationTarget.colorFormats().equals(List.of(swapchainFormat))) {
            throw new IllegalStateException("swapchain recreation changed format; stable RenderTarget format contract cannot be preserved");
        }
    }

    private static VkSurfaceFormatKHR chooseSurfaceFormat(VkSurfaceFormatKHR.Buffer formats) {
        for (int i = 0; i < formats.capacity(); i++) {
            VkSurfaceFormatKHR f = formats.get(i);
            if (f.format() == VK_FORMAT_B8G8R8A8_UNORM && f.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) return f;
        }
        for (int i = 0; i < formats.capacity(); i++) {
            VkSurfaceFormatKHR f = formats.get(i);
            if (f.format() == VK_FORMAT_R8G8B8A8_UNORM && f.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) return f;
        }
        throw new IllegalStateException("surface lacks RGBA8/BGRA8 UNORM swapchain format supported by drakon-graphics spike");
    }

    private static int chooseCompositeAlpha(int supported) {
        int[] candidates = {
                VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR,
                VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR,
                VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR,
                VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR
        };
        for (int candidate : candidates) if ((supported & candidate) != 0) return candidate;
        throw new IllegalStateException("surface exposes no supported composite-alpha mode");
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private void destroySwapchainViews() {
        for (long view : swapchainViews) if (view != 0L) vkDestroyImageView(device, view, null);
        swapchainViews = new long[0];
        swapchainImages = new long[0];
        swapchainInitialized = new boolean[0];
    }

    void requireOpen() {
        if (closed) throw new IllegalStateException("device is closed");
    }

    boolean isClosed() { return closed; }
    VkDevice vkDevice() { return device; }
    VkPhysicalDevice physicalDevice() { return physicalDevice; }
    VkQueue queue() { return queue; }
    int queueFamily() { return queueFamily; }

    private <T extends VulkanResource> T track(T resource) {
        resources.add(resource);
        return resource;
    }

    <T extends VulkanResource> T owned(Object resource, Class<T> type, String label) {
        if (!type.isInstance(resource)) throw new IllegalArgumentException(label + " was not created by the Vulkan backend");
        T result = type.cast(resource);
        if (result.device != this) throw new IllegalArgumentException(label + " belongs to another device");
        result.requireAlive();
        return result;
    }

    @Override
    public Buffer createBuffer(BufferDescriptor descriptor) {
        return createBufferInternal(Objects.requireNonNull(descriptor, "descriptor"), null);
    }

    @Override
    public Buffer createBuffer(BufferDescriptor descriptor, ByteBuffer initialData) {
        Objects.requireNonNull(initialData, "initialData");
        return createBufferInternal(Objects.requireNonNull(descriptor, "descriptor"), initialData.duplicate());
    }

    private Buffer createBufferInternal(BufferDescriptor descriptor, ByteBuffer initialData) {
        requireOpen();
        if (initialData != null && initialData.remaining() > descriptor.size()) {
            throw new IllegalArgumentException("initial data exceeds buffer size");
        }
        try (MemoryStack stack = stackPush()) {
            VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack)
                    .sType$Default()
                    .size(descriptor.size())
                    .usage(VulkanMappings.bufferUsage(descriptor.usage()))
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer pBuffer = stack.mallocLong(1);
            check(vkCreateBuffer(device, info, null, pBuffer), "vkCreateBuffer");
            long buffer = pBuffer.get(0);
            try {
                VkMemoryRequirements requirements = VkMemoryRequirements.malloc(stack);
                vkGetBufferMemoryRequirements(device, buffer, requirements);
                int memoryType = findMemoryType(
                        requirements.memoryTypeBits(),
                        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                        stack);
                long memory = allocateMemory(requirements.size(), memoryType, stack);
                check(vkBindBufferMemory(device, buffer, memory, 0), "vkBindBufferMemory");

                if (initialData != null && initialData.hasRemaining()) {
                    PointerBuffer mapped = stack.mallocPointer(1);
                    check(vkMapMemory(device, memory, 0, descriptor.size(), 0, mapped), "vkMapMemory");
                    org.lwjgl.system.MemoryUtil.memByteBuffer(
                            mapped.get(0), initialData.remaining()).put(initialData);
                    vkUnmapMemory(device, memory);
                }
                return track(new VulkanBuffer(this, buffer, memory, descriptor));
            } catch (RuntimeException | Error failure) {
                vkDestroyBuffer(device, buffer, null);
                throw failure;
            }
        }
    }

    @Override
    public Texture createTexture(TextureDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        return createTextureInternal(descriptor, null, ResourceState.UNDEFINED);
    }

    @Override
    public Texture createTexture(TextureDescriptor descriptor, ByteBuffer initialData, ResourceState initialState) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(initialData, "initialData");
        Objects.requireNonNull(initialState, "initialState");
        validateInitialTexture(descriptor, initialData, initialState);
        return createTextureInternal(descriptor, initialData.duplicate(), initialState);
    }

    private Texture createTextureInternal(TextureDescriptor descriptor, ByteBuffer initialData, ResourceState initialState) {
        requireOpen();
        try (MemoryStack stack = stackPush()) {
            VkImageCreateInfo info = VkImageCreateInfo.calloc(stack)
                    .sType$Default()
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(VulkanMappings.format(descriptor.format()))
                    .extent(e -> e.width(descriptor.width()).height(descriptor.height()).depth(1))
                    .mipLevels(1)
                    .arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VulkanMappings.imageUsage(descriptor.usage())
                            | (initialData == null ? 0 : VK_IMAGE_USAGE_TRANSFER_DST_BIT))
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            LongBuffer pImage = stack.mallocLong(1);
            check(vkCreateImage(device, info, null, pImage), "vkCreateImage");
            long image = pImage.get(0);
            long memory = 0L;
            long view = 0L;
            try {
                VkMemoryRequirements requirements = VkMemoryRequirements.malloc(stack);
                vkGetImageMemoryRequirements(device, image, requirements);
                int memoryType = findMemoryType(requirements.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, stack);
                memory = allocateMemory(requirements.size(), memoryType, stack);
                check(vkBindImageMemory(device, image, memory, 0), "vkBindImageMemory");

                VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                        .sType$Default()
                        .image(image)
                        .viewType(VK_IMAGE_VIEW_TYPE_2D)
                        .format(VulkanMappings.format(descriptor.format()))
                        .subresourceRange(r -> r
                                .aspectMask(VulkanMappings.imageAspect(descriptor.format()))
                                .baseMipLevel(0).levelCount(1)
                                .baseArrayLayer(0).layerCount(1));
                LongBuffer pView = stack.mallocLong(1);
                check(vkCreateImageView(device, viewInfo, null, pView), "vkCreateImageView");
                view = pView.get(0);
                VulkanTexture texture = new VulkanTexture(this, image, memory, view, descriptor);
                if (initialData != null) {
                    uploadTexture(texture, initialData, initialState);
                }
                texture.state = initialState;
                return track(texture);
            } catch (RuntimeException | Error failure) {
                if (view != 0L) vkDestroyImageView(device, view, null);
                if (memory != 0L) vkFreeMemory(device, memory, null);
                vkDestroyImage(device, image, null);
                throw failure;
            }
        }
    }

    private void uploadTexture(VulkanTexture texture, ByteBuffer initialData, ResourceState initialState) {
        long size = initialData.remaining();
        long stagingBuffer = 0L;
        long stagingMemory = 0L;
        VkCommandBuffer commandBuffer = null;
        try (MemoryStack stack = stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType$Default()
                    .size(size)
                    .usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer pBuffer = stack.mallocLong(1);
            check(vkCreateBuffer(device, bufferInfo, null, pBuffer), "vkCreateBuffer(texture staging)");
            stagingBuffer = pBuffer.get(0);
            VkMemoryRequirements requirements = VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(device, stagingBuffer, requirements);
            int memoryType = findMemoryType(requirements.memoryTypeBits(),
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, stack);
            stagingMemory = allocateMemory(requirements.size(), memoryType, stack);
            check(vkBindBufferMemory(device, stagingBuffer, stagingMemory, 0), "vkBindBufferMemory(texture staging)");

            PointerBuffer mapped = stack.mallocPointer(1);
            check(vkMapMemory(device, stagingMemory, 0, size, 0, mapped), "vkMapMemory(texture staging)");
            try {
                MemoryUtil.memByteBuffer(mapped.get(0), Math.toIntExact(size)).put(initialData);
            } finally {
                vkUnmapMemory(device, stagingMemory);
            }

            VkCommandBufferAllocateInfo allocate = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType$Default()
                    .commandPool(commandPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1);
            PointerBuffer pCommandBuffer = stack.mallocPointer(1);
            check(vkAllocateCommandBuffers(device, allocate, pCommandBuffer), "vkAllocateCommandBuffers(texture upload)");
            commandBuffer = new VkCommandBuffer(pCommandBuffer.get(0), device);
            VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            check(vkBeginCommandBuffer(commandBuffer, begin), "vkBeginCommandBuffer(texture upload)");

            VkBufferMemoryBarrier2.Buffer hostWrite = VkBufferMemoryBarrier2.calloc(1, stack);
            hostWrite.get(0).sType$Default()
                    .srcStageMask(VK_PIPELINE_STAGE_2_HOST_BIT)
                    .srcAccessMask(VK_ACCESS_2_HOST_WRITE_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_2_TRANSFER_BIT)
                    .dstAccessMask(VK_ACCESS_2_TRANSFER_READ_BIT)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .buffer(stagingBuffer)
                    .offset(0)
                    .size(size);
            VkImageMemoryBarrier2.Buffer toTransfer = imageBarrier(stack, texture,
                    VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT, 0L,
                    VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                    VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            vkCmdPipelineBarrier2(commandBuffer, VkDependencyInfo.calloc(stack)
                    .sType$Default().pBufferMemoryBarriers(hostWrite).pImageMemoryBarriers(toTransfer));

            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.get(0).bufferOffset(0).bufferRowLength(0).bufferImageHeight(0)
                    .imageSubresource(s -> s.aspectMask(VulkanMappings.imageAspect(texture.format()))
                            .mipLevel(0).baseArrayLayer(0).layerCount(1))
                    .imageOffset(o -> o.set(0, 0, 0))
                    .imageExtent(e -> e.set(texture.width(), texture.height(), 1));
            vkCmdCopyBufferToImage(commandBuffer, stagingBuffer, texture.image,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);

            VkImageMemoryBarrier2.Buffer toInitial = imageBarrier(stack, texture,
                    VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                    VulkanMappings.stageMask(initialState), VulkanMappings.accessMask(initialState),
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VulkanMappings.imageLayout(initialState));
            vkCmdPipelineBarrier2(commandBuffer, VkDependencyInfo.calloc(stack)
                    .sType$Default().pImageMemoryBarriers(toInitial));
            check(vkEndCommandBuffer(commandBuffer), "vkEndCommandBuffer(texture upload)");
            VkSubmitInfo submit = VkSubmitInfo.calloc(stack).sType$Default()
                    .pCommandBuffers(stack.pointers(commandBuffer.address()));
            check(vkQueueSubmit(queue, submit, VK_NULL_HANDLE), "vkQueueSubmit(texture upload)");
            check(vkQueueWaitIdle(queue), "vkQueueWaitIdle(texture upload)");
        } finally {
            if (commandBuffer != null) vkFreeCommandBuffers(device, commandPool, commandBuffer);
            if (stagingBuffer != 0L) vkDestroyBuffer(device, stagingBuffer, null);
            if (stagingMemory != 0L) vkFreeMemory(device, stagingMemory, null);
        }
    }

    private VkImageMemoryBarrier2.Buffer imageBarrier(
            MemoryStack stack, VulkanTexture texture, long srcStage, long srcAccess,
            long dstStage, long dstAccess, int oldLayout, int newLayout) {
        VkImageMemoryBarrier2.Buffer barrier = VkImageMemoryBarrier2.calloc(1, stack);
        barrier.get(0).sType$Default()
                .srcStageMask(srcStage).srcAccessMask(srcAccess)
                .dstStageMask(dstStage).dstAccessMask(dstAccess)
                .oldLayout(oldLayout).newLayout(newLayout)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(texture.image)
                .subresourceRange(r -> r.aspectMask(VulkanMappings.imageAspect(texture.format()))
                        .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
        return barrier;
    }

    private static void validateInitialTexture(
            TextureDescriptor descriptor, ByteBuffer initialData, ResourceState initialState) {
        if (descriptor.format().isDepth()) {
            throw new IllegalArgumentException("CPU initialization of depth textures is not supported");
        }
        long required = textureByteCount(descriptor);
        if (initialData.remaining() != required) {
            throw new IllegalArgumentException("initial data must contain exactly " + required + " bytes");
        }
        TextureUsage usage = switch (initialState) {
            case COLOR_ATTACHMENT_WRITE -> TextureUsage.COLOR_ATTACHMENT;
            case DEPTH_ATTACHMENT_WRITE -> TextureUsage.DEPTH_ATTACHMENT;
            case SAMPLED_READ -> TextureUsage.SAMPLED;
            case COPY_SRC -> TextureUsage.COPY_SRC;
            case COPY_DST -> TextureUsage.COPY_DST;
            case UNDEFINED, UNIFORM_READ, VERTEX_READ, INDEX_READ ->
                    throw new IllegalArgumentException(initialState + " is not a valid initialized texture state");
        };
        if (!descriptor.usage().contains(usage)) {
            throw new IllegalArgumentException(initialState + " requires texture usage " + usage);
        }
    }

    private static long textureByteCount(TextureDescriptor descriptor) {
        long texels = Math.multiplyExact((long) descriptor.width(), descriptor.height());
        int bytesPerTexel = switch (descriptor.format()) {
            case RGBA8_UNORM, BGRA8_UNORM, D32_FLOAT -> 4;
        };
        return Math.multiplyExact(texels, bytesPerTexel);
    }

    private long allocateMemory(long size, int memoryType, MemoryStack stack) {
        VkMemoryAllocateInfo info = VkMemoryAllocateInfo.calloc(stack)
                .sType$Default()
                .allocationSize(size)
                .memoryTypeIndex(memoryType);
        LongBuffer pMemory = stack.mallocLong(1);
        check(vkAllocateMemory(device, info, null, pMemory), "vkAllocateMemory");
        return pMemory.get(0);
    }

    private int findMemoryType(int typeBits, int requiredFlags, MemoryStack stack) {
        VkPhysicalDeviceMemoryProperties memory = VkPhysicalDeviceMemoryProperties.malloc(stack);
        vkGetPhysicalDeviceMemoryProperties(physicalDevice, memory);
        for (int i = 0; i < memory.memoryTypeCount(); i++) {
            if ((typeBits & (1 << i)) != 0
                    && (memory.memoryTypes(i).propertyFlags() & requiredFlags) == requiredFlags) return i;
        }
        throw new IllegalStateException("no Vulkan memory type satisfies flags 0x" + Integer.toHexString(requiredFlags));
    }

    @Override
    public ShaderTarget shaderTarget() {
        requireOpen();
        return shaderTarget;
    }

    @Override
    public Shader createShader(ShaderDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        requireOpen();
        if (!(descriptor.code() instanceof SpirvShaderCode spirv)) {
            throw new IllegalArgumentException("Vulkan backend requires SpirvShaderCode");
        }
        ByteBuffer code = spirv.code();
        if ((code.remaining() & 3) != 0) throw new IllegalArgumentException("SPIR-V byte length must be divisible by four");
        // ShaderCode owns a portable heap snapshot. LWJGL needs an aligned
        // native pointer, alive until vkCreateShaderModule has copied the code.
        ByteBuffer nativeCode = MemoryUtil.memAlloc(code.remaining());
        try (MemoryStack stack = stackPush()) {
            nativeCode.put(code).flip();
            VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack)
                    .sType$Default()
                    .pCode(nativeCode);
            LongBuffer pModule = stack.mallocLong(1);
            check(vkCreateShaderModule(device, info, null, pModule), "vkCreateShaderModule");
            return track(new VulkanShader(this, pModule.get(0), descriptor.stage(), descriptor.entryPoint()));
        } finally {
            MemoryUtil.memFree(nativeCode);
        }
    }

    @Override
    public Sampler createSampler(SamplerDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        requireOpen();
        try (MemoryStack stack = stackPush()) {
            VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack)
                    .sType$Default()
                    .magFilter(VulkanMappings.filter(descriptor.magFilter()))
                    .minFilter(VulkanMappings.filter(descriptor.minFilter()))
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VulkanMappings.addressMode(descriptor.addressMode()))
                    .addressModeV(VulkanMappings.addressMode(descriptor.addressMode()))
                    .addressModeW(VulkanMappings.addressMode(descriptor.addressMode()))
                    .minLod(0f).maxLod(0f)
                    .maxAnisotropy(1f);
            LongBuffer pSampler = stack.mallocLong(1);
            check(vkCreateSampler(device, info, null, pSampler), "vkCreateSampler");
            return track(new VulkanSampler(this, pSampler.get(0)));
        }
    }

    @Override
    public GraphicsState createGraphicsState(GraphicsStateDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        requireOpen();
        VulkanShader vertex = owned(descriptor.vertexShader(), VulkanShader.class, "vertex shader");
        VulkanShader fragment = owned(descriptor.fragmentShader(), VulkanShader.class, "fragment shader");
        try (MemoryStack stack = stackPush()) {
            List<VulkanDescriptorLayout> layouts = descriptorLayouts(descriptor.bindingLayouts());
            long pipelineLayout = createPipelineLayout(layouts, stack);
            long pipeline = 0L;
            try {
                pipeline = createGraphicsPipeline(descriptor, vertex, fragment, pipelineLayout, stack);
                return track(new VulkanGraphicsState(this, pipeline, pipelineLayout, descriptor, layouts));
            } catch (RuntimeException | Error failure) {
                if (pipeline != 0L) vkDestroyPipeline(device, pipeline, null);
                vkDestroyPipelineLayout(device, pipelineLayout, null);
                throw failure;
            }
        }
    }

    private List<VulkanDescriptorLayout> descriptorLayouts(List<BindingLayout> layouts) {
        List<VulkanDescriptorLayout> result = new ArrayList<>(layouts.size());
        for (BindingLayout layout : layouts) {
            VulkanDescriptorLayout nativeLayout = descriptorLayouts.get(layout);
            if (nativeLayout == null) {
                nativeLayout = createDescriptorLayout(layout);
                descriptorLayouts.put(layout, nativeLayout);
            }
            result.add(nativeLayout);
        }
        return result;
    }

    private VulkanDescriptorLayout createDescriptorLayout(BindingLayout layout) {
        try (MemoryStack stack = stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(layout.bindings().size(), stack);
            for (int i = 0; i < layout.bindings().size(); i++) {
                Binding<?> binding = layout.bindings().get(i);
                bindings.get(i)
                        .binding(binding.binding())
                        .descriptorType(VulkanMappings.descriptorType(binding.type()))
                        .descriptorCount(1)
                        .stageFlags(VulkanMappings.shaderStages(binding.stages()));
            }
            VkDescriptorSetLayoutCreateInfo info = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default()
                    .pBindings(bindings);
            LongBuffer pLayout = stack.mallocLong(1);
            check(vkCreateDescriptorSetLayout(device, info, null, pLayout), "vkCreateDescriptorSetLayout");
            return new VulkanDescriptorLayout(layout, pLayout.get(0));
        }
    }

    private long createPipelineLayout(List<VulkanDescriptorLayout> layouts, MemoryStack stack) {
        LongBuffer handles = stack.mallocLong(layouts.size());
        for (VulkanDescriptorLayout layout : layouts) handles.put(layout.handle);
        handles.flip();
        VkPipelineLayoutCreateInfo info = VkPipelineLayoutCreateInfo.calloc(stack)
                .sType$Default()
                .pSetLayouts(handles);
        LongBuffer pLayout = stack.mallocLong(1);
        check(vkCreatePipelineLayout(device, info, null, pLayout), "vkCreatePipelineLayout");
        return pLayout.get(0);
    }

    private long createGraphicsPipeline(
            GraphicsStateDescriptor descriptor,
            VulkanShader vertex,
            VulkanShader fragment,
            long pipelineLayout,
            MemoryStack stack) {
        VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
        stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertex.module).pName(stack.UTF8(vertex.entryPoint));
        stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragment.module).pName(stack.UTF8(fragment.entryPoint));

        VertexLayout vertexLayout = descriptor.vertexLayout();
        VkVertexInputBindingDescription.Buffer vertexBindings = VkVertexInputBindingDescription.calloc(vertexLayout.bindings().size(), stack);
        for (int i = 0; i < vertexLayout.bindings().size(); i++) {
            VertexBinding binding = vertexLayout.bindings().get(i);
            vertexBindings.get(i)
                    .binding(binding.binding())
                    .stride(binding.stride())
                    .inputRate(binding.inputRate() == VertexInputRate.PER_VERTEX
                            ? VK_VERTEX_INPUT_RATE_VERTEX : VK_VERTEX_INPUT_RATE_INSTANCE);
        }
        VkVertexInputAttributeDescription.Buffer attributes = VkVertexInputAttributeDescription.calloc(vertexLayout.attributes().size(), stack);
        for (int i = 0; i < vertexLayout.attributes().size(); i++) {
            VertexAttribute attribute = vertexLayout.attributes().get(i);
            attributes.get(i)
                    .location(attribute.location())
                    .binding(attribute.binding())
                    .format(VulkanMappings.vertexFormat(attribute.format()))
                    .offset(attribute.offset());
        }
        VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                .sType$Default()
                .pVertexBindingDescriptions(vertexBindings)
                .pVertexAttributeDescriptions(attributes);
        VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                .sType$Default().topology(VulkanMappings.topology(descriptor.topology()));
        VkPipelineViewportStateCreateInfo viewport = VkPipelineViewportStateCreateInfo.calloc(stack)
                .sType$Default().viewportCount(1).scissorCount(1);
        VkPipelineRasterizationStateCreateInfo raster = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                .sType$Default()
                .polygonMode(VK_POLYGON_MODE_FILL)
                .cullMode(VulkanMappings.cull(descriptor.rasterState().cullMode()))
                // The negative-height viewport restores the OpenGL-style Y
                // orientation. Keep counter-clockwise front faces with it.
                .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE)
                .lineWidth(1f);
        VkPipelineMultisampleStateCreateInfo multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                .sType$Default().rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
        VkPipelineDepthStencilStateCreateInfo depth = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                .sType$Default()
                .depthTestEnable(descriptor.depthState().testEnabled())
                .depthWriteEnable(descriptor.depthState().writeEnabled())
                .depthCompareOp(VulkanMappings.compare(descriptor.depthState().compareOp()));

        VkPipelineColorBlendAttachmentState.Buffer blendAttachments = VkPipelineColorBlendAttachmentState.calloc(descriptor.colorFormats().size(), stack);
        for (int i = 0; i < blendAttachments.capacity(); i++) {
            VkPipelineColorBlendAttachmentState attachment = blendAttachments.get(i)
                    .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT)
                    .blendEnable(descriptor.blendState().enabled());
            if (descriptor.blendState().enabled()) {
                attachment.srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA)
                        .dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                        .colorBlendOp(VK_BLEND_OP_ADD)
                        .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE)
                        .dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                        .alphaBlendOp(VK_BLEND_OP_ADD);
            }
        }
        VkPipelineColorBlendStateCreateInfo blend = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                .sType$Default().pAttachments(blendAttachments);

        IntBuffer dynamicStates = stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR);
        VkPipelineDynamicStateCreateInfo dynamic = VkPipelineDynamicStateCreateInfo.calloc(stack)
                .sType$Default().pDynamicStates(dynamicStates);

        IntBuffer colorFormats = stack.mallocInt(descriptor.colorFormats().size());
        for (TextureFormat format : descriptor.colorFormats()) colorFormats.put(VulkanMappings.format(format));
        colorFormats.flip();
        VkPipelineRenderingCreateInfo rendering = VkPipelineRenderingCreateInfo.calloc(stack)
                .sType$Default()
                .pColorAttachmentFormats(colorFormats)
                .depthAttachmentFormat(descriptor.depthFormat().map(VulkanMappings::format).orElse(VK_FORMAT_UNDEFINED));

        VkGraphicsPipelineCreateInfo.Buffer pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack);
        pipelineInfo.get(0)
                .sType$Default()
                .pNext(rendering.address())
                .pStages(stages)
                .pVertexInputState(vertexInput)
                .pInputAssemblyState(inputAssembly)
                .pViewportState(viewport)
                .pRasterizationState(raster)
                .pMultisampleState(multisample)
                .pDepthStencilState(depth)
                .pColorBlendState(blend)
                .pDynamicState(dynamic)
                .layout(pipelineLayout)
                .renderPass(VK_NULL_HANDLE)
                .subpass(0);
        LongBuffer pPipeline = stack.mallocLong(1);
        check(vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, pipelineInfo, null, pPipeline), "vkCreateGraphicsPipelines");
        return pPipeline.get(0);
    }

    @Override
    public BindingSet createBindingSet(BindingSetDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        requireOpen();
        VulkanDescriptorLayout layout = descriptorLayouts(List.of(descriptor.layout())).get(0);
        try (MemoryStack stack = stackPush()) {
            VkDescriptorSetAllocateInfo allocate = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType$Default()
                    .descriptorPool(descriptorPool)
                    .pSetLayouts(stack.longs(layout.handle));
            LongBuffer pSet = stack.mallocLong(1);
            check(vkAllocateDescriptorSets(device, allocate, pSet), "vkAllocateDescriptorSets");
            long set = pSet.get(0);

            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(descriptor.layout().bindings().size(), stack);
            List<VkDescriptorImageInfo.Buffer> imageInfos = new ArrayList<>();
            List<VkDescriptorBufferInfo.Buffer> bufferInfos = new ArrayList<>();
            for (int i = 0; i < descriptor.layout().bindings().size(); i++) {
                Binding<?> binding = descriptor.layout().bindings().get(i);
                VkWriteDescriptorSet write = writes.get(i)
                        .sType$Default()
                        .dstSet(set)
                        .dstBinding(binding.binding())
                        .descriptorCount(1)
                        .descriptorType(VulkanMappings.descriptorType(binding.type()));
                Object value = descriptor.values().get(binding);
                switch (binding.type()) {
                    case SAMPLED_TEXTURE -> {
                        TextureBinding sampled = (TextureBinding) value;
                        VulkanTexture texture = owned(sampled.texture(), VulkanTexture.class, "sampled texture");
                        VulkanSampler sampler = owned(sampled.sampler(), VulkanSampler.class, "sampler");
                        VkDescriptorImageInfo.Buffer image = VkDescriptorImageInfo.calloc(1, stack);
                        image.get(0)
                                .sampler(sampler.handle)
                                .imageView(texture.view)
                                .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                        imageInfos.add(image);
                        write.pImageInfo(image);
                    }
                    case UNIFORM_BUFFER -> {
                        BufferBinding range = (BufferBinding) value;
                        VulkanBuffer buffer = owned(range.buffer(), VulkanBuffer.class, "bound buffer");
                        VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack);
                        bufferInfo.get(0).buffer(buffer.handle).offset(range.offset()).range(range.size());
                        bufferInfos.add(bufferInfo);
                        write.pBufferInfo(bufferInfo);
                    }
                }
            }
            vkUpdateDescriptorSets(device, writes, null);
            return track(new VulkanBindingSet(this, set, descriptor));
        }
    }

    @Override
    public RenderTarget createRenderTarget(RenderTargetDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        requireOpen();
        List<VulkanTexture> colors = descriptor.colorAttachments().stream()
                .map(texture -> owned(texture, VulkanTexture.class, "color attachment"))
                .toList();
        VulkanTexture depth = descriptor.depthAttachment() == null
                ? null : owned(descriptor.depthAttachment(), VulkanTexture.class, "depth attachment");
        return track(new VulkanRenderTarget(this, colors, depth));
    }

    @Override
    public void present(RenderTarget target) {
        Objects.requireNonNull(target, "target");
        VulkanTarget vkTarget = owned(target, VulkanTarget.class, "render target");
        if (!(vkTarget instanceof VulkanPresentationTarget presentation) || presentation != presentationTarget) {
            throw new IllegalArgumentException("render target is not this device's presentation target");
        }
        if (!imageAcquired) throw new IllegalStateException("presentation target has no acquired image");
        FrameSync frame = frames[frameSlot];
        if (!frame.presentationSubmitted) {
            throw new IllegalStateException("presentation target has not been submitted for this frame");
        }
        try (MemoryStack stack = stackPush()) {
            // present() is the public frame boundary. Signal renderFinished from an
            // empty queue submission here rather than forcing submit() to guess
            // which command list is the final one for this presentation image.
            // Queue order guarantees that all previously submitted rendering for
            // this image completes before this semaphore is signalled.
            check(vkResetFences(device, stack.longs(frame.fence)), "vkResetFences");
            VkSubmitInfo finishSubmit = VkSubmitInfo.calloc(stack)
                    .sType$Default()
                    .pSignalSemaphores(stack.longs(frame.renderFinished));
            check(vkQueueSubmit(queue, finishSubmit, frame.fence), "vkQueueSubmit(presentation boundary)");
            frame.inFlight = true;

            VkPresentInfoKHR info = VkPresentInfoKHR.calloc(stack)
                    .sType$Default()
                    .pWaitSemaphores(stack.longs(frame.renderFinished))
                    // LWJGL cannot infer a count shared by multiple arrays.
                    .swapchainCount(1)
                    .pSwapchains(stack.longs(swapchain))
                    .pImageIndices(stack.ints(imageIndex));
            int result = vkQueuePresentKHR(queue, info);
            imageAcquired = false;
            imageIndex = -1;
            frameSlot = (frameSlot + 1) % frames.length;
            if (result == VK_ERROR_OUT_OF_DATE_KHR || result == VK_SUBOPTIMAL_KHR) {
                recreateSwapchain();
            } else {
                check(result, "vkQueuePresentKHR");
            }
        }
    }

    @Override
    public CommandEncoder createCommandEncoder() {
        requireOpen();
        try (MemoryStack stack = stackPush()) {
            VkCommandBufferAllocateInfo allocate = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType$Default()
                    .commandPool(commandPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1);
            PointerBuffer pCommandBuffer = stack.mallocPointer(1);
            check(vkAllocateCommandBuffers(device, allocate, pCommandBuffer), "vkAllocateCommandBuffers");
            VkCommandBuffer commandBuffer = new VkCommandBuffer(pCommandBuffer.get(0), device);
            VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            check(vkBeginCommandBuffer(commandBuffer, begin), "vkBeginCommandBuffer");
            return new VulkanCommandEncoder(this, commandBuffer);
        }
    }

    @Override
    public void submit(CommandList commandList) {
        requireOpen();
        if (!(commandList instanceof VulkanCommandList list) || list.device != this) {
            throw new IllegalArgumentException("command list belongs to another backend/device");
        }
        list.markSubmitted();
        try (MemoryStack stack = stackPush()) {
            VkSubmitInfo submit = VkSubmitInfo.calloc(stack)
                    .sType$Default()
                    .pCommandBuffers(stack.pointers(list.commandBuffer.address()));

            if (list.presentationTarget != null) {
                if (list.presentationTarget != presentationTarget || !imageAcquired) {
                    throw new IllegalStateException("presentation command list has no matching acquired swapchain image");
                }
                FrameSync frame = frames[frameSlot];
                if (frame.inFlight) {
                    throw new IllegalStateException("current presentation frame slot has already been presented");
                }
                // Only the first submission that touches an acquired swapchain
                // image waits on vkAcquireNextImageKHR. Later submissions are
                // naturally ordered on this single graphics queue. present()
                // establishes the final render-finished signal and fence.
                if (!frame.presentationSubmitted) {
                    submit.waitSemaphoreCount(1)
                            .pWaitSemaphores(stack.longs(frame.imageAvailable))
                            .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT));
                }
                check(vkQueueSubmit(queue, submit, VK_NULL_HANDLE), "vkQueueSubmit(presentation)");
                frame.presentationSubmitted = true;
                frame.commandBuffers.add(list.commandBuffer);
            } else {
                // Offscreen submissions remain synchronous in this spike so
                // command-buffer/resource lifetime stays simple while the RHI
                // mapping is evaluated. Presentation deliberately does not use
                // this shortcut and keeps two frames in flight.
                check(vkQueueSubmit(queue, submit, VK_NULL_HANDLE), "vkQueueSubmit");
                check(vkQueueWaitIdle(queue), "vkQueueWaitIdle");
                vkFreeCommandBuffers(device, commandPool, list.commandBuffer);
            }
        }
    }

    public String backendName() { requireOpen(); return "LWJGL Vulkan 1.3 spike"; }

    void destroyBuffer(long buffer, long memory) {
        vkDestroyBuffer(device, buffer, null);
        vkFreeMemory(device, memory, null);
    }

    void destroyTexture(long image, long view, long memory) {
        vkDestroyImageView(device, view, null);
        vkDestroyImage(device, image, null);
        vkFreeMemory(device, memory, null);
    }

    void destroyShaderModule(long module) { vkDestroyShaderModule(device, module, null); }
    void destroySampler(long sampler) { vkDestroySampler(device, sampler, null); }
    void destroyPipeline(long pipeline, long pipelineLayout) {
        vkDestroyPipeline(device, pipeline, null);
        vkDestroyPipelineLayout(device, pipelineLayout, null);
    }

    @Override
    public void close() {
        if (closed) return;
        vkDeviceWaitIdle(device);
        for (FrameSync frame : frames) {
            if (frame == null) continue;
            if (!frame.commandBuffers.isEmpty()) {
                try (MemoryStack stack = stackPush()) {
                    PointerBuffer buffers = stack.mallocPointer(frame.commandBuffers.size());
                    for (VkCommandBuffer commandBuffer : frame.commandBuffers) buffers.put(commandBuffer.address());
                    buffers.flip();
                    vkFreeCommandBuffers(device, commandPool, buffers);
                }
                frame.commandBuffers.clear();
            }
        }
        for (int i = resources.size() - 1; i >= 0; i--) {
            VulkanResource resource = resources.get(i);
            if (!resource.isClosed()) resource.close();
        }
        for (Map.Entry<BindingLayout, VulkanDescriptorLayout> entry : descriptorLayouts.entrySet()) {
            vkDestroyDescriptorSetLayout(device, entry.getValue().handle, null);
        }
        for (FrameSync frame : frames) {
            if (frame == null) continue;
            vkDestroySemaphore(device, frame.imageAvailable, null);
            vkDestroySemaphore(device, frame.renderFinished, null);
            vkDestroyFence(device, frame.fence, null);
        }
        destroySwapchainViews();
        if (swapchain != 0L) vkDestroySwapchainKHR(device, swapchain, null);
        vkDestroyDescriptorPool(device, descriptorPool, null);
        vkDestroyCommandPool(device, commandPool, null);
        closed = true;
        vkDestroyDevice(device, null);
        if (surface != 0L) vkDestroySurfaceKHR(instance, surface, null);
        vkDestroyInstance(instance, null);
        if (window != 0L) glfwDestroyWindow(window);
        if (glfwOwned) releaseGlfw();
    }

    static void check(int result, String operation) {
        if (result != VK_SUCCESS) throw new IllegalStateException(operation + " failed with VkResult " + result);
    }


    private static final class FrameSync {
        final long imageAvailable;
        final long renderFinished;
        final long fence;
        final List<VkCommandBuffer> commandBuffers = new ArrayList<>();
        boolean inFlight;
        boolean presentationSubmitted;

        FrameSync(long imageAvailable, long renderFinished, long fence) {
            this.imageAvailable = imageAvailable;
            this.renderFinished = renderFinished;
            this.fence = fence;
        }
    }

    /** Small helper solely to keep stack-owned queue-priority storage alive with its stack. */
    private static final class FloatBufferCompat {
        private final java.nio.FloatBuffer buffer;
        FloatBufferCompat(MemoryStack stack) { buffer = stack.floats(1.0f); }
        java.nio.FloatBuffer buffer() { return buffer; }
    }
}
