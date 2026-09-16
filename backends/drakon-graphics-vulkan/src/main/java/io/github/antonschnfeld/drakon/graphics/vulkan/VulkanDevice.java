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
import org.lwjgl.vulkan.*;

import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;

/**
 * Minimal real LWJGL Vulkan 1.3 device used to pressure-test the portable API.
 *
 * <p>This is intentionally a correctness-first spike, not a production Vulkan
 * allocator or scheduler. Buffers use host-visible coherent memory, images use
 * dedicated device-local allocations, and descriptor sets come from one
 * generously sized pool. Presentation submissions use frame synchronization and
 * do not wait for the queue after every submission; non-presentation submissions
 * currently wait for the queue to idle before freeing their command buffer.</p>
 *
 * <p>The important part of this implementation is that all native Vulkan state
 * is derived from existing portable descriptors. If a Vulkan concept cannot be
 * derived without guessing, that is treated as API pressure and recorded in the
 * spike findings instead of being hidden behind backend magic.</p>
 */
public final class VulkanDevice implements GraphicsDevice {
    private final GraphicsDeviceConfig config;
    private final VkInstance instance;
    private final VkPhysicalDevice physicalDevice;
    private final VkDevice device;
    private final int queueFamily;
    private final VkQueue queue;
    private final long commandPool;
    private final long descriptorPool;
    private final long uniformBufferOffsetAlignment;
    private final boolean presentationEnabled;
    private final VulkanShaderTarget shaderTarget = new VulkanShaderTarget(1, 3, 1, 6);
    private final List<VulkanResource> resources = new ArrayList<>();
    private final IdentityHashMap<BindingLayout, VulkanDescriptorLayout> descriptorLayouts = new IdentityHashMap<>();
    private final ArrayList<VkCommandBuffer> deferredCommandBuffers = new ArrayList<>();
    private final ArrayList<VulkanResource> deferredResources = new ArrayList<>();
    private boolean closed;

    private VulkanDevice(
            GraphicsDeviceConfig config,
            VkInstance instance,
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            int queueFamily,
            VkQueue queue,
            long commandPool,
            long descriptorPool,
            long uniformBufferOffsetAlignment,
            boolean presentationEnabled) {
        this.config = config;
        this.instance = instance;
        this.physicalDevice = physicalDevice;
        this.device = device;
        this.queueFamily = queueFamily;
        this.queue = queue;
        this.commandPool = commandPool;
        this.descriptorPool = descriptorPool;
        this.uniformBufferOffsetAlignment = uniformBufferOffsetAlignment;
        this.presentationEnabled = presentationEnabled;
    }

