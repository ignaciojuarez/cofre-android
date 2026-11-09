package com.ignacio.cofre.probe;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;

/**
 * Walks the path Minecraft's Vulkan backend takes to reach the screen: GLFW shim → VkInstance with
 * the shim's extensions → VkSurfaceKHR on the SurfaceView → physical devices. Debug builds only.
 */
public final class VulkanProbe {
    private VulkanProbe() {}

    public static void main(String[] args) {
        if (!GLFW.glfwInit() || !GLFWVulkan.glfwVulkanSupported()) throw new IllegalStateException("GLFW/Vulkan unavailable");
        long window = GLFW.glfwCreateWindow(0, 0, "probe", 0L, 0L);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer extensions = GLFWVulkan.glfwGetRequiredInstanceExtensions();
            VkApplicationInfo app = VkApplicationInfo.calloc(stack).sType$Default()
                .pApplicationName(stack.UTF8("cofre-probe")).apiVersion(VK10.VK_MAKE_API_VERSION(0, 1, 2, 0));
            VkInstanceCreateInfo info = VkInstanceCreateInfo.calloc(stack).sType$Default()
                .pApplicationInfo(app).ppEnabledExtensionNames(extensions);
            PointerBuffer pInstance = stack.mallocPointer(1);
            check(VK10.vkCreateInstance(info, null, pInstance), "vkCreateInstance");
            VkInstance instance = new VkInstance(pInstance.get(0), info);

            LongBuffer pSurface = stack.mallocLong(1);
            check(GLFWVulkan.glfwCreateWindowSurface(instance, window, null, pSurface), "glfwCreateWindowSurface");
            long surface = pSurface.get(0);
            System.out.println("[Cofre] vulkan probe instance+surface ok surface=0x" + Long.toHexString(surface));

            IntBuffer count = stack.mallocInt(1);
            check(VK10.vkEnumeratePhysicalDevices(instance, count, null), "vkEnumeratePhysicalDevices");
            PointerBuffer devices = stack.mallocPointer(count.get(0));
            check(VK10.vkEnumeratePhysicalDevices(instance, count, devices), "vkEnumeratePhysicalDevices");
            for (int i = 0; i < devices.capacity(); i++) {
                VkPhysicalDevice device = new VkPhysicalDevice(devices.get(i), instance);
                VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
                VK10.vkGetPhysicalDeviceProperties(device, props);
                IntBuffer supported = stack.mallocInt(1);
                KHRSurface.vkGetPhysicalDeviceSurfaceSupportKHR(device, 0, surface, supported);
                int api = props.apiVersion();
                System.out.println("[Cofre] vulkan probe device=" + props.deviceNameString()
                    + " api=" + VK10.VK_API_VERSION_MAJOR(api) + "." + VK10.VK_API_VERSION_MINOR(api) + "." + VK10.VK_API_VERSION_PATCH(api)
                    + " present=" + (supported.get(0) != 0));
            }
            KHRSurface.vkDestroySurfaceKHR(instance, surface, null);
            VK10.vkDestroyInstance(instance, null);
        }
        System.out.println("[Cofre] vulkan probe done");
    }

    private static void check(int result, String call) {
        if (result != VK10.VK_SUCCESS) throw new IllegalStateException(call + " failed: " + result);
    }
}
