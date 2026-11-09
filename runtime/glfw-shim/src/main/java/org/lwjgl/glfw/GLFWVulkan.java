/*
 * Cofre's GLFWVulkan for Android: the three entry points Minecraft's Vulkan backend uses to reach
 * the window. Signatures follow LWJGL 3.4.1 (BSD-3-Clause, see NOTICE).
 */
package org.lwjgl.glfw;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;

import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.FunctionProvider;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRAndroidSurface;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkAndroidSurfaceCreateInfoKHR;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;

public class GLFWVulkan {

    private static final String[] INSTANCE_EXTENSIONS = {"VK_KHR_surface", "VK_KHR_android_surface"};
    private static PointerBuffer instanceExtensions;
    private static ByteBuffer[] extensionNames; // keeps the strings' memory reachable

    protected GLFWVulkan() {
        throw new UnsupportedOperationException();
    }

    public static void glfwInitVulkanLoader(long loader) {}
    public static void setPath(FunctionProvider provider) {}
    public static void setPath(String path) {}

    public static boolean glfwVulkanSupported() {
        return true;
    }

    public static synchronized PointerBuffer glfwGetRequiredInstanceExtensions() {
        if (instanceExtensions == null) {
            extensionNames = new ByteBuffer[INSTANCE_EXTENSIONS.length];
            PointerBuffer names = BufferUtils.createPointerBuffer(INSTANCE_EXTENSIONS.length);
            for (int i = 0; i < INSTANCE_EXTENSIONS.length; i++) {
                byte[] bytes = INSTANCE_EXTENSIONS[i].getBytes(StandardCharsets.US_ASCII);
                extensionNames[i] = BufferUtils.createByteBuffer(bytes.length + 1).put(bytes).put((byte) 0).flip();
                names.put(i, extensionNames[i]);
            }
            instanceExtensions = names;
        }
        return instanceExtensions;
    }

    public static long glfwGetInstanceProcAddress(VkInstance instance, CharSequence name) {
        return VK10.vkGetInstanceProcAddr(instance, name);
    }
    public static long glfwGetInstanceProcAddress(VkInstance instance, ByteBuffer name) {
        return VK10.vkGetInstanceProcAddr(instance, name);
    }

    /** Android requires every queue family of every device to support presentation. */
    public static boolean glfwGetPhysicalDevicePresentationSupport(VkInstance instance, VkPhysicalDevice device, int queueFamily) {
        return true;
    }

    /**
     * Creates the VkSurfaceKHR on the activity's SurfaceView. While the activity has no surface
     * (the device is asleep), this blocks until a new one exists; the game then rebuilds its swapchain.
     */
    public static int glfwCreateWindowSurface(VkInstance instance, long window, VkAllocationCallbacks allocator, LongBuffer surface) {
        GLFW.loadNative();
        long nativeWindow = GLFW.nAcquireWindow();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAndroidSurfaceCreateInfoKHR info = VkAndroidSurfaceCreateInfoKHR.calloc(stack).sType$Default().window(nativeWindow);
            return KHRAndroidSurface.vkCreateAndroidSurfaceKHR(instance, info, allocator, surface);
        } finally {
            GLFW.nReleaseWindow(nativeWindow);
        }
    }
    public static int glfwCreateWindowSurface(VkInstance instance, long window, VkAllocationCallbacks allocator, long[] surface) {
        LongBuffer out = BufferUtils.createLongBuffer(1);
        int result = glfwCreateWindowSurface(instance, window, allocator, out);
        surface[0] = out.get(0);
        return result;
    }
}