    /** Creates a headless Vulkan 1.3 device with graphics support. */
    static VulkanDevice createHeadless(GraphicsDeviceConfig config) {
        Objects.requireNonNull(config, "config");
        try (Arena arena = Arena.ofConfined()) {
            VkApplicationInfo app = VulkanFfm.struct(arena, VkApplicationInfo.SIZEOF, VkApplicationInfo.ALIGNOF, VkApplicationInfo::create)
                    .sType$Default()
                    .pApplicationName(VulkanFfm.utf8(arena, "drakon-graphics backend spike"))
                    .applicationVersion(VK_MAKE_VERSION(0, 1, 0))
                    .pEngineName(VulkanFfm.utf8(arena, "drakon-graphics"))
                    .engineVersion(VK_MAKE_VERSION(0, 1, 0))
                    .apiVersion(VK_API_VERSION_1_3);

            VkInstanceCreateInfo createInfo = VulkanFfm.struct(arena, VkInstanceCreateInfo.SIZEOF, VkInstanceCreateInfo.ALIGNOF, VkInstanceCreateInfo::create)
                    .sType$Default()
                    .pApplicationInfo(app);

            PointerBuffer pInstance = VulkanFfm.pointers(arena, 1);
            check(vkCreateInstance(createInfo, null, pInstance), "vkCreateInstance");
            VkInstance instance = new VkInstance(pInstance.get(0), createInfo);

            try {
                VkPhysicalDevice physicalDevice = pickPhysicalDevice(instance, arena);
                int queueFamily = pickQueueFamily(physicalDevice, arena);

                FloatBufferCompat priority = new FloatBufferCompat(arena);
                VkDeviceQueueCreateInfo.Buffer queueInfo = VulkanFfm.structBuffer(arena, VkDeviceQueueCreateInfo.SIZEOF, VkDeviceQueueCreateInfo.ALIGNOF, 1, VkDeviceQueueCreateInfo::create);
                queueInfo.get(0)
                        .sType$Default()
                        .queueFamilyIndex(queueFamily)
                        .pQueuePriorities(priority.buffer());

                // Dynamic rendering and synchronization2 are core in Vulkan 1.3,
                // but still need to be explicitly enabled in the feature chain.
                VkPhysicalDeviceVulkan13Features v13 = VulkanFfm.struct(arena, VkPhysicalDeviceVulkan13Features.SIZEOF, VkPhysicalDeviceVulkan13Features.ALIGNOF, VkPhysicalDeviceVulkan13Features::create)
                        .sType$Default()
                        .dynamicRendering(true)
                        .synchronization2(true);

                VkDeviceCreateInfo deviceInfo = VulkanFfm.struct(arena, VkDeviceCreateInfo.SIZEOF, VkDeviceCreateInfo.ALIGNOF, VkDeviceCreateInfo::create)
                        .sType$Default()
                        .pNext(v13.address())
                        .pQueueCreateInfos(queueInfo);

                PointerBuffer pDevice = VulkanFfm.pointers(arena, 1);
                check(vkCreateDevice(physicalDevice, deviceInfo, null, pDevice), "vkCreateDevice");
                VkDevice device = new VkDevice(pDevice.get(0), physicalDevice, deviceInfo);
                long commandPool = 0L;
                long descriptorPool = 0L;
                try {

                    PointerBuffer pQueue = VulkanFfm.pointers(arena, 1);
                    vkGetDeviceQueue(device, queueFamily, 0, pQueue);
                    VkQueue queue = new VkQueue(pQueue.get(0), device);

                    VkCommandPoolCreateInfo commandPoolInfo = VulkanFfm.struct(arena, VkCommandPoolCreateInfo.SIZEOF, VkCommandPoolCreateInfo.ALIGNOF, VkCommandPoolCreateInfo::create)
                            .sType$Default()
                            .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                            .queueFamilyIndex(queueFamily);
                    LongBuffer pCommandPool = VulkanFfm.longs(arena, 1);
                    check(vkCreateCommandPool(device, commandPoolInfo, null, pCommandPool), "vkCreateCommandPool");
                    commandPool = pCommandPool.get(0);

                    VkDescriptorPoolSize.Buffer sizes = VulkanFfm.structBuffer(arena, VkDescriptorPoolSize.SIZEOF, VkDescriptorPoolSize.ALIGNOF, 2, VkDescriptorPoolSize::create);
                    sizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1024);
                    sizes.get(1).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1024);
                    VkDescriptorPoolCreateInfo descriptorPoolInfo = VulkanFfm.struct(arena, VkDescriptorPoolCreateInfo.SIZEOF, VkDescriptorPoolCreateInfo.ALIGNOF, VkDescriptorPoolCreateInfo::create)
                            .sType$Default()
                            .flags(VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT)
                            .maxSets(1024)
                            .pPoolSizes(sizes);
                    LongBuffer pDescriptorPool = VulkanFfm.longs(arena, 1);
                    check(vkCreateDescriptorPool(device, descriptorPoolInfo, null, pDescriptorPool), "vkCreateDescriptorPool");
                    descriptorPool = pDescriptorPool.get(0);

                    return new VulkanDevice(
                            config,
                            instance,
                            physicalDevice,
                            device,
                            queueFamily,
                            queue,
                            commandPool,
                            descriptorPool,
                            uniformBufferOffsetAlignment(physicalDevice, arena),
                            false);
                } catch (RuntimeException | Error failure) {
                    if (descriptorPool != 0L) vkDestroyDescriptorPool(device, descriptorPool, null);
                    if (commandPool != 0L) vkDestroyCommandPool(device, commandPool, null);
                    vkDestroyDevice(device, null);
                    throw failure;
                }
            } catch (RuntimeException | Error failure) {
                vkDestroyInstance(instance, null);
                throw failure;
            }
        }
    }


    /** Creates a presentation-capable device and its first surface-backed target. */
    static VulkanPresentation createPresentation(
            GraphicsDeviceConfig config,
            VulkanSurfaceFactory surfaceFactory) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(surfaceFactory, "surfaceFactory");
        VkInstance instance = null;
        VulkanDevice result = null;
        long surface = 0L;
        try (Arena arena = Arena.ofConfined()) {
            List<String> extensionNames = List.copyOf(surfaceFactory.requiredInstanceExtensions());
            PointerBuffer requiredExtensions = VulkanFfm.pointers(arena, extensionNames.size());
            for (String extension : extensionNames) {
                requiredExtensions.put(VulkanFfm.utf8(arena, Objects.requireNonNull(extension, "instance extension")));
            }
            requiredExtensions.flip();

            VkApplicationInfo app = VulkanFfm.struct(arena, VkApplicationInfo.SIZEOF, VkApplicationInfo.ALIGNOF, VkApplicationInfo::create)
                    .sType$Default()
                    .pApplicationName(VulkanFfm.utf8(arena, "drakon-graphics"))
                    .applicationVersion(VK_MAKE_VERSION(0, 1, 0))
                    .pEngineName(VulkanFfm.utf8(arena, "drakon-graphics"))
                    .engineVersion(VK_MAKE_VERSION(0, 1, 0))
                    .apiVersion(VK_API_VERSION_1_3);
            VkInstanceCreateInfo instanceInfo = VulkanFfm.struct(arena, VkInstanceCreateInfo.SIZEOF, VkInstanceCreateInfo.ALIGNOF, VkInstanceCreateInfo::create)
                    .sType$Default()
                    .pApplicationInfo(app)
                    .ppEnabledExtensionNames(requiredExtensions);
            PointerBuffer pInstance = VulkanFfm.pointers(arena, 1);
            check(vkCreateInstance(instanceInfo, null, pInstance), "vkCreateInstance(presentation)");
            instance = new VkInstance(pInstance.get(0), instanceInfo);

            surface = surfaceFactory.createSurface(instance.address());
            if (surface == 0L) throw new IllegalStateException("surface factory returned a null Vulkan surface");

            VkPhysicalDevice physicalDevice = pickPhysicalDevice(instance, surface, arena);
            int queueFamily = pickQueueFamily(physicalDevice, surface, arena);
            VkDeviceQueueCreateInfo.Buffer queueInfo = VulkanFfm.structBuffer(arena, VkDeviceQueueCreateInfo.SIZEOF, VkDeviceQueueCreateInfo.ALIGNOF, 1, VkDeviceQueueCreateInfo::create);
            queueInfo.get(0).sType$Default().queueFamilyIndex(queueFamily).pQueuePriorities(VulkanFfm.floats(arena, 1.0f));
            VkPhysicalDeviceVulkan13Features v13 = VulkanFfm.struct(arena, VkPhysicalDeviceVulkan13Features.SIZEOF, VkPhysicalDeviceVulkan13Features.ALIGNOF, VkPhysicalDeviceVulkan13Features::create)
                    .sType$Default().dynamicRendering(true).synchronization2(true);
            VkDeviceCreateInfo deviceInfo = VulkanFfm.struct(arena, VkDeviceCreateInfo.SIZEOF, VkDeviceCreateInfo.ALIGNOF, VkDeviceCreateInfo::create)
                    .sType$Default()
                    .pNext(v13.address())
                    .pQueueCreateInfos(queueInfo)
                    .ppEnabledExtensionNames(VulkanFfm.pointers(
                            arena, VulkanFfm.utf8(arena, VK_KHR_SWAPCHAIN_EXTENSION_NAME)));
            PointerBuffer pDevice = VulkanFfm.pointers(arena, 1);
            check(vkCreateDevice(physicalDevice, deviceInfo, null, pDevice), "vkCreateDevice(presentation)");
            VkDevice device = new VkDevice(pDevice.get(0), physicalDevice, deviceInfo);

            PointerBuffer pQueue = VulkanFfm.pointers(arena, 1);
            vkGetDeviceQueue(device, queueFamily, 0, pQueue);
            VkQueue queue = new VkQueue(pQueue.get(0), device);
            VkCommandPoolCreateInfo commandPoolInfo = VulkanFfm.struct(arena, VkCommandPoolCreateInfo.SIZEOF, VkCommandPoolCreateInfo.ALIGNOF, VkCommandPoolCreateInfo::create)
                    .sType$Default().flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(queueFamily);
            LongBuffer pCommandPool = VulkanFfm.longs(arena, 1);
            check(vkCreateCommandPool(device, commandPoolInfo, null, pCommandPool), "vkCreateCommandPool");
            VkDescriptorPoolSize.Buffer sizes = VulkanFfm.structBuffer(arena, VkDescriptorPoolSize.SIZEOF, VkDescriptorPoolSize.ALIGNOF, 2, VkDescriptorPoolSize::create);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1024);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1024);
            VkDescriptorPoolCreateInfo descriptorPoolInfo = VulkanFfm.struct(arena, VkDescriptorPoolCreateInfo.SIZEOF, VkDescriptorPoolCreateInfo.ALIGNOF, VkDescriptorPoolCreateInfo::create)
                    .sType$Default().flags(VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT)
                    .maxSets(1024).pPoolSizes(sizes);
            LongBuffer pDescriptorPool = VulkanFfm.longs(arena, 1);
            check(vkCreateDescriptorPool(device, descriptorPoolInfo, null, pDescriptorPool), "vkCreateDescriptorPool");

            result = new VulkanDevice(
                    config, instance, physicalDevice, device, queueFamily, queue,
                    pCommandPool.get(0), pDescriptorPool.get(0),
                    uniformBufferOffsetAlignment(physicalDevice, arena), true);
            long ownedSurface = surface;
            surface = 0L;
            VulkanPresentationTarget target = result.createPresentationTarget(surfaceFactory, ownedSurface);
            return new VulkanPresentation(result, target);
        } catch (RuntimeException | Error failure) {
            if (result != null) {
                result.close();
                instance = null;
            }
            if (surface != 0L && instance != null) vkDestroySurfaceKHR(instance, surface, null);
            if (instance != null) vkDestroyInstance(instance, null);
            throw failure;
        }
    }

    private static VkPhysicalDevice pickPhysicalDevice(VkInstance instance, long surface, Arena arena) {
        IntBuffer count = VulkanFfm.ints(arena, 1);
        check(vkEnumeratePhysicalDevices(instance, count, null), "vkEnumeratePhysicalDevices(count)");
        if (count.get(0) == 0) throw new IllegalStateException("no Vulkan physical device available");
        PointerBuffer devices = VulkanFfm.pointers(arena, count.get(0));
        check(vkEnumeratePhysicalDevices(instance, count, devices), "vkEnumeratePhysicalDevices");
        for (int i = 0; i < count.get(0); i++) {
            VkPhysicalDevice candidate = new VkPhysicalDevice(devices.get(i), instance);
            VkPhysicalDeviceProperties properties = VulkanFfm.struct(arena, VkPhysicalDeviceProperties.SIZEOF, VkPhysicalDeviceProperties.ALIGNOF, VkPhysicalDeviceProperties::create);
            vkGetPhysicalDeviceProperties(candidate, properties);
            boolean versionOk = VK_API_VERSION_MAJOR(properties.apiVersion()) > 1
                    || (VK_API_VERSION_MAJOR(properties.apiVersion()) == 1
                    && VK_API_VERSION_MINOR(properties.apiVersion()) >= 3);
            if (!versionOk) continue;
            try {
                pickQueueFamily(candidate, surface, arena);
                if (supportsSwapchain(candidate, arena)) return candidate;
            } catch (IllegalStateException ignored) {
                // Try next device.
            }
        }
        throw new IllegalStateException("no Vulkan 1.3 device supports graphics and presentation");
    }

    private static int pickQueueFamily(VkPhysicalDevice physicalDevice, long surface, Arena arena) {
        IntBuffer count = VulkanFfm.ints(arena, 1);
        vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, count, null);
        VkQueueFamilyProperties.Buffer properties = VulkanFfm.structBuffer(arena, VkQueueFamilyProperties.SIZEOF, VkQueueFamilyProperties.ALIGNOF, count.get(0), VkQueueFamilyProperties::create);
        vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, count, properties);
        IntBuffer supported = VulkanFfm.ints(arena, 1);
        for (int i = 0; i < properties.capacity(); i++) {
            int flags = properties.get(i).queueFlags();
            if ((flags & VK_QUEUE_GRAPHICS_BIT) == 0) continue;
            check(vkGetPhysicalDeviceSurfaceSupportKHR(physicalDevice, i, surface, supported), "vkGetPhysicalDeviceSurfaceSupportKHR");
            if (supported.get(0) == VK_TRUE) return i;
        }
        throw new IllegalStateException("no queue family supports graphics and presentation");
    }

    private static boolean supportsSwapchain(VkPhysicalDevice physicalDevice, Arena arena) {
        IntBuffer count = VulkanFfm.ints(arena, 1);
        check(vkEnumerateDeviceExtensionProperties(physicalDevice, (ByteBuffer) null, count, null),
                "vkEnumerateDeviceExtensionProperties(count)");
        VkExtensionProperties.Buffer extensions = VulkanFfm.structBuffer(arena, VkExtensionProperties.SIZEOF, VkExtensionProperties.ALIGNOF, count.get(0), VkExtensionProperties::create);
        check(vkEnumerateDeviceExtensionProperties(physicalDevice, (ByteBuffer) null, count, extensions),
                "vkEnumerateDeviceExtensionProperties");
        for (int i = 0; i < extensions.capacity(); i++) {
            if (VK_KHR_SWAPCHAIN_EXTENSION_NAME.equals(extensions.get(i).extensionNameString())) return true;
        }
        return false;
    }

    private static VkPhysicalDevice pickPhysicalDevice(VkInstance instance, Arena arena) {
        IntBuffer count = VulkanFfm.ints(arena, 1);
        check(vkEnumeratePhysicalDevices(instance, count, null), "vkEnumeratePhysicalDevices(count)");
        if (count.get(0) == 0) throw new IllegalStateException("no Vulkan physical device available");
        PointerBuffer devices = VulkanFfm.pointers(arena, count.get(0));
        check(vkEnumeratePhysicalDevices(instance, count, devices), "vkEnumeratePhysicalDevices");

        for (int i = 0; i < count.get(0); i++) {
            VkPhysicalDevice candidate = new VkPhysicalDevice(devices.get(i), instance);
            VkPhysicalDeviceProperties properties = VulkanFfm.struct(arena, VkPhysicalDeviceProperties.SIZEOF, VkPhysicalDeviceProperties.ALIGNOF, VkPhysicalDeviceProperties::create);
            vkGetPhysicalDeviceProperties(candidate, properties);
            if (VK_API_VERSION_MAJOR(properties.apiVersion()) > 1
                    || (VK_API_VERSION_MAJOR(properties.apiVersion()) == 1
                    && VK_API_VERSION_MINOR(properties.apiVersion()) >= 3)) {
                try {
                    pickQueueFamily(candidate, arena);
                    return candidate;
                } catch (IllegalStateException ignored) {
                    // Try the next physical device; this one lacks a usable queue.
                }
            }
        }
        throw new IllegalStateException("no Vulkan 1.3 device with a graphics queue available");
    }

    private static int pickQueueFamily(VkPhysicalDevice physicalDevice, Arena arena) {
        IntBuffer count = VulkanFfm.ints(arena, 1);
        vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, count, null);
        VkQueueFamilyProperties.Buffer properties = VulkanFfm.structBuffer(arena, VkQueueFamilyProperties.SIZEOF, VkQueueFamilyProperties.ALIGNOF, count.get(0), VkQueueFamilyProperties::create);
        vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, count, properties);
        for (int i = 0; i < properties.capacity(); i++) {
            int flags = properties.get(i).queueFlags();
            if ((flags & VK_QUEUE_GRAPHICS_BIT) != 0) return i;
        }
        throw new IllegalStateException("no queue family supports graphics");
    }

    private static long uniformBufferOffsetAlignment(VkPhysicalDevice physicalDevice, Arena arena) {
        VkPhysicalDeviceProperties properties = VulkanFfm.struct(arena, VkPhysicalDeviceProperties.SIZEOF, VkPhysicalDeviceProperties.ALIGNOF, VkPhysicalDeviceProperties::create);
        vkGetPhysicalDeviceProperties(physicalDevice, properties);
        long alignment = properties.limits().minUniformBufferOffsetAlignment();
        if (alignment <= 0) {
            throw new IllegalStateException("Vulkan reported an invalid uniform-buffer offset alignment");
        }
        return alignment;
    }


    /**
     * Creates another presentation target for this presentation-enabled device.
     * The selected queue family must support the newly created surface and the
     * instance must already have all extensions required by the factory.
     *
     * @param surfaceFactory external surface and extent integration
     * @return a new independently presentable render target
     */
    public RenderTarget createPresentationTarget(VulkanSurfaceFactory surfaceFactory) {
        Objects.requireNonNull(surfaceFactory, "surfaceFactory");
        requireOpen();
        if (!presentationEnabled) {
            throw new IllegalStateException("headless devices cannot add presentation targets");
        }
        long surface = surfaceFactory.createSurface(instance.address());
        if (surface == 0L) throw new IllegalStateException("surface factory returned a null Vulkan surface");
        return createPresentationTarget(surfaceFactory, surface);
    }

    private VulkanPresentationTarget createPresentationTarget(
            VulkanSurfaceFactory surfaceFactory,
            long surface) {
        VulkanPresentationTarget target = new VulkanPresentationTarget(this, surfaceFactory, surface);
        try {
            try (Arena arena = Arena.ofConfined()) {
                IntBuffer supported = VulkanFfm.ints(arena, 1);
                check(vkGetPhysicalDeviceSurfaceSupportKHR(physicalDevice, queueFamily, surface, supported),
                        "vkGetPhysicalDeviceSurfaceSupportKHR");
                if (supported.get(0) != VK_TRUE) {
                    throw new IllegalArgumentException("selected queue family cannot present to this surface");
                }
            }
            createSwapchain(target, 0L);
            createFrameSync(target, 2);
            return track(target);
        } catch (RuntimeException | Error failure) {
            destroyPresentationTarget(target);
            throw failure;
        }
    }

    long presentationColorView(VulkanPresentationTarget target, int index) {
        requireOpen();
        target.requireAlive();
        if (!target.imageAcquired) throw new IllegalStateException("no swapchain image is currently acquired");
        if (index != 0) throw new IndexOutOfBoundsException("presentation target has one color attachment");
        return target.swapchainViews[target.imageIndex];
    }

    /**
     * Acquires the frame lazily on first rendering use and transitions the
     * backend-owned swapchain image into color-attachment layout. The public API
     * never exposes this image as a Texture, which is why this transition is
     * intentionally backend-managed rather than expressed through ResourceState.
     */
    VulkanPresentationState preparePresentationImage(
            VkCommandBuffer commandBuffer,
            VulkanPresentationTarget target,
            VulkanPresentationState recordingState) {
        requireOpen();
        target.requireAlive();
        ensurePresentationAcquired(target);
        VulkanPresentationState state = recordingState;
        if (state == null) {
            state = new VulkanPresentationState(
                    target, target.currentAcquisition, target.imageIndex,
                    target.swapchainInitialized[target.imageIndex]);
        } else if (state.target != target
                || state.acquisition != target.currentAcquisition
                || state.imageIndex != target.imageIndex) {
            throw new IllegalStateException("presentation acquisition changed while recording command list");
        }
        try (Arena arena = Arena.ofConfined()) {
            int oldLayout = state.recordingInitialized()
                    ? VK_IMAGE_LAYOUT_PRESENT_SRC_KHR : VK_IMAGE_LAYOUT_UNDEFINED;
            long srcStage = state.recordingInitialized()
                    ? VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT : VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT;
            long srcAccess = 0L;
            VkImageMemoryBarrier2.Buffer barrier = VulkanFfm.structBuffer(arena, VkImageMemoryBarrier2.SIZEOF, VkImageMemoryBarrier2.ALIGNOF, 1, VkImageMemoryBarrier2::create);
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
                    .image(target.swapchainImages[target.imageIndex])
                    .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                            .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
            VkDependencyInfo dependency = VulkanFfm.struct(arena, VkDependencyInfo.SIZEOF, VkDependencyInfo.ALIGNOF, VkDependencyInfo::create)
                    .sType$Default().pImageMemoryBarriers(barrier);
            vkCmdPipelineBarrier2(commandBuffer, dependency);
        }
        return state;
    }

    void finishPresentationImage(
            VulkanPresentationTarget target,
            VkCommandBuffer commandBuffer,
            VulkanPresentationState state) {
        requireOpen();
        target.requireAlive();
        if (!target.imageAcquired) throw new IllegalStateException("no swapchain image is acquired");
        if (state == null
                || state.target != target
                || state.acquisition != target.currentAcquisition
                || state.imageIndex != target.imageIndex) {
            throw new IllegalStateException("presentation acquisition changed while recording command list");
        }
        try (Arena arena = Arena.ofConfined()) {
            VkImageMemoryBarrier2.Buffer barrier = VulkanFfm.structBuffer(arena, VkImageMemoryBarrier2.SIZEOF, VkImageMemoryBarrier2.ALIGNOF, 1, VkImageMemoryBarrier2::create);
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
                    .image(target.swapchainImages[target.imageIndex])
                    .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                            .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
            VkDependencyInfo dependency = VulkanFfm.struct(arena, VkDependencyInfo.SIZEOF, VkDependencyInfo.ALIGNOF, VkDependencyInfo::create)
                    .sType$Default().pImageMemoryBarriers(barrier);
            vkCmdPipelineBarrier2(commandBuffer, dependency);
        }
        state.markInitialized();
    }

    private void ensurePresentationAcquired(VulkanPresentationTarget target) {
        if (target.imageAcquired) return;
        FrameSync frame = target.frames[target.frameSlot];
        retireFrame(frame);

        while (true) {
            try (Arena arena = Arena.ofConfined()) {
                IntBuffer pImage = VulkanFfm.ints(arena, 1);
                int result = vkAcquireNextImageKHR(
                        device, target.swapchain, Long.MAX_VALUE,
                        frame.imageAvailable, VK_NULL_HANDLE, pImage);
                if (result == VK_ERROR_OUT_OF_DATE_KHR) {
                    recreateSwapchain(target);
                    continue;
                }
                if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR) check(result, "vkAcquireNextImageKHR");
                target.imageIndex = pImage.get(0);
                target.currentAcquisition = ++target.acquisitionSerial;
                target.imageAcquired = true;
                return;
            }
        }
    }

    private void retireFrame(FrameSync frame) {
        if (!frame.inFlight) return;
        try (Arena arena = Arena.ofConfined()) {
            check(vkWaitForFences(device, VulkanFfm.longs(arena, frame.fence), true, Long.MAX_VALUE), "vkWaitForFences");
        }
        reclaimFrame(frame);
    }

    private void reclaimFrame(FrameSync frame) {
        freeCommandBuffers(frame.commandBuffers);
        List<VulkanResource> retained = new ArrayList<>(frame.resources);
        frame.resources.clear();
        frame.inFlight = false;
        frame.presentationSubmitted = false;
        releaseResources(retained);
    }

    private void createFrameSync(VulkanPresentationTarget target, int count) {
        target.frames = new FrameSync[count];
        try (Arena arena = Arena.ofConfined()) {
            VkSemaphoreCreateInfo semaphoreInfo = VulkanFfm.struct(arena, VkSemaphoreCreateInfo.SIZEOF, VkSemaphoreCreateInfo.ALIGNOF, VkSemaphoreCreateInfo::create).sType$Default();
            VkFenceCreateInfo fenceInfo = VulkanFfm.struct(arena, VkFenceCreateInfo.SIZEOF, VkFenceCreateInfo.ALIGNOF, VkFenceCreateInfo::create)
                    .sType$Default().flags(VK_FENCE_CREATE_SIGNALED_BIT);
            for (int i = 0; i < count; i++) {
                LongBuffer pAvailable = VulkanFfm.longs(arena, 1);
                LongBuffer pFinished = VulkanFfm.longs(arena, 1);
                LongBuffer pFence = VulkanFfm.longs(arena, 1);
                check(vkCreateSemaphore(device, semaphoreInfo, null, pAvailable), "vkCreateSemaphore(imageAvailable)");
                try {
                    check(vkCreateSemaphore(device, semaphoreInfo, null, pFinished), "vkCreateSemaphore(renderFinished)");
                    check(vkCreateFence(device, fenceInfo, null, pFence), "vkCreateFence(frame)");
                    target.frames[i] = new FrameSync(pAvailable.get(0), pFinished.get(0), pFence.get(0));
                } catch (RuntimeException | Error failure) {
                    vkDestroySemaphore(device, pAvailable.get(0), null);
                    if (pFinished.get(0) != 0L) vkDestroySemaphore(device, pFinished.get(0), null);
                    throw failure;
                }
            }
        }
    }

    private void createSwapchain(VulkanPresentationTarget target, long oldSwapchain) {
        try (Arena arena = Arena.ofConfined()) {
            VkSurfaceCapabilitiesKHR capabilities = VulkanFfm.struct(arena, VkSurfaceCapabilitiesKHR.SIZEOF, VkSurfaceCapabilitiesKHR.ALIGNOF, VkSurfaceCapabilitiesKHR::create);
            check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice, target.surface, capabilities),
                    "vkGetPhysicalDeviceSurfaceCapabilitiesKHR");

            IntBuffer formatCount = VulkanFfm.ints(arena, 1);
            check(vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, target.surface, formatCount, null),
                    "vkGetPhysicalDeviceSurfaceFormatsKHR(count)");
            if (formatCount.get(0) == 0) throw new IllegalStateException("surface exposes no formats");
            VkSurfaceFormatKHR.Buffer formats = VulkanFfm.structBuffer(arena, VkSurfaceFormatKHR.SIZEOF, VkSurfaceFormatKHR.ALIGNOF, formatCount.get(0), VkSurfaceFormatKHR::create);
            check(vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, target.surface, formatCount, formats),
                    "vkGetPhysicalDeviceSurfaceFormatsKHR");
            VkSurfaceFormatKHR chosen = chooseSurfaceFormat(formats);

            int width;
            int height;
            if (capabilities.currentExtent().width() != 0xFFFFFFFF) {
                width = capabilities.currentExtent().width();
                height = capabilities.currentExtent().height();
            } else {
                width = clamp(Math.max(target.surfaceFactory.width(), 1),
                        capabilities.minImageExtent().width(), capabilities.maxImageExtent().width());
                height = clamp(Math.max(target.surfaceFactory.height(), 1),
                        capabilities.minImageExtent().height(), capabilities.maxImageExtent().height());
            }

            int imageCount = capabilities.minImageCount() + 1;
            if (capabilities.maxImageCount() > 0) imageCount = Math.min(imageCount, capabilities.maxImageCount());

            VkSwapchainCreateInfoKHR info = VulkanFfm.struct(arena, VkSwapchainCreateInfoKHR.SIZEOF, VkSwapchainCreateInfoKHR.ALIGNOF, VkSwapchainCreateInfoKHR::create)
                    .sType$Default()
                    .surface(target.surface)
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
            LongBuffer pSwapchain = VulkanFfm.longs(arena, 1);
            check(vkCreateSwapchainKHR(device, info, null, pSwapchain), "vkCreateSwapchainKHR");
            long newSwapchain = pSwapchain.get(0);

            IntBuffer imageCountOut = VulkanFfm.ints(arena, 1);
            check(vkGetSwapchainImagesKHR(device, newSwapchain, imageCountOut, null), "vkGetSwapchainImagesKHR(count)");
            LongBuffer images = VulkanFfm.longs(arena, imageCountOut.get(0));
            check(vkGetSwapchainImagesKHR(device, newSwapchain, imageCountOut, images), "vkGetSwapchainImagesKHR");
            long[] newImages = new long[imageCountOut.get(0)];
            long[] newViews = new long[imageCountOut.get(0)];
            for (int i = 0; i < newImages.length; i++) {
                newImages[i] = images.get(i);
                VkImageViewCreateInfo viewInfo = VulkanFfm.struct(arena, VkImageViewCreateInfo.SIZEOF, VkImageViewCreateInfo.ALIGNOF, VkImageViewCreateInfo::create)
                        .sType$Default()
                        .image(newImages[i])
                        .viewType(VK_IMAGE_VIEW_TYPE_2D)
                        .format(chosen.format())
                        .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                                .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
                LongBuffer pView = VulkanFfm.longs(arena, 1);
                check(vkCreateImageView(device, viewInfo, null, pView), "vkCreateImageView(swapchain)");
                newViews[i] = pView.get(0);
            }

            destroySwapchainViews(target);
            if (oldSwapchain != 0L) vkDestroySwapchainKHR(device, oldSwapchain, null);
            target.swapchain = newSwapchain;
            target.swapchainImages = newImages;
            target.swapchainViews = newViews;
            target.swapchainInitialized = new boolean[newImages.length];
            target.swapchainWidth = width;
            target.swapchainHeight = height;
            target.colorFormat = switch (chosen.format()) {
                case VK_FORMAT_B8G8R8A8_UNORM -> TextureFormat.BGRA8_UNORM;
                case VK_FORMAT_R8G8B8A8_UNORM -> TextureFormat.RGBA8_UNORM;
                default -> throw new IllegalStateException("chosen swapchain format lacks portable TextureFormat mapping");
            };
        }
    }

    private void recreateSwapchain(VulkanPresentationTarget target) {
        check(vkDeviceWaitIdle(device), "vkDeviceWaitIdle(recreate swapchain)");
        for (FrameSync frame : target.frames) if (frame != null) reclaimFrame(frame);
        reclaimDeferredSubmissions();
        target.imageAcquired = false;
        target.imageIndex = -1;
        target.currentAcquisition = 0L;
        long old = target.swapchain;
        TextureFormat oldFormat = target.colorFormat;
        createSwapchain(target, old);
        if (oldFormat != target.colorFormat) {
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

    private void destroySwapchainViews(VulkanPresentationTarget target) {
        for (long view : target.swapchainViews) if (view != 0L) vkDestroyImageView(device, view, null);
        target.swapchainViews = new long[0];
        target.swapchainImages = new long[0];
        target.swapchainInitialized = new boolean[0];
    }

    void destroyPresentationTarget(VulkanPresentationTarget target) {
        if (closed) return;
        check(vkDeviceWaitIdle(device), "vkDeviceWaitIdle(close presentation target)");
        for (FrameSync frame : target.frames) {
            if (frame == null) continue;
            reclaimFrame(frame);
            vkDestroySemaphore(device, frame.imageAvailable, null);
            vkDestroySemaphore(device, frame.renderFinished, null);
            vkDestroyFence(device, frame.fence, null);
        }
        target.frames = new FrameSync[0];
        destroySwapchainViews(target);
        if (target.swapchain != 0L) {
            vkDestroySwapchainKHR(device, target.swapchain, null);
            target.swapchain = 0L;
        }
        vkDestroySurfaceKHR(instance, target.surface, null);
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
        try (Arena arena = Arena.ofConfined()) {
            VkBufferCreateInfo info = VulkanFfm.struct(arena, VkBufferCreateInfo.SIZEOF, VkBufferCreateInfo.ALIGNOF, VkBufferCreateInfo::create)
                    .sType$Default()
                    .size(descriptor.size())
                    .usage(VulkanMappings.bufferUsage(descriptor.usage()))
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer pBuffer = VulkanFfm.longs(arena, 1);
            check(vkCreateBuffer(device, info, null, pBuffer), "vkCreateBuffer");
            long buffer = pBuffer.get(0);
            try {
                VkMemoryRequirements requirements = VulkanFfm.struct(arena, VkMemoryRequirements.SIZEOF, VkMemoryRequirements.ALIGNOF, VkMemoryRequirements::create);
                vkGetBufferMemoryRequirements(device, buffer, requirements);
                int memoryType = findMemoryType(
                        requirements.memoryTypeBits(),
                        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                        arena);
                long memory = allocateMemory(requirements.size(), memoryType, arena);
                check(vkBindBufferMemory(device, buffer, memory, 0), "vkBindBufferMemory");

                if (initialData != null && initialData.hasRemaining()) {
                    PointerBuffer mapped = VulkanFfm.pointers(arena, 1);
                    check(vkMapMemory(device, memory, 0, descriptor.size(), 0, mapped), "vkMapMemory");
                    try {
                        VulkanFfm.copyToBorrowed(initialData, mapped.get(0), initialData.remaining());
                    } finally {
                        vkUnmapMemory(device, memory);
                    }
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
        try (Arena arena = Arena.ofConfined()) {
            VkImageCreateInfo info = VulkanFfm.struct(arena, VkImageCreateInfo.SIZEOF, VkImageCreateInfo.ALIGNOF, VkImageCreateInfo::create)
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
            LongBuffer pImage = VulkanFfm.longs(arena, 1);
            check(vkCreateImage(device, info, null, pImage), "vkCreateImage");
            long image = pImage.get(0);
            long memory = 0L;
            long view = 0L;
            try {
                VkMemoryRequirements requirements = VulkanFfm.struct(arena, VkMemoryRequirements.SIZEOF, VkMemoryRequirements.ALIGNOF, VkMemoryRequirements::create);
                vkGetImageMemoryRequirements(device, image, requirements);
                int memoryType = findMemoryType(requirements.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, arena);
                memory = allocateMemory(requirements.size(), memoryType, arena);
                check(vkBindImageMemory(device, image, memory, 0), "vkBindImageMemory");

                VkImageViewCreateInfo viewInfo = VulkanFfm.struct(arena, VkImageViewCreateInfo.SIZEOF, VkImageViewCreateInfo.ALIGNOF, VkImageViewCreateInfo::create)
                        .sType$Default()
                        .image(image)
                        .viewType(VK_IMAGE_VIEW_TYPE_2D)
                        .format(VulkanMappings.format(descriptor.format()))
                        .subresourceRange(r -> r
                                .aspectMask(VulkanMappings.imageAspect(descriptor.format()))
                                .baseMipLevel(0).levelCount(1)
                                .baseArrayLayer(0).layerCount(1));
                LongBuffer pView = VulkanFfm.longs(arena, 1);
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
        try (Arena arena = Arena.ofConfined()) {
            VkBufferCreateInfo bufferInfo = VulkanFfm.struct(arena, VkBufferCreateInfo.SIZEOF, VkBufferCreateInfo.ALIGNOF, VkBufferCreateInfo::create)
                    .sType$Default()
                    .size(size)
                    .usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer pBuffer = VulkanFfm.longs(arena, 1);
            check(vkCreateBuffer(device, bufferInfo, null, pBuffer), "vkCreateBuffer(texture staging)");
            stagingBuffer = pBuffer.get(0);
            VkMemoryRequirements requirements = VulkanFfm.struct(arena, VkMemoryRequirements.SIZEOF, VkMemoryRequirements.ALIGNOF, VkMemoryRequirements::create);
            vkGetBufferMemoryRequirements(device, stagingBuffer, requirements);
            int memoryType = findMemoryType(requirements.memoryTypeBits(),
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, arena);
            stagingMemory = allocateMemory(requirements.size(), memoryType, arena);
            check(vkBindBufferMemory(device, stagingBuffer, stagingMemory, 0), "vkBindBufferMemory(texture staging)");

            PointerBuffer mapped = VulkanFfm.pointers(arena, 1);
            check(vkMapMemory(device, stagingMemory, 0, size, 0, mapped), "vkMapMemory(texture staging)");
            try {
                VulkanFfm.copyToBorrowed(initialData, mapped.get(0), size);
            } finally {
                vkUnmapMemory(device, stagingMemory);
            }

            VkCommandBufferAllocateInfo allocate = VulkanFfm.struct(arena, VkCommandBufferAllocateInfo.SIZEOF, VkCommandBufferAllocateInfo.ALIGNOF, VkCommandBufferAllocateInfo::create)
                    .sType$Default()
                    .commandPool(commandPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1);
            PointerBuffer pCommandBuffer = VulkanFfm.pointers(arena, 1);
            check(vkAllocateCommandBuffers(device, allocate, pCommandBuffer), "vkAllocateCommandBuffers(texture upload)");
            commandBuffer = new VkCommandBuffer(pCommandBuffer.get(0), device);
            VkCommandBufferBeginInfo begin = VulkanFfm.struct(arena, VkCommandBufferBeginInfo.SIZEOF, VkCommandBufferBeginInfo.ALIGNOF, VkCommandBufferBeginInfo::create)
                    .sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            check(vkBeginCommandBuffer(commandBuffer, begin), "vkBeginCommandBuffer(texture upload)");

            VkBufferMemoryBarrier2.Buffer hostWrite = VulkanFfm.structBuffer(arena, VkBufferMemoryBarrier2.SIZEOF, VkBufferMemoryBarrier2.ALIGNOF, 1, VkBufferMemoryBarrier2::create);
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
            VkImageMemoryBarrier2.Buffer toTransfer = imageBarrier(arena, texture,
                    VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT, 0L,
                    VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                    VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            vkCmdPipelineBarrier2(commandBuffer, VulkanFfm.struct(arena, VkDependencyInfo.SIZEOF, VkDependencyInfo.ALIGNOF, VkDependencyInfo::create)
                    .sType$Default().pBufferMemoryBarriers(hostWrite).pImageMemoryBarriers(toTransfer));

            VkBufferImageCopy.Buffer region = VulkanFfm.structBuffer(arena, VkBufferImageCopy.SIZEOF, VkBufferImageCopy.ALIGNOF, 1, VkBufferImageCopy::create);
            region.get(0).bufferOffset(0).bufferRowLength(0).bufferImageHeight(0)
                    .imageSubresource(s -> s.aspectMask(VulkanMappings.imageAspect(texture.format()))
                            .mipLevel(0).baseArrayLayer(0).layerCount(1))
                    .imageOffset(o -> o.set(0, 0, 0))
                    .imageExtent(e -> e.set(texture.width(), texture.height(), 1));
            vkCmdCopyBufferToImage(commandBuffer, stagingBuffer, texture.image,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);

            VkImageMemoryBarrier2.Buffer toInitial = imageBarrier(arena, texture,
                    VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                    VulkanMappings.stageMask(initialState), VulkanMappings.accessMask(initialState),
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VulkanMappings.imageLayout(initialState));
            vkCmdPipelineBarrier2(commandBuffer, VulkanFfm.struct(arena, VkDependencyInfo.SIZEOF, VkDependencyInfo.ALIGNOF, VkDependencyInfo::create)
                    .sType$Default().pImageMemoryBarriers(toInitial));
            check(vkEndCommandBuffer(commandBuffer), "vkEndCommandBuffer(texture upload)");
            VkSubmitInfo submit = VulkanFfm.struct(arena, VkSubmitInfo.SIZEOF, VkSubmitInfo.ALIGNOF, VkSubmitInfo::create).sType$Default()
                    .pCommandBuffers(VulkanFfm.pointers(arena, commandBuffer.address()));
            check(vkQueueSubmit(queue, submit, VK_NULL_HANDLE), "vkQueueSubmit(texture upload)");
            check(vkQueueWaitIdle(queue), "vkQueueWaitIdle(texture upload)");
        } finally {
            if (commandBuffer != null) vkFreeCommandBuffers(device, commandPool, commandBuffer);
            if (stagingBuffer != 0L) vkDestroyBuffer(device, stagingBuffer, null);
            if (stagingMemory != 0L) vkFreeMemory(device, stagingMemory, null);
        }
    }

    private VkImageMemoryBarrier2.Buffer imageBarrier(
            Arena arena, VulkanTexture texture, long srcStage, long srcAccess,
            long dstStage, long dstAccess, int oldLayout, int newLayout) {
        VkImageMemoryBarrier2.Buffer barrier = VulkanFfm.structBuffer(arena, VkImageMemoryBarrier2.SIZEOF, VkImageMemoryBarrier2.ALIGNOF, 1, VkImageMemoryBarrier2::create);
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

    private long allocateMemory(long size, int memoryType, Arena arena) {
        VkMemoryAllocateInfo info = VulkanFfm.struct(arena, VkMemoryAllocateInfo.SIZEOF, VkMemoryAllocateInfo.ALIGNOF, VkMemoryAllocateInfo::create)
                .sType$Default()
                .allocationSize(size)
                .memoryTypeIndex(memoryType);
        LongBuffer pMemory = VulkanFfm.longs(arena, 1);
        check(vkAllocateMemory(device, info, null, pMemory), "vkAllocateMemory");
        return pMemory.get(0);
    }

    private int findMemoryType(int typeBits, int requiredFlags, Arena arena) {
        VkPhysicalDeviceMemoryProperties memory = VulkanFfm.struct(arena, VkPhysicalDeviceMemoryProperties.SIZEOF, VkPhysicalDeviceMemoryProperties.ALIGNOF, VkPhysicalDeviceMemoryProperties::create);
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
        try (Arena arena = Arena.ofConfined()) {
            ByteBuffer nativeCode = VulkanFfm.nativeCopy(arena, code, Integer.BYTES);
            VkShaderModuleCreateInfo info = VulkanFfm.struct(arena, VkShaderModuleCreateInfo.SIZEOF, VkShaderModuleCreateInfo.ALIGNOF, VkShaderModuleCreateInfo::create)
                    .sType$Default()
                    .pCode(nativeCode);
            LongBuffer pModule = VulkanFfm.longs(arena, 1);
            check(vkCreateShaderModule(device, info, null, pModule), "vkCreateShaderModule");
            return track(new VulkanShader(this, pModule.get(0), descriptor.stage(), descriptor.entryPoint()));
        }
    }

    @Override
    public Sampler createSampler(SamplerDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        requireOpen();
        try (Arena arena = Arena.ofConfined()) {
            VkSamplerCreateInfo info = VulkanFfm.struct(arena, VkSamplerCreateInfo.SIZEOF, VkSamplerCreateInfo.ALIGNOF, VkSamplerCreateInfo::create)
                    .sType$Default()
                    .magFilter(VulkanMappings.filter(descriptor.magFilter()))
                    .minFilter(VulkanMappings.filter(descriptor.minFilter()))
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VulkanMappings.addressMode(descriptor.addressMode()))
                    .addressModeV(VulkanMappings.addressMode(descriptor.addressMode()))
                    .addressModeW(VulkanMappings.addressMode(descriptor.addressMode()))
                    .minLod(0f).maxLod(0f)
                    .maxAnisotropy(1f);
            LongBuffer pSampler = VulkanFfm.longs(arena, 1);
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
        try (Arena arena = Arena.ofConfined()) {
            List<VulkanDescriptorLayout> layouts = descriptorLayouts(descriptor.bindingLayouts());
            long pipelineLayout = createPipelineLayout(layouts, arena);
            long pipeline = 0L;
            try {
                pipeline = createGraphicsPipeline(descriptor, vertex, fragment, pipelineLayout, arena);
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
        try (Arena arena = Arena.ofConfined()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VulkanFfm.structBuffer(arena, VkDescriptorSetLayoutBinding.SIZEOF, VkDescriptorSetLayoutBinding.ALIGNOF, layout.bindings().size(), VkDescriptorSetLayoutBinding::create);
            for (int i = 0; i < layout.bindings().size(); i++) {
                Binding<?> binding = layout.bindings().get(i);
                bindings.get(i)
                        .binding(binding.binding())
                        .descriptorType(VulkanMappings.descriptorType(binding.type()))
                        .descriptorCount(1)
                        .stageFlags(VulkanMappings.shaderStages(binding.stages()));
            }
            VkDescriptorSetLayoutCreateInfo info = VulkanFfm.struct(arena, VkDescriptorSetLayoutCreateInfo.SIZEOF, VkDescriptorSetLayoutCreateInfo.ALIGNOF, VkDescriptorSetLayoutCreateInfo::create)
                    .sType$Default()
                    .pBindings(bindings);
            LongBuffer pLayout = VulkanFfm.longs(arena, 1);
            check(vkCreateDescriptorSetLayout(device, info, null, pLayout), "vkCreateDescriptorSetLayout");
            return new VulkanDescriptorLayout(layout, pLayout.get(0));
        }
    }

    private long createPipelineLayout(List<VulkanDescriptorLayout> layouts, Arena arena) {
        LongBuffer handles = VulkanFfm.longs(arena, layouts.size());
        for (VulkanDescriptorLayout layout : layouts) handles.put(layout.handle);
        handles.flip();
        VkPipelineLayoutCreateInfo info = VulkanFfm.struct(arena, VkPipelineLayoutCreateInfo.SIZEOF, VkPipelineLayoutCreateInfo.ALIGNOF, VkPipelineLayoutCreateInfo::create)
                .sType$Default()
                .pSetLayouts(handles);
        LongBuffer pLayout = VulkanFfm.longs(arena, 1);
        check(vkCreatePipelineLayout(device, info, null, pLayout), "vkCreatePipelineLayout");
        return pLayout.get(0);
    }

    private long createGraphicsPipeline(
            GraphicsStateDescriptor descriptor,
            VulkanShader vertex,
            VulkanShader fragment,
            long pipelineLayout,
            Arena arena) {
        VkPipelineShaderStageCreateInfo.Buffer stages = VulkanFfm.structBuffer(arena, VkPipelineShaderStageCreateInfo.SIZEOF, VkPipelineShaderStageCreateInfo.ALIGNOF, 2, VkPipelineShaderStageCreateInfo::create);
        stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertex.module).pName(VulkanFfm.utf8(arena, vertex.entryPoint));
        stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragment.module).pName(VulkanFfm.utf8(arena, fragment.entryPoint));

        VertexLayout vertexLayout = descriptor.vertexLayout();
        VkVertexInputBindingDescription.Buffer vertexBindings = VulkanFfm.structBuffer(arena, VkVertexInputBindingDescription.SIZEOF, VkVertexInputBindingDescription.ALIGNOF, vertexLayout.bindings().size(), VkVertexInputBindingDescription::create);
        for (int i = 0; i < vertexLayout.bindings().size(); i++) {
            VertexBinding binding = vertexLayout.bindings().get(i);
            vertexBindings.get(i)
                    .binding(binding.binding())
                    .stride(binding.stride())
                    .inputRate(binding.inputRate() == VertexInputRate.PER_VERTEX
                            ? VK_VERTEX_INPUT_RATE_VERTEX : VK_VERTEX_INPUT_RATE_INSTANCE);
        }
        VkVertexInputAttributeDescription.Buffer attributes = VulkanFfm.structBuffer(arena, VkVertexInputAttributeDescription.SIZEOF, VkVertexInputAttributeDescription.ALIGNOF, vertexLayout.attributes().size(), VkVertexInputAttributeDescription::create);
        for (int i = 0; i < vertexLayout.attributes().size(); i++) {
            VertexAttribute attribute = vertexLayout.attributes().get(i);
            attributes.get(i)
                    .location(attribute.location())
                    .binding(attribute.binding())
                    .format(VulkanMappings.vertexFormat(attribute.format()))
                    .offset(attribute.offset());
        }
        VkPipelineVertexInputStateCreateInfo vertexInput = VulkanFfm.struct(arena, VkPipelineVertexInputStateCreateInfo.SIZEOF, VkPipelineVertexInputStateCreateInfo.ALIGNOF, VkPipelineVertexInputStateCreateInfo::create)
                .sType$Default()
                .pVertexBindingDescriptions(vertexBindings)
                .pVertexAttributeDescriptions(attributes);
        VkPipelineInputAssemblyStateCreateInfo inputAssembly = VulkanFfm.struct(arena, VkPipelineInputAssemblyStateCreateInfo.SIZEOF, VkPipelineInputAssemblyStateCreateInfo.ALIGNOF, VkPipelineInputAssemblyStateCreateInfo::create)
                .sType$Default().topology(VulkanMappings.topology(descriptor.topology()));
        VkPipelineViewportStateCreateInfo viewport = VulkanFfm.struct(arena, VkPipelineViewportStateCreateInfo.SIZEOF, VkPipelineViewportStateCreateInfo.ALIGNOF, VkPipelineViewportStateCreateInfo::create)
                .sType$Default().viewportCount(1).scissorCount(1);
        VkPipelineRasterizationStateCreateInfo raster = VulkanFfm.struct(arena, VkPipelineRasterizationStateCreateInfo.SIZEOF, VkPipelineRasterizationStateCreateInfo.ALIGNOF, VkPipelineRasterizationStateCreateInfo::create)
                .sType$Default()
                .polygonMode(VK_POLYGON_MODE_FILL)
                .cullMode(VulkanMappings.cull(descriptor.rasterState().cullMode()))
                // The negative-height viewport restores the OpenGL-style Y
                // orientation. Keep counter-clockwise front faces with it.
                .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE)
                .lineWidth(1f);
        VkPipelineMultisampleStateCreateInfo multisample = VulkanFfm.struct(arena, VkPipelineMultisampleStateCreateInfo.SIZEOF, VkPipelineMultisampleStateCreateInfo.ALIGNOF, VkPipelineMultisampleStateCreateInfo::create)
                .sType$Default().rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
        VkPipelineDepthStencilStateCreateInfo depth = VulkanFfm.struct(arena, VkPipelineDepthStencilStateCreateInfo.SIZEOF, VkPipelineDepthStencilStateCreateInfo.ALIGNOF, VkPipelineDepthStencilStateCreateInfo::create)
                .sType$Default()
                .depthTestEnable(descriptor.depthState().testEnabled())
                .depthWriteEnable(descriptor.depthState().writeEnabled())
                .depthCompareOp(VulkanMappings.compare(descriptor.depthState().compareOp()));

        VkPipelineColorBlendAttachmentState.Buffer blendAttachments = VulkanFfm.structBuffer(arena, VkPipelineColorBlendAttachmentState.SIZEOF, VkPipelineColorBlendAttachmentState.ALIGNOF, descriptor.colorFormats().size(), VkPipelineColorBlendAttachmentState::create);
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
        VkPipelineColorBlendStateCreateInfo blend = VulkanFfm.struct(arena, VkPipelineColorBlendStateCreateInfo.SIZEOF, VkPipelineColorBlendStateCreateInfo.ALIGNOF, VkPipelineColorBlendStateCreateInfo::create)
                .sType$Default().pAttachments(blendAttachments);

        IntBuffer dynamicStates = VulkanFfm.intValues(
                arena, VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR);
        VkPipelineDynamicStateCreateInfo dynamic = VulkanFfm.struct(arena, VkPipelineDynamicStateCreateInfo.SIZEOF, VkPipelineDynamicStateCreateInfo.ALIGNOF, VkPipelineDynamicStateCreateInfo::create)
                .sType$Default().pDynamicStates(dynamicStates);

        IntBuffer colorFormats = VulkanFfm.ints(arena, descriptor.colorFormats().size());
        for (TextureFormat format : descriptor.colorFormats()) colorFormats.put(VulkanMappings.format(format));
        colorFormats.flip();
        VkPipelineRenderingCreateInfo rendering = VulkanFfm.struct(arena, VkPipelineRenderingCreateInfo.SIZEOF, VkPipelineRenderingCreateInfo.ALIGNOF, VkPipelineRenderingCreateInfo::create)
                .sType$Default()
                .pColorAttachmentFormats(colorFormats)
                .depthAttachmentFormat(descriptor.depthFormat().map(VulkanMappings::format).orElse(VK_FORMAT_UNDEFINED));

        VkGraphicsPipelineCreateInfo.Buffer pipelineInfo = VulkanFfm.structBuffer(arena, VkGraphicsPipelineCreateInfo.SIZEOF, VkGraphicsPipelineCreateInfo.ALIGNOF, 1, VkGraphicsPipelineCreateInfo::create);
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
        LongBuffer pPipeline = VulkanFfm.longs(arena, 1);
        check(vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, pipelineInfo, null, pPipeline), "vkCreateGraphicsPipelines");
        return pPipeline.get(0);
    }

    @Override
    public BindingSet createBindingSet(BindingSetDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        requireOpen();
        VulkanDescriptorLayout layout = descriptorLayouts(List.of(descriptor.layout())).get(0);
        long descriptorSet = 0L;
        try (Arena arena = Arena.ofConfined()) {
            VkDescriptorSetAllocateInfo allocate = VulkanFfm.struct(arena, VkDescriptorSetAllocateInfo.SIZEOF, VkDescriptorSetAllocateInfo.ALIGNOF, VkDescriptorSetAllocateInfo::create)
                    .sType$Default()
                    .descriptorPool(descriptorPool)
                    .pSetLayouts(VulkanFfm.longs(arena, layout.handle));
            LongBuffer pSet = VulkanFfm.longs(arena, 1);
            check(vkAllocateDescriptorSets(device, allocate, pSet), "vkAllocateDescriptorSets");
            descriptorSet = pSet.get(0);

            VkWriteDescriptorSet.Buffer writes = VulkanFfm.structBuffer(arena, VkWriteDescriptorSet.SIZEOF, VkWriteDescriptorSet.ALIGNOF, descriptor.layout().bindings().size(), VkWriteDescriptorSet::create);
            List<VkDescriptorImageInfo.Buffer> imageInfos = new ArrayList<>();
            List<VkDescriptorBufferInfo.Buffer> bufferInfos = new ArrayList<>();
            List<VulkanResource> dependencies = new ArrayList<>();
            for (int i = 0; i < descriptor.layout().bindings().size(); i++) {
                Binding<?> binding = descriptor.layout().bindings().get(i);
                VkWriteDescriptorSet write = writes.get(i)
                        .sType$Default()
                        .dstSet(descriptorSet)
                        .dstBinding(binding.binding())
                        .descriptorCount(1)
                        .descriptorType(VulkanMappings.descriptorType(binding.type()));
                Object value = descriptor.values().get(binding);
                switch (binding.type()) {
                    case SAMPLED_TEXTURE -> {
                        TextureBinding sampled = (TextureBinding) value;
                        VulkanTexture texture = owned(sampled.texture(), VulkanTexture.class, "sampled texture");
                        VulkanSampler sampler = owned(sampled.sampler(), VulkanSampler.class, "sampler");
                        dependencies.add(texture);
                        dependencies.add(sampler);
                        VkDescriptorImageInfo.Buffer image = VulkanFfm.structBuffer(arena, VkDescriptorImageInfo.SIZEOF, VkDescriptorImageInfo.ALIGNOF, 1, VkDescriptorImageInfo::create);
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
                        VulkanValidation.validateUniformOffset(range.offset(), uniformBufferOffsetAlignment);
                        dependencies.add(buffer);
                        VkDescriptorBufferInfo.Buffer bufferInfo = VulkanFfm.structBuffer(arena, VkDescriptorBufferInfo.SIZEOF, VkDescriptorBufferInfo.ALIGNOF, 1, VkDescriptorBufferInfo::create);
                        bufferInfo.get(0).buffer(buffer.handle).offset(range.offset()).range(range.size());
                        bufferInfos.add(bufferInfo);
                        write.pBufferInfo(bufferInfo);
                    }
                }
            }
            vkUpdateDescriptorSets(device, writes, null);
            return track(new VulkanBindingSet(this, descriptorSet, descriptor, dependencies));
        } catch (RuntimeException | Error failure) {
            if (descriptorSet != 0L) destroyDescriptorSet(descriptorSet);
            throw failure;
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
        if (!(vkTarget instanceof VulkanPresentationTarget presentation)) {
            throw new IllegalArgumentException("render target has no Vulkan presentation integration");
        }
        if (!presentation.imageAcquired) {
            throw new IllegalStateException("presentation target has no acquired image");
        }
        FrameSync frame = presentation.frames[presentation.frameSlot];
        if (!frame.presentationSubmitted) {
            throw new IllegalStateException("presentation target has not been submitted for this frame");
        }
        try (Arena arena = Arena.ofConfined()) {
            // present() is the public frame boundary. Signal renderFinished from an
            // empty queue submission here rather than forcing submit() to guess
            // which command list is the final one for this presentation image.
            // Queue order guarantees that all previously submitted rendering for
            // this image completes before this semaphore is signalled.
            check(vkResetFences(device, VulkanFfm.longs(arena, frame.fence)), "vkResetFences");
            VkSubmitInfo finishSubmit = VulkanFfm.struct(arena, VkSubmitInfo.SIZEOF, VkSubmitInfo.ALIGNOF, VkSubmitInfo::create)
                    .sType$Default()
                    .pSignalSemaphores(VulkanFfm.longs(arena, frame.renderFinished));
            check(vkQueueSubmit(queue, finishSubmit, frame.fence), "vkQueueSubmit(presentation boundary)");
            frame.inFlight = true;

            VkPresentInfoKHR info = VulkanFfm.struct(arena, VkPresentInfoKHR.SIZEOF, VkPresentInfoKHR.ALIGNOF, VkPresentInfoKHR::create)
                    .sType$Default()
                    .pWaitSemaphores(VulkanFfm.longs(arena, frame.renderFinished))
                    // LWJGL cannot infer a count shared by multiple arrays.
                    .swapchainCount(1)
                    .pSwapchains(VulkanFfm.longs(arena, presentation.swapchain))
                    .pImageIndices(VulkanFfm.intValues(arena, presentation.imageIndex));
            int result = vkQueuePresentKHR(queue, info);
            presentation.imageAcquired = false;
            presentation.imageIndex = -1;
            presentation.currentAcquisition = 0L;
            presentation.frameSlot = (presentation.frameSlot + 1) % presentation.frames.length;
            if (result == VK_ERROR_OUT_OF_DATE_KHR || result == VK_SUBOPTIMAL_KHR) {
                recreateSwapchain(presentation);
            } else {
                check(result, "vkQueuePresentKHR");
            }
        }
    }

    @Override
    public CommandEncoder createCommandEncoder() {
        requireOpen();
        try (Arena arena = Arena.ofConfined()) {
            VkCommandBufferAllocateInfo allocate = VulkanFfm.struct(arena, VkCommandBufferAllocateInfo.SIZEOF, VkCommandBufferAllocateInfo.ALIGNOF, VkCommandBufferAllocateInfo::create)
                    .sType$Default()
                    .commandPool(commandPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1);
            PointerBuffer pCommandBuffer = VulkanFfm.pointers(arena, 1);
            check(vkAllocateCommandBuffers(device, allocate, pCommandBuffer), "vkAllocateCommandBuffers");
            VkCommandBuffer commandBuffer = new VkCommandBuffer(pCommandBuffer.get(0), device);
            try {
                VkCommandBufferBeginInfo begin = VulkanFfm.struct(arena, VkCommandBufferBeginInfo.SIZEOF, VkCommandBufferBeginInfo.ALIGNOF, VkCommandBufferBeginInfo::create).sType$Default()
                        .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
                check(vkBeginCommandBuffer(commandBuffer, begin), "vkBeginCommandBuffer");
                return new VulkanCommandEncoder(this, commandBuffer, config.validation());
            } catch (RuntimeException | Error failure) {
                freeCommandBuffer(commandBuffer);
                throw failure;
            }
        }
    }

    @Override
    public synchronized void submit(CommandList commandList) {
        requireOpen();
        if (!(commandList instanceof VulkanCommandList list) || list.device != this) {
            throw new IllegalArgumentException("command list belongs to another backend/device");
        }
        list.requireReady();
        try {
            list.commandState.validateCommittedStates();
            validatePresentationState(list.presentationState);
        } catch (RuntimeException | Error failure) {
            list.failAndRelease();
            throw failure;
        }

        List<VulkanResource> retained = new ArrayList<>(list.resources.size());
        try {
            for (VulkanResource resource : list.resources) {
                resource.retainForSubmission();
                retained.add(resource);
            }
            list.beginSubmission();
        } catch (RuntimeException | Error failure) {
            releaseResources(retained);
            list.failAndRelease();
            throw failure;
        }

        boolean nativeSubmitted = false;
        try (Arena arena = Arena.ofConfined()) {
            VkSubmitInfo submit = VulkanFfm.struct(arena, VkSubmitInfo.SIZEOF, VkSubmitInfo.ALIGNOF, VkSubmitInfo::create)
                    .sType$Default()
                    .pCommandBuffers(VulkanFfm.pointers(arena, list.commandBuffer.address()));

            if (list.presentationState != null) {
                VulkanPresentationTarget presentation = list.presentationState.target;
                FrameSync frame = presentation.frames[presentation.frameSlot];
                frame.commandBuffers.ensureCapacity(frame.commandBuffers.size() + 1);
                frame.resources.ensureCapacity(frame.resources.size() + retained.size());
                // Only the first submission that touches an acquired swapchain
                // image waits on vkAcquireNextImageKHR. Later submissions are
                // naturally ordered on this single graphics queue. present()
                // establishes the final render-finished signal and fence.
                if (!frame.presentationSubmitted) {
                    submit.waitSemaphoreCount(1)
                            .pWaitSemaphores(VulkanFfm.longs(arena, frame.imageAvailable))
                            .pWaitDstStageMask(VulkanFfm.intValues(
                                    arena, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT));
                }
                int result = vkQueueSubmit(queue, submit, VK_NULL_HANDLE);
                if (result != VK_SUCCESS) check(result, "vkQueueSubmit(presentation)");
                nativeSubmitted = true;
                commitSubmittedState(list);
                list.markSubmitted();
                frame.presentationSubmitted = true;
                frame.commandBuffers.add(list.commandBuffer);
                frame.resources.addAll(retained);
            } else {
                deferredCommandBuffers.ensureCapacity(deferredCommandBuffers.size() + 1);
                deferredResources.ensureCapacity(deferredResources.size() + retained.size());
                // Offscreen submissions remain synchronous in this spike so
                // command-buffer/resource lifetime stays simple while the RHI
                // mapping is evaluated. Presentation deliberately does not use
                // this shortcut and keeps two frames in flight.
                int result = vkQueueSubmit(queue, submit, VK_NULL_HANDLE);
                if (result != VK_SUCCESS) check(result, "vkQueueSubmit");
                nativeSubmitted = true;
                commitSubmittedState(list);
                list.markSubmitted();
                int waitResult = vkQueueWaitIdle(queue);
                if (waitResult != VK_SUCCESS) {
                    deferredCommandBuffers.add(list.commandBuffer);
                    deferredResources.addAll(retained);
                    check(waitResult, "vkQueueWaitIdle");
                }
                freeCommandBuffer(list.commandBuffer);
                releaseResources(retained);
            }
        } catch (RuntimeException | Error failure) {
            if (!nativeSubmitted) {
                list.failAndRelease();
                releaseResources(retained);
            }
            throw failure;
        }
    }

    private void validatePresentationState(VulkanPresentationState state) {
        if (state == null) return;
        VulkanPresentationTarget target = state.target;
        target.requireAlive();
        if (!target.imageAcquired
                || state.acquisition != target.currentAcquisition
                || state.imageIndex != target.imageIndex) {
            throw new IllegalStateException("presentation command list has no matching acquired swapchain image");
        }
        if (target.frames[target.frameSlot].inFlight) {
            throw new IllegalStateException("current presentation frame slot has already been presented");
        }
        if (target.swapchainInitialized[state.imageIndex] != state.expectedInitialized) {
            throw new IllegalStateException("presentation image state changed since command-list recording");
        }
    }

    private void commitSubmittedState(VulkanCommandList list) {
        // This single graphics queue orders later submissions after earlier ones,
        // so committed logical state advances when the queue accepts the work;
        // waiting for GPU completion would make subsequent recording stale.
        list.commandState.commitFinalStates();
        if (list.presentationState != null && list.presentationState.recordingInitialized()) {
            list.presentationState.target.swapchainInitialized[list.presentationState.imageIndex] = true;
        }
    }

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
    void destroyDescriptorSet(long descriptorSet) {
        try (Arena arena = Arena.ofConfined()) {
            check(vkFreeDescriptorSets(device, descriptorPool, VulkanFfm.longs(arena, descriptorSet)), "vkFreeDescriptorSets");
        }
    }
    void destroyPipeline(long pipeline, long pipelineLayout) {
        vkDestroyPipeline(device, pipeline, null);
        vkDestroyPipelineLayout(device, pipelineLayout, null);
    }

    void freeCommandBuffer(VkCommandBuffer commandBuffer) {
        vkFreeCommandBuffers(device, commandPool, commandBuffer);
    }

    void freeCommandBufferIfOpen(VkCommandBuffer commandBuffer) {
        if (!closed) freeCommandBuffer(commandBuffer);
    }

    private void freeCommandBuffers(List<VkCommandBuffer> commandBuffers) {
        if (commandBuffers.isEmpty()) return;
        try (Arena arena = Arena.ofConfined()) {
            PointerBuffer buffers = VulkanFfm.pointers(arena, commandBuffers.size());
            for (VkCommandBuffer commandBuffer : commandBuffers) buffers.put(commandBuffer.address());
            buffers.flip();
            vkFreeCommandBuffers(device, commandPool, buffers);
        }
        commandBuffers.clear();
    }

    private static void releaseResources(List<VulkanResource> retained) {
        for (int i = retained.size() - 1; i >= 0; i--) retained.get(i).releaseFromSubmission();
        retained.clear();
    }

    private void reclaimDeferredSubmissions() {
        freeCommandBuffers(deferredCommandBuffers);
        releaseResources(deferredResources);
    }

    @Override
    public void close() {
        if (closed) return;
        vkDeviceWaitIdle(device);
        for (VulkanResource resource : List.copyOf(resources)) {
            if (resource instanceof VulkanPresentationTarget target) {
                for (FrameSync frame : target.frames.clone()) {
                    if (frame != null) reclaimFrame(frame);
                }
            }
        }
        reclaimDeferredSubmissions();
        for (int i = resources.size() - 1; i >= 0; i--) {
            resources.get(i).destroyForDeviceClose();
        }
        for (Map.Entry<BindingLayout, VulkanDescriptorLayout> entry : descriptorLayouts.entrySet()) {
            vkDestroyDescriptorSetLayout(device, entry.getValue().handle, null);
        }
        vkDestroyDescriptorPool(device, descriptorPool, null);
        vkDestroyCommandPool(device, commandPool, null);
        closed = true;
        vkDestroyDevice(device, null);
        vkDestroyInstance(instance, null);
    }

    static void check(int result, String operation) {
        if (result != VK_SUCCESS) throw new IllegalStateException(operation + " failed with VkResult " + result);
    }


    static final class FrameSync {
        final long imageAvailable;
        final long renderFinished;
        final long fence;
        final ArrayList<VkCommandBuffer> commandBuffers = new ArrayList<>();
        final ArrayList<VulkanResource> resources = new ArrayList<>();
        boolean inFlight;
        boolean presentationSubmitted;

        FrameSync(long imageAvailable, long renderFinished, long fence) {
            this.imageAvailable = imageAvailable;
            this.renderFinished = renderFinished;
            this.fence = fence;
        }
    }

    /** Small helper solely to keep arena-owned queue-priority storage alive with its arena. */
    private static final class FloatBufferCompat {
        private final java.nio.FloatBuffer buffer;
        FloatBufferCompat(Arena arena) { buffer = VulkanFfm.floats(arena, 1.0f); }
        java.nio.FloatBuffer buffer() { return buffer; }
    }
}
